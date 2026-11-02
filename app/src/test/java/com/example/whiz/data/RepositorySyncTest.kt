package com.example.whiz.data

import android.content.Context
import android.content.SharedPreferences
import com.example.whiz.data.api.ApiService
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.local.ChatDao
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.data.local.MessageDao
import com.example.whiz.data.local.WhizDatabase
import com.example.whiz.data.repository.ConversationsFetchResult
import com.example.whiz.data.repository.TransactionRunner
import com.example.whiz.data.repository.WhizRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.inject.Provider

/**
 * Room is the single source of truth for chats: syncChats() merges server rows into Room
 * with upsert (never REPLACE, which would cascade-delete messages), keeps the later
 * lastMessageTime, honours tombstones, and prunes synced rows only on a full sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepositorySyncTest {

    private val apiService: ApiService = mock()
    private val chatDao: ChatDao = mock()
    private val messageDao: MessageDao = mock()
    private val context: Context = mock()
    private val prefs: SharedPreferences = mock()
    private val prefsEditor: SharedPreferences.Editor = mock()
    private val authRepository: AuthRepository = mock()
    private val database: WhizDatabase = mock()

    private lateinit var repository: WhizRepository

    // Stands in for RoomDatabase.withTransaction (not drivable with mocked DAOs): records how
    // many transactions were opened and whether a DAO call happens inside one.
    private var transactionsOpened = 0
    private var inTransaction = false
    private val recordingRunner = object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R {
            transactionsOpened++
            inTransaction = true
            try {
                return block()
            } finally {
                inTransaction = false
            }
        }
    }

    @Before
    fun setUp() {
        // Suspend DAO methods return Object at the bytecode level, so unstubbed ones yield null
        // even for non-null Kotlin return types; give the ones the code consumes real defaults.
        runBlocking {
            whenever(chatDao.getAllChatsSnapshot()).thenReturn(emptyList())
            whenever(chatDao.deleteSyncedChatsNotIn(any())).thenReturn(0)
            whenever(chatDao.deleteAllSyncedChats()).thenReturn(0)
            whenever(chatDao.deleteChat(any())).thenReturn(1)
            whenever(chatDao.updateChatLastMessageTime(any(), any())).thenReturn(1)
            whenever(chatDao.insertIfAbsent(any())).thenReturn(1L)
        }
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.edit()).thenReturn(prefsEditor)
        whenever(prefsEditor.putString(any(), anyOrNull())).thenReturn(prefsEditor)
        whenever(prefsEditor.clear()).thenReturn(prefsEditor)
        // Never signed in: the repository's startup sync stays parked on the token wait,
        // so it cannot race with the sync each test drives explicitly.
        whenever(authRepository.serverToken).thenReturn(MutableStateFlow(null))
        whenever(chatDao.getAllChatsFlow()).thenReturn(flowOf(emptyList()))

        repository = WhizRepository(
            apiService,
            context,
            messageDao,
            chatDao,
            database,
            Provider { authRepository }
        )
        repository.transactionRunner = recordingRunner
    }

    private fun conversation(
        clientId: String,
        serverId: Long,
        title: String = "Title",
        lastMessageTime: String = "1970-01-01T00:00:01Z",
        deletedAt: String? = null
    ) = ApiService.ConversationResponse(
        id = serverId,
        client_chat_id = clientId,
        user_id = "user",
        title = title,
        created_at = "1970-01-01T00:00:00Z",
        last_message_time = lastMessageTime,
        source = "app",
        deleted_at = deletedAt
    )

    private fun response(
        conversations: List<ApiService.ConversationResponse>,
        isIncremental: Boolean = false
    ) = ApiService.ConversationsResponse(
        conversations = conversations,
        server_timestamp = "2026-11-01T00:00:00Z",
        is_incremental = isIncremental,
        count = conversations.size
    )

    @Test
    fun syncChats_usesUpsertNotInsert() = runTest {
        whenever(apiService.getConversationsIncremental(anyOrNull()))
            .thenReturn(response(listOf(conversation("a", 1L))))

        repository.syncChats(forceFull = true)

        // ChatDao has no insert/REPLACE method any more; the old full sync wiped every row
        // with deleteAllChats() and re-inserted, cascading the open chat's messages away.
        verify(chatDao).upsertAll(listOf(ChatEntity("a", 1L, "Title", 0L, 1000L)))
        verify(chatDao, never()).deleteAllChats()
    }

    @Test
    fun syncChats_writePhaseRunsInOneTransaction() = runTest {
        whenever(apiService.getConversationsIncremental(anyOrNull()))
            .thenReturn(response(listOf(conversation("a", 1L))))
        // The local snapshot feeding the max-merge and prune guard must be read inside it.
        whenever(chatDao.getAllChatsSnapshot()).thenAnswer {
            assertTrue("local rows read outside the transaction", inTransaction)
            emptyList<ChatEntity>()
        }
        whenever(chatDao.upsertAll(any())).thenAnswer {
            assertTrue("upsertAll outside the transaction", inTransaction)
            Unit
        }
        whenever(chatDao.deleteSyncedChatsNotIn(any())).thenAnswer {
            assertTrue("prune outside the transaction", inTransaction)
            0
        }

        val result = repository.syncChats(forceFull = true)

        assertFalse(result.isCachedData)
        assertEquals(1, transactionsOpened)
        verify(chatDao).getAllChatsSnapshot()
        verify(chatDao).upsertAll(any())
        verify(chatDao).deleteSyncedChatsNotIn(listOf("a"))
    }

    @Test
    fun applySyncWrites_keepsRowThatGotServerIdAfterSnapshotStarted() = runTest {
        // "late" was created locally and got its serverId (recordServerId) after the server's
        // full list was taken, so the response omits it. The guard must keep it.
        val requestStartedAt = 10_000L
        whenever(chatDao.getAllChatsSnapshot()).thenReturn(
            listOf(ChatEntity("late", 9L, "New", createdAt = requestStartedAt + 5, lastMessageTime = requestStartedAt + 5))
        )
        val fetch = ConversationsFetchResult(
            conversations = listOf(conversation("a", 1L)),
            isIncremental = false,
            serverTimestamp = "2026-11-01T00:00:00Z",
            isCachedData = false,
            requestStartedAt = requestStartedAt
        )

        repository.applySyncWrites(fetch, forceFull = true)

        verify(chatDao).deleteSyncedChatsNotIn(listOf("a", "late"))
    }

    @Test
    fun syncChats_keepsLaterLocalLastMessageTime() = runTest {
        whenever(chatDao.getAllChatsSnapshot()).thenReturn(
            listOf(ChatEntity("a", 1L, "Title", createdAt = 0L, lastMessageTime = 2000L))
        )
        whenever(apiService.getConversationsIncremental(anyOrNull()))
            .thenReturn(response(listOf(conversation("a", 1L, lastMessageTime = "1970-01-01T00:00:01Z"))))

        repository.syncChats()

        val captor = argumentCaptor<List<ChatEntity>>()
        verify(chatDao).upsertAll(captor.capture())
        assertEquals(2000L, captor.firstValue.single().lastMessageTime)
    }

    @Test
    fun syncChats_takesServerTitle() = runTest {
        whenever(chatDao.getAllChatsSnapshot()).thenReturn(
            listOf(ChatEntity("a", null, "Hi...", createdAt = 0L, lastMessageTime = 500L))
        )
        whenever(apiService.getConversationsIncremental(anyOrNull()))
            .thenReturn(response(listOf(conversation("a", 1L, title = "Hi there, how are you"))))

        repository.syncChats()

        val captor = argumentCaptor<List<ChatEntity>>()
        verify(chatDao).upsertAll(captor.capture())
        val upserted = captor.firstValue.single()
        assertEquals("Hi there, how are you", upserted.title)
        assertEquals(1L, upserted.serverId)
    }

    @Test
    fun syncChats_fullSync_deletesSyncedRowsNotReturned() = runTest {
        whenever(apiService.getConversationsIncremental(anyOrNull()))
            .thenReturn(response(listOf(conversation("a", 1L), conversation("b", 2L))))

        repository.syncChats(forceFull = true)

        verify(chatDao).deleteSyncedChatsNotIn(listOf("a", "b"))
    }

    @Test
    fun syncChats_fullSync_emptyResponse_deletesSyncedRowsWithoutEmptyNotIn() = runTest {
        // Room expands NOT IN () for an empty list; the empty case must use its own query.
        whenever(apiService.getConversationsIncremental(anyOrNull()))
            .thenReturn(response(emptyList()))

        repository.syncChats(forceFull = true)

        verify(chatDao).deleteAllSyncedChats()
        verify(chatDao, never()).deleteSyncedChatsNotIn(any())
        verify(chatDao, never()).deleteAllChats()
    }

    @Test
    fun syncChats_tombstone_deletesRow() = runTest {
        whenever(prefs.getString(eq("last_sync_conversations"), anyOrNull()))
            .thenReturn("2026-10-31T00:00:00Z")
        whenever(apiService.getConversationsIncremental(anyOrNull())).thenReturn(
            response(
                listOf(conversation("gone", 5L, deletedAt = "2026-10-31T12:00:00Z")),
                isIncremental = true
            )
        )

        repository.syncChats()

        verify(chatDao).deleteChat("gone")
        verify(chatDao, never()).deleteSyncedChatsNotIn(any())
    }

    @Test
    fun syncChats_setsFirstSyncCompleteEvenOnFailure() = runTest {
        whenever(apiService.getConversationsIncremental(anyOrNull()))
            .thenThrow(RuntimeException("network down"))

        val result = repository.syncChats(forceFull = true)

        assertTrue(repository.isFirstSyncComplete.value)
        assertTrue(result.isCachedData)
        verify(chatDao, never()).deleteSyncedChatsNotIn(any())
        verify(chatDao, never()).deleteAllSyncedChats()
    }

    @Test
    fun deleteChat_alwaysCallsRestByClientId_404IsSuccess() = runTest {
        whenever(chatDao.getChatById("local")).thenReturn(ChatEntity("local", null, "Draft"))
        val notFound = retrofit2.HttpException(
            retrofit2.Response.error<Any>(
                404,
                okhttp3.ResponseBody.create(null, "")
            )
        )
        whenever(apiService.deleteConversation("local")).thenAnswer { throw notFound }

        repository.deleteChat("local")

        verify(apiService).deleteConversation("local")
        verify(chatDao).deleteChat("local")
    }

    @Test
    fun deleteChat_restFailure_keepsLocalRow() = runTest {
        whenever(chatDao.getChatById("local")).thenReturn(ChatEntity("local", 7L, "Draft"))
        whenever(apiService.deleteConversation("local")).thenAnswer { throw java.io.IOException("offline") }

        repository.deleteChat("local")

        verify(apiService).deleteConversation("local")
        verify(chatDao, never()).deleteChat(any())
    }

    @Test
    fun ensureChatRowForIncoming_syncsBeforeCreating() = runTest {
        whenever(chatDao.getChatById("incoming")).thenReturn(null)
        whenever(apiService.getConversationsIncremental(anyOrNull())).thenReturn(response(emptyList()))

        repository.ensureChatRowForIncoming("incoming", "Hello there")

        val order = inOrder(apiService, chatDao)
        order.verify(apiService).getConversationsIncremental(anyOrNull())
        order.verify(chatDao, atLeastOnce()).getChatById("incoming")
        order.verify(chatDao).insertIfAbsent(argThat { id == "incoming" && serverId == null && title == "Hello there" })
    }

    @Test
    fun createChatLocal_existingRow_makesNoFurtherWrites() = runTest {
        // A sync wrote the row first: INSERT OR IGNORE returns -1 and nothing overwrites it.
        whenever(chatDao.insertIfAbsent(any())).thenReturn(-1L)

        repository.createChatLocal("synced", "Hello")

        verify(chatDao).insertIfAbsent(argThat { id == "synced" && serverId == null })
        verify(chatDao, never()).upsert(any())
        verify(chatDao, never()).upsertAll(any())
        verify(chatDao, never()).updateTitle(any(), any())
        verify(chatDao, never()).setServerId(any(), any())
    }

    @Test
    fun addUserMessageOptimistic_bumpsLastMessageTime() = runTest {
        whenever(messageDao.getMessagesForChatFlow("c")).thenReturn(flowOf(emptyList()))
        whenever(messageDao.insertMessage(any())).thenAnswer {
            assertTrue("message insert outside the transaction", inTransaction)
            7L
        }
        whenever(chatDao.updateChatLastMessageTime(any(), any())).thenAnswer {
            assertTrue("lastMessageTime bump outside the transaction", inTransaction)
            1
        }

        val id = repository.addUserMessageOptimistic("c", "hi", "req-1", 5000L)

        assertEquals(7L, id)
        assertEquals(1, transactionsOpened)
        // The DAO's UPDATE takes MAX(lastMessageTime, :timestamp), so this never lowers it.
        verify(chatDao).updateChatLastMessageTime("c", 5000L)
    }
}
