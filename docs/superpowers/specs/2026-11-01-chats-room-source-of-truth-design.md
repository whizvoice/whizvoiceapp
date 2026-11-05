# Client UUID chat identity, Room as the single source of truth

Date: 2026-11-01
Status: draft, awaiting approval
Scope: whizvoiceapp and whizvoice (server). Two-repo change, server deploys first.

## Intent

A chat is identified by a UUID the client mints when the chat is created. The
server stores that UUID on the conversation and echoes it on every reply and
every sync row. The client never learns a new id for a chat, so the
optimistic-to-server "migration" (two rows, an in-memory id map, a filter that
hides one of the pair, a ~170-line `migrateChatMessages`, and an event that
tells every holder of the old id to switch) is deleted rather than improved.

The chats list becomes a plain Room query. Network sync writes into Room.
Every screen observes Room. There is no refresh trigger and no in-memory copy
of the server list.

Trigger for this work: CI run 41346613809. On an account with no chats, the
first chat created never appears in the list because the list pipeline only
re-ran on a hand-fired trigger that the sync path never fired. Investigation
showed the pipeline itself (fetch-inside-a-cold-flow, one private copy per
collector) was the problem, and that the id swap was the reason it existed.

## Goals

- One identity per chat for its whole life, on both client and server.
- Chats list and chat screen observe Room and update on every Room write.
- One network fetch per sync regardless of observer count.
- Zero-chat accounts see the empty state after the first sync, not a spinner.
- Chat creation is idempotent: a retried first message cannot create two
  server conversations.
- Delete, not deprecate: the optimistic-migration code paths on both sides are
  removed in the same change.

## Non-goals

- Changing message identity. Messages already carry a client `request_id`.
- Any UI change beyond what falls out of the data flow (empty state after
  sync, no "Loading..." forever).
- Paginating the chats list in the UI.

## Anti-goals

Things that make this a failure even if the list renders:

- A chat visibly duplicating, vanishing, or changing position when the server
  confirms it.
- The chat screen losing its thinking indicator or its messages when the
  server confirms the chat (covered by PowerButtonForegroundTest and
  AppLifecycleIntegrationTest).
- A WebSocket reconnect when the server confirms the chat.
- A sync that deletes real chats because the list endpoint capped its result.
- A full sync blanking the message list of the chat currently on screen.

## Identity model

| | Today | After |
|---|---|---|
| Client primary key | `Long`; negative `-System.currentTimeMillis()` until confirmed, then the server's integer | `String` UUID v4, minted on the client, never changes |
| Server primary key | integer `conversations.id` | unchanged |
| Link between them | `conversations.optimistic_chat_id` (text, holds the negative number as a string) | `conversations.client_chat_id` (text, holds the UUID), unique per user, server-defaulted for rows created elsewhere |
| What the client sends | `conversation_id` (int) once known, else `client_conversation_id` (negative int) | `client_chat_id` (string) always |
| What the server returns | `conversation_id` plus `client_conversation_id` echo | `conversation_id` plus `client_chat_id` |
| What REST paths accept | integer id | integer id or UUID; server resolves |

The client stores the server's integer as `serverId: Long?` on the chat row
for diagnostics and bug reports. No client code path depends on it.

Naming: the UUID identifies the chat, nothing else. Not the device
(`device_id`), not the user (`user_id`), not a message turn (`request_id`). On
the server and on the wire it is `client_chat_id`, "the chat id the client
assigned", because the server already has an integer called `conversation_id`
and the two must not be confused. In the app it is simply `chatId`; after this
change the app has no other chat identifier.

## Server (whizvoice)

### Schema

Executed once in the Supabase SQL editor (the repo has no schema-as-code):

```sql
-- Pre-check: nothing today prevents two rows with the same optimistic id for one
-- user (that is one of the bugs this fixes). The unique index below fails if any
-- exist, so find them first and give all but the oldest a fresh id.
SELECT user_id, optimistic_chat_id, count(*) FROM conversations
 WHERE optimistic_chat_id IS NOT NULL GROUP BY 1, 2 HAVING count(*) > 1;
UPDATE conversations c SET optimistic_chat_id = gen_random_uuid()::text
 WHERE optimistic_chat_id IS NOT NULL AND id <> (
   SELECT min(id) FROM conversations d
    WHERE d.user_id = c.user_id AND d.optimistic_chat_id = c.optimistic_chat_id);

ALTER TABLE conversations RENAME COLUMN optimistic_chat_id TO client_chat_id;
UPDATE conversations SET client_chat_id = gen_random_uuid()::text WHERE client_chat_id IS NULL;
ALTER TABLE conversations ALTER COLUMN client_chat_id SET DEFAULT gen_random_uuid()::text;
ALTER TABLE conversations ALTER COLUMN client_chat_id SET NOT NULL;
CREATE UNIQUE INDEX conversations_user_client_chat_id ON conversations (user_id, client_chat_id);
```

Rename rather than add: the column is already text and every server lookup is
already string equality on it. Old rows keep their negative-number strings as
their `client_chat_id`. They are unique, they resolve, and nothing special-cases
them. This is my call; a fresh column with a backfill works too.

The default means conversations created by any other client (web, assistant,
REST `POST /conversations`) get a `client_chat_id` too, so the app can key every
conversation it ever sees by `client_chat_id`.

### Resolution helper

`database.resolve_conversation_id(conversation_ref, user_id)` accepts an
`int`, a numeric string, or a UUID string and returns the integer id or
`None`. Numeric input verifies ownership as today. Non-numeric input looks up
`client_chat_id`. This is the one function that knows both forms. Everything that
today branches on `conversation_id < 0` calls it instead. There are about 19
such sites across `app.py` and `database.py`, plus the `int()` parses of
`client_conversation_id`.

### WebSocket protocol

- Connect: `?client_chat_id=<uuid>&device_id=...`. `?conversation_id=<int>` keeps
  working for other clients. Whichever is present is resolved to the integer
  session conversation id; if `client_chat_id` has no row yet, the session is the
  "new" kind exactly as a negative id is today.
- Inbound messages (`message`, `tool_result`, `tool_status`, and the rest):
  `client_chat_id` replaces `client_conversation_id`. `save_message_to_db` looks up
  or creates the conversation by `(user_id, client_chat_id)`. The unique index makes
  a concurrent duplicate create fail, and the handler catches that and re-reads
  the row, which is what makes creation idempotent.
- Outbound (`response`, `broadcast`, `tool_execution`, `delete_message`,
  `response_cancelled`, etc.): every payload that carries `conversation_id`
  also carries `client_chat_id`. The ten sites that build
  `"client_conversation_id": ...` become `"client_chat_id": ...`, and the value is
  read from the conversation row rather than echoed from the request, so it is
  present even when the request did not include it.
- Legacy `client_conversation_id` (negative int) stays accepted at the
  boundary until the new app is installed: the resolver treats a negative
  integer as a `client_chat_id` string (`str(n)`), which is exactly what old
  rows hold. This lets the rewritten server be verified end to end with the
  old app before the app work starts. It is removed in the last server commit,
  after the app has shipped.

### REST

Every `/api/conversations/{id}...` route (get, update title, delete,
last_message_time, messages, messages/count, check-retry, pending-requests)
takes the path parameter as a string and passes it through
`resolve_conversation_id`. `ConversationResponse` gains `client_chat_id`.
`POST /conversations` accepts an optional `client_chat_id` and returns the row.

`GET /api/conversations` (full and incremental) must return every row for the
user. Today it issues one Supabase select with no range, so it is subject to
PostgREST's default row cap (1000). The server loops with `.range()` until a
short page. This is required because the client's full sync treats the result
as complete (section "Sync" below).

### Session and channel keying

Today every live-conversation key on the server is built from the integer
conversation id, which does not exist until the first message has been saved.
So the first message runs under a temporary key named after the client's
negative id, and after the insert the server renames: `rename_chat_session`,
`set_redirect` in Redis (`session_redirect:*`), `update_websocket_conversation`
(subscribe new channel, re-register socket, unsubscribe old), the
`SessionMappingManager` and `LocalObjectManager` id caches, and a second
publish to the optimistic channel in both broadcast functions. Every later
lookup follows the redirect. That is the server-side copy of the two-rows
problem, and it goes away the same way: key by the identity that exists from
the first byte.

After this change, these are keyed by `client_chat_id` and never renamed:

| Key | Today | After |
|---|---|---|
| Chat session (Redis context, timestamps, active requests) | `ws_{user}_conv_{int}` or `ws_{user}_conv_{negative}` or `ws_{user}_new_{time}` | `ws_{user}_conv_{client_chat_id}` |
| Pub/sub channel | `conversation:{int}` (+ `conversation:{negative}` during the window) | `conversation:{client_chat_id}` |
| Pending-tools counter | `conversation:{int}:pending_tools` | `conversation:{client_chat_id}:pending_tools` |
| Socket registration (`register_conversation_websocket`, `get_conversation_websockets`) | by `int` | by `client_chat_id` |

Rules:

- On connect, resolve whatever the URL carried (`client_chat_id`, or `conversation_id`
  from another client) to a `client_chat_id` once. If a `conversation_id` has no row
  the connection is rejected as today. If a `client_chat_id` has no row yet, the
  session is created under that key and the row is created on first message;
  nothing about the session changes when the row appears.
- `process_message_task` receives `client_chat_id` and the session key derived from
  it. The per-message "route to a different session because
  `client_conversation_id` is set" branch collapses to "session key is always
  derived from the message's `client_chat_id`".
- The integer id is obtained from the row when the database needs it
  (`save_message_to_db`, `load_conversation_history`, message queries,
  `RequestMessageTracker`, which indexes database message ids). A small cached
  `client_chat_id_for(conversation_id)` / `conversation_id_for(client_chat_id)` pair in
  `database.py` replaces `SessionMappingManager` and the
  `LocalObjectManager` id caches. Callers that today hold only the integer
  (REST handlers, `check_and_retry_failed_messages`, broadcasts triggered from
  REST) look up the `client_chat_id` through it before touching a session or
  channel.
- Broadcast functions publish once, to `conversation:{client_chat_id}`.

Files: `app.py`, `database.py`, `redis_managers.py` (the int-typed
optimistic paths in `ChatSessionManager`, `SessionMappingManager` and
`LocalObjectManager`), `tool_result_handler.py` if it builds session ids, and
`tests/test_redis_managers.py`.

Deleted: `update_websocket_conversation`, `rename_chat_session`,
`ChatSessionManager.set_redirect` / `get_redirect` / `resolve_session_id` /
`rename_session` and the `resolve_session_id` calls inside its other methods,
`SessionMappingManager`, `LocalObjectManager.get_optimistic_id_cached` /
`get_real_id_cached` / `cache_id_mapping` / `clear_id_mapping_cache` and the
`optimistic_id` parameter of `register_conversation_websocket`, the
`opt_channel_name` publishes, the "migrate old optimistic session" block at
connect, the `ws_{user}_new_{time}` session form, and
`local_objects.optimistic_to_real` / `real_to_optimistic`.

Why this belongs in the same change rather than a follow-up: the WebSocket
handler, `process_message_task` and `save_message_to_db` are being rewritten
for the `client_chat_id` boundary anyway. Doing the keying later means touching the
same functions twice and carrying the redirect machinery through one more
release.

### Tests

`tests/test_resolve_conversation_id.py` extends to cover the UUID form, the
numeric-string form, and not-found. Other server tests that construct
`client_conversation_id` update the field name.

## App (whizvoiceapp)

### Schema (Room version 3 to 4, destructive)

```kotlin
@Entity(tableName = "chats", indices = [Index(value = ["serverId"])])
data class ChatEntity(
    @PrimaryKey val id: String,            // client UUID
    val serverId: Long? = null,            // filled by sync / first reply; diagnostics only
    val title: String,
    val createdAt: Long,
    val lastMessageTime: Long
)

@Entity(tableName = "messages",
    foreignKeys = [ForeignKey(entity = ChatEntity::class, parentColumns = ["id"],
        childColumns = ["chatId"], onDelete = CASCADE)],
    indices = [Index("chatId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    ...unchanged
)
```

`optimisticChatId` is gone. The migration is destructive: bump the version and
let the existing `fallbackToDestructiveMigration` drop the cache. Room is a
cache of the server except for optimistic chats that never reached the server,
and with one user that loss is acceptable on this one upgrade. Startup full
sync repopulates. This is my call.

### Routes and the new-chat sentinel

`chat/{chatId}` takes a `String`. The `-1` sentinel is gone. Anything that
wants a new chat mints an id and navigates to it:

```kotlin
object Chat : Screen("chat/{chatId}") {
    fun route(chatId: String) = "chat/$chatId"
    fun newChatRoute() = route(UUID.randomUUID().toString())
}
```

Call sites: `WhizNavHost` start-destination for assistant launch and the FAB
`onNewChatClick`; `MainActivity` voice-launch `navigateWhenReady`; the power
button `FORCE_NEW_CHAT` path in `ChatScreen`. The row is still created lazily
on first send, as today, so an abandoned new-chat screen leaves nothing in
Room. "Is this a new chat with no row yet" is answered by
`chatDao.getChatById(id) == null`, not by a sentinel.

### Repository: single source of truth

Public surface after the change (chat-related):

```kotlin
fun observeChats(): Flow<List<ChatEntity>>            // chatDao.getAllChatsFlow()
fun observeMessages(chatId: String): Flow<List<MessageEntity>>
suspend fun syncChats(forceFull: Boolean = false): SyncResult
suspend fun syncMessages(chatId: String)              // existing fetch+dedupe, keyed by client id
suspend fun createChatLocal(id: String, title: String)       // first send
suspend fun addUserMessageOptimistic(chatId: String, ...)    // also bumps chats.lastMessageTime
suspend fun addAssistantMessage...(chatId: String, ...)      // also bumps chats.lastMessageTime
suspend fun updateChatTitle(chatId: String, title: String)   // REST then Room
suspend fun deleteChat(chatId: String)                       // REST by client id (404 = success) then Room
suspend fun deleteAllChats()
suspend fun cleanupStaleUnsyncedChats()               // serverId == null, > 1h old, 0 messages
val isFirstSyncComplete: StateFlow<Boolean>           // process-wide, set after first syncChats returns or throws
```

Deleted: `getAllChatsFlow`, `getAllChats`, `_conversations`/`conversations`,
`_conversationsRefreshTrigger`, `triggerConversationsRefresh`,
`refreshConversations`, `setupReactiveLoading`, `performIncrementalSync`,
`migrateChatMessages`, `migrationLocks`, `registerChatMigration`,
`getActualChatId`, `areChatsMigrated`, `getMigratedChatId`,
`getOptimisticChatId`, `chatMigrationEvents`, `createChatOptimistic`
(replaced by `createChatLocal`), `getChatDao()` (tests use the DAO via Hilt
or a test-only accessor; builder's call), and the 14 trigger call sites.

#### Sync

`syncChats(forceFull)`:

1. Fetch via the existing `fetchConversationsWithDeduplication` (keep the
   in-flight request dedupe). Incremental uses the stored `since`; full passes
   none.
2. In one Room transaction, for each returned conversation:
   - `deleted_at != null` → `chatDao.deleteChat(client_chat_id)`.
   - else → `chatDao.upsert(ChatEntity(id = client_chat_id, serverId = id, title,
     createdAt, lastMessageTime))`. Room `@Upsert`, not `INSERT OR REPLACE`:
     REPLACE deletes the old row first and the FK cascade would wipe that
     chat's local messages, blanking an open chat screen.
   - `title`: the server's value always wins. The client only ever changes a
     title through REST-then-Room `updateChatTitle`, so the server is already
     the authority, and the client's first-send title (20 chars) is a
     placeholder for the server's (50 chars) that the list shows today.
   - `lastMessageTime`: take the larger of local and server. The client bumps
     it locally when it inserts a message (below), which is after the server's
     own bump for the same message, so a stale sync row must not move a chat
     the user just wrote in back down the list.
3. On a full sync only: delete local rows with `serverId != null` whose
   `client_chat_id` was not returned. Safe only because the server endpoint now
   returns all rows (see Server / REST). Rows with `serverId == null` are never
   touched by sync.
4. Store the new `since`. Set `isFirstSyncComplete = true` in `finally`.
5. Return `SyncResult(isCachedData)` for the offline snackbar.

`Repository.init` runs `syncChats(forceFull = true)` after the existing
sign-in wait, replacing the `getAllChatsIncremental` into `_conversations`.

#### Messages arriving for an unknown chat

A WebSocket `response`/`broadcast` can reference a `client_chat_id` with no local
row (created on another device, or this device's row was dropped). Today the
code inserts a placeholder row titled "Loading..." to satisfy the FK, and that
row was invisible because the list came from the server response. With Room
as the list it would show. The handler instead calls `syncChats()` first (an
incremental sync, cheap), and only if the row is still absent inserts a row
titled from the message's first line, the same derivation `sendMessage` uses.
No filter in the list query.

#### lastMessageTime

Nothing bumps `chats.lastMessageTime` in Room today; ordering came from the
server list. `addUserMessageOptimistic` and the assistant-message inserts
update it in the same transaction as the message insert so the chat moves to
the top of the Room-ordered list immediately. The REST
`updateConversationLastMessageTime` call stays as the server-side bump.

### ChatsListViewModel and screen

```kotlin
val chats = repository.observeChats().stateIn(viewModelScope, WhileSubscribed(5000), emptyList())
val isFirstSyncComplete = repository.isFirstSyncComplete
fun refreshChats() { viewModelScope.launch { _isRefreshing.value = true; try { syncChats() ... } finally { _isRefreshing.value = false } } }
```

- `ChatsListScreen` shows "Loading chats..." only while `chats.isEmpty() &&
  !isFirstSyncComplete`. After one sync has finished (success or failure) an
  empty account shows `EmptyChatsList`. Offline shows the existing cached-data
  snackbar, not the spinner.
- Remove the `chats.first { it.isNotEmpty() }` coroutine. Keep the
  stale-unsynced cleanup call in `init`.
- `MainActivity` and `AssistantActivity` keep their own instances; a second
  observer of a Room flow costs nothing. Remove them if unused.
- `PreloadManager.preloadChatsList` and its two `WhizNavHost` call sites are
  deleted. `preloadChat(chatId)` becomes a one-shot `syncMessages(chatId)` or
  is deleted; builder's call.

### ChatViewModel

- `_chatId: StateFlow<String>`. A given chat's id never changes. The
  ViewModel is Activity-scoped (one instance serves every chat screen), so
  its current-chat pointer moves when `loadChatWithVoiceMode(chatId)` is
  called for a different chat; that is a pointer change, not an id change.
  The initial value falls back to a freshly minted UUID when savedStateHandle
  holds none. "New chat" means "no local row yet", never a sentinel.
  (Corrected during implementation; the original text said the value never
  changed for the life of the screen.) The `savedStateHandle["chatId"]`
  persistence block, the `chatMigrationEvents` collector, the two WebSocket
  "scenario 1 / scenario 2" blocks (~920 to 1030), the third block around
  ~1095 to 1130, and the `getMigratedChatId` fallbacks at ~1052 and ~1070 are
  deleted. The `getActualChatId` calls at ~1962 and ~2125 are deleted.
- `pendingRequests: Map<String, String>` (requestId → chatId). Entries are
  never remapped. The reply's `client_chat_id` is compared directly.
- `sendMessage` first-send path: if `chatDao.getChatById(_chatId.value) ==
  null`, `createChatLocal(_chatId.value, derivedTitle)` then insert the
  message. No id is assigned here, so the "don't update `_chatId` yet to avoid
  a race" dance is gone.
- The three `refreshConversations()` calls (~1200, ~1540, ~2074) are deleted
  with nothing in their place. Room writes already update the list; the list
  screen's own `refreshChats()` on entry picks up server titles.
- The FORCE_NEW_CHAT handler in `ChatScreen` navigates to
  `Screen.Chat.newChatRoute()`, popping the current chat entry inclusively
  (not `popUpTo(Home)`: after an assistant cold start Home is not on the back
  stack and that pop would be a no-op, stacking chat entries). The new entry
  gets `ENABLE_VOICE_MODE`.

### WhizServerRepository

- `connect(clientId: String?)` builds `?client_chat_id=...&device_id=...`. The
  "already connected to this conversation" check compares `client_chat_id`
  strings; the "migration, keep socket" branch and `areChatsMigrated` are
  deleted because the id never changes.
- Outbound payloads put `client_chat_id` instead of `conversation_id` /
  `client_conversation_id`.
- Inbound parsing reads `client_chat_id` (string) and still reads `conversation_id`
  to fill `serverId` on the chat row via `chatDao.setServerId(clientId,
  serverId)` when the row has none. That write is bookkeeping; nothing waits
  on it.
- `WebSocketEvent.Message(content, requestId, clientId: String, serverTimestamp)`.
- The `connectionStateManager.registerChatMigration` block at ~783 and the
  queued-message routing through `getEffectiveChatId` (~1173, ~1293) are
  deleted; routing compares `client_chat_id` strings.

### ConnectionStateManager

Keeps `activeConversationId: StateFlow<String?>`, `isOnChatScreen`,
`lastActiveConversationId`. The migration map, timestamps, cleanup and the six
mapping functions are deleted. The bubble overlay reads
`activeConversationId` and is unaffected beyond the type.

### Everything else holding a chat id

`ChatScreen`, `PreloadManager`, `ApiService` (`@Path("id") id: String`),
`BugReportSubmitter` and `ScreenAgentTools` (pass `conversationId = null`
today; type change only). Mechanical `Long` → `String`.

`AssistantActivity` creates an "Assistant Chat" row eagerly before
navigating. It follows the same rule as every other entry point: mint an id,
navigate, let first send create the row. Otherwise an abandoned assistant
launch leaves an empty chat in the list.

### Tests

`repository.getAllChats()` is called from about 50 sites in 18 instrumented
test files, mostly as "snapshot ids before, snapshot after, delete the
difference" for cleanup, including shared code in `BaseIntegrationTest`. It is
removed from the repository. The tests get a helper in `BaseIntegrationTest`:

```kotlin
suspend fun listChats(): List<ChatEntity> { repository.syncChats(); return repository.observeChats().first() }
```

and `deleteChat(clientId)` always calls REST by client id (404 counts as
success; other failures keep the local row for retry). Call sites are updated mechanically. This is its own commit.

Instrumented tests that exercise chat identity and need review beyond the
mechanical rename: `WebSocketReconnectionTest` (reads the DAO directly),
`MessageDisplayAndLifecycleTest`, `VoiceLaunchDetectionIntegrationTest`,
`MessageFlowComposeTest`, `MessageFlowVoiceComposeTest`,
`ChatViewModelComposeTest`, `SettingsIntegrationTest` (hard sync),
`AppLifecycleIntegrationTest`, `PowerButtonForegroundTest`,
`ChatLoadErrorTest`, `ChatsListLoadErrorTest`.

Unit tests under `app/src/test` construct `ChatEntity` in 4 files (8 sites):
mechanical.

One new instrumented test: empty account, create a chat, press back, the row
is present. That is the exact CI failure.

## Behaviour end to end

1. User taps New Chat. App navigates to `chat/7f3a…`. No row exists.
2. User sends the first message. `sendMessage` inserts chat `7f3a…` and the
   message in one transaction, `pendingRequests[req] = "7f3a…"`, and connects
   the socket with `client_chat_id=7f3a…`. The list (if visible) shows the row at
   once.
3. Server receives `message` with `client_chat_id = 7f3a…`, finds no row, inserts
   `conversations(client_chat_id = 7f3a…)` and gets integer 25407. A concurrent
   duplicate insert hits the unique index and re-reads instead.
4. Server replies `response { conversation_id: 25407, client_chat_id: "7f3a…" }`.
5. App stores `serverId = 25407` on row `7f3a…`. Nothing else changes: not
   `_chatId`, not `pendingRequests`, not the row's position, not the socket.
6. If the user pressed the power button for a new chat between steps 2 and 4,
   they are on `chat/9c1e…`; the reply for `7f3a…` is stored under `7f3a…` and
   their screen does not move. The CI race cannot occur because there is no id
   to hijack with.
7. A list sync that returns `7f3a…` before step 4 upserts the same row (fills
   `serverId`, maybe a server title). Same row, no duplicate.

## Rollout order

1. Server: schema SQL in Supabase, then deploy the server change
   (`git pull` + `systemctl restart whizvoice` on host `whiz`). The server
   accepts `client_chat_id` on all surfaces and still accepts the old
   negative-int `client_conversation_id`, mapped to its string form.
2. Verify with the old app still installed: new chat, first message, tool
   call, reply, second device or web broadcast if available. This exercises
   the rekeyed sessions with the only client that exists at that point.
3. App: install the new build. First launch drops the Room cache and full-syncs.
4. Server: remove the legacy `client_conversation_id` handling.

Server-first is forced: the app cannot send `client_chat_id` to a server that
ignores it, but the server can carry the column before any client uses it.

## Constraints

- Platform: PostgREST caps a single select at 1000 rows by default; the list
  endpoint must page. Without this the client's full-sync deletion rule is
  unsafe.
- Platform: Room `INSERT OR REPLACE` deletes-then-inserts and fires
  `onDelete = CASCADE`. Use `@Upsert`.
- Platform: `WhizServerRepository`'s OkHttp `onMessage` is not a coroutine.
  Any Room write it needs (`setServerId`, the unknown-chat sync) is launched
  and sequenced before the corresponding `WebSocketEvent` is emitted, in the
  same coroutine.
- Project: the server's `client_chat_id` column is text. Old rows carry a negative
  number as a string. Do not add a UUID format check anywhere on the server.
- Project (memory: bubble overlay is a primary use mode): `ConnectionStateManager.
  activeConversationId` must keep being set when a chat gains a row while no
  screen is in the foreground.
- Project (memory: wake word always opens a new chat): every wake-word /
  assistant / power-button entry point mints a fresh id; none reuse the last
  chat.
- Project: the `requestId → chatId` map in `ChatViewModel` is what keeps the
  thinking indicator alive across navigation. It stays; it just stops being
  remapped.

## Left to the builder

- Exact Room DAO spellings and the `@Upsert` conflict handling for the
  "server newer than local" rule.
- Whether `preloadChat` survives as a one-shot or is deleted.
- Whether the `MainActivity`/`AssistantActivity` `ChatsListViewModel`
  instances survive.
- How tests reach the DAO once `getChatDao()` is gone.
- Server: cache shape and TTL for `client_chat_id_for` / `conversation_id_for`;
  in-process dict per worker is enough.
- Server: whether Redis keys from the old scheme are flushed on deploy or left
  to expire. Sessions are rebuilt from the database on connect either way.
- Commit order. A sensible split: (1) server schema + resolver + protocol +
  REST + tests. (1b) server session and channel keying; deploy both together.
  (2) App schema, entities, DAO, `Screen.Chat` routes.
  (3) Repository rewrite and ViewModels. (4) WhizServerRepository and
  ConnectionStateManager. (5) Test helper and mechanical test updates.
  (6) PreloadManager and remaining deletions.
