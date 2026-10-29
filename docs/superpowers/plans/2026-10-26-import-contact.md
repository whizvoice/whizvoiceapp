# Import Contact Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the model save a phone or Google contact into Whiz in one call, and have the server decide deterministically when to offer it.

**Architecture:** `get_contact_preference` already finds phone/Google candidates when a Whiz contact is missing. A new `contact_import` module records those candidates per conversation in Redis, watches select/draft/send tool calls as they pass through `execute_tool_with_queue`, and after a successful WhatsApp/SMS send attaches a `suggest_import` hint (or `suggest_update_preferred_app` when a Whiz contact was used on the other app) to the send result. A new `import_contact` tool consumes the hint's `candidate_id` and copies the stored snapshot into the Whiz contacts store, or records a decline.

**Tech Stack:** Python (FastAPI server in `whizvoice/`), async redis, Kotlin (Android `whizvoiceapp/`).

**Spec:** Design agreed in chat 2026-10-26; summarized in the "Design" section below.

## Design (as agreed)

- Tool `import_contact(candidate_id, preferred_app, nicknames, decline)`. `candidate_id` is `phone:<LOOKUP_KEY>` or `google:<resourceName>`. Phone `LOOKUP_KEY` survives re-syncs; `_ID` does not.
- Snapshot of the candidate (name, labeled phones/emails/addresses, primary flags, the spellings the user said) is kept in Redis for 24h under the conversation. Import reads the snapshot; no second device or People API round-trip.
- Import merges into an existing Whiz contact with the same real name rather than duplicating. Existing fields win; missing fields are filled.
- Offer only after a successful send, once per candidate per conversation. A decline is stored in the contacts preference and respected forever.
- No offer for group chats or raw phone numbers (a candidate only exists when a lookup matched a named contact, so these never produce one).
- `add_contact_preference` stays for contacts that exist nowhere else.

## Global Constraints

- Server tests run with `whizvoice/venv/bin/pytest whizvoice/tests`. Never use another venv.
- Do not write to `raw_training_data.jsonl` or any training data.
- `contact_import` must never raise into the tool path: every public async function swallows and logs.
- Android changes must stay backward compatible: an older app that omits `lookup_key` still produces candidates (fall back to display name as `source_id`).
- Do not commit; the user commits.

## Review Focus

- Older app build returns phone contacts without `lookup_key`: candidate still gets an id (`phone:<display_name>`) and import still works. Test in Task 3.
- `conversation_id` is `None` (tool executed outside a conversation): `observe` and `record_candidates` are no-ops, no Redis key `contact_import:user:None`. Test in Task 2.
- Send result is not a dict or carries `error`/`success: False`: no hint attached, `offered` not marked, so a retry can still trigger the offer. Test in Task 2.
- A nickname in `spoken_as` already indexes to a different Whiz contact: import skips it and reports `skipped_nicknames` rather than re-pointing the index. Test in Task 4.
- The same candidate is used twice in one conversation (two messages): the hint appears once. Test in Task 2.

---

### Task 1: Android returns `lookup_key` and primary flags

**Files:**
- Modify: `whizvoiceapp/app/src/main/java/com/example/whiz/tools/DeviceControlTools.kt:1361-1520` (`lookupPhoneContacts`)

**Interfaces:**
- Produces: each contact JSON gains `lookup_key` (string, from `ContactsContract.Contacts.LOOKUP_KEY` / `Data.LOOKUP_KEY`), and optional `primary_phone` / `primary_email` (the label string of the row whose `IS_SUPER_PRIMARY != 0`, else absent).

- [ ] **Step 1:** Add `LOOKUP_KEY` to both the name query projection (`ContactsContract.Contacts.LOOKUP_KEY`) and the nickname query projection (`ContactsContract.Data.LOOKUP_KEY`). Carry it alongside display name and `matched_on` in the `contactIds` map (change the value type to a small data class `PhoneContactHit(displayName, matchedOn, lookupKey)`), and `put("lookup_key", ...)` on `contactJson`.
- [ ] **Step 2:** Add `IS_SUPER_PRIMARY` to the Phone and Email projections. When a row has it set, after the loop `put("primary_phone", finalLabel)` / `put("primary_email", finalLabel)` for that row's label.
- [ ] **Step 3:** Compile: `cd whizvoiceapp && ./gradlew :app:compileDebugKotlin -q`.

---

### Task 2: `contact_import` Redis tracker and hint builder

**Files:**
- Create: `whizvoice/contact_import.py`
- Test: `whizvoice/tests/test_contact_import.py`

**Interfaces:**
- Produces:
  - `init_redis_client(client) -> None` (same pattern as `tool_dedupe.init_redis_client`).
  - `candidate_id(source: str, source_id: str) -> str` returns `f"{source}:{source_id}"`.
  - `async record_candidates(user_id, conversation_id, queried_name, source, contacts: list[dict]) -> None`. Mutates each contact dict in place to add `candidate_id`. `source_id` is `contact.get("lookup_key")` for `phone`, `contact.get("resource_name")` for `google`, falling back to `display_name`. Stores a snapshot: the contact dict plus `source`, `source_id`, and `queried_as` (list of `normalize_nickname(queried_name)` values accumulated across calls). Appends `queried_name` to `queries`.
  - `async record_resolved(user_id, conversation_id, queried_name, contact: dict) -> None`. Stores `resolved = {"real_name", "preferred_app", "nicknames", "queried_as": [...]}` from a Whiz contact result.
  - `async get_candidate(user_id, conversation_id, candidate_id) -> Optional[dict]`.
  - `async observe(tool_name, tool_args, user_id, conversation_id, result) -> Any`. Returns `result`, possibly annotated. See behavior below.
- State: one Redis string key `contact_import:{user_id}:{conversation_id}`, JSON `{"queries": [], "candidates": {cid: snapshot}, "resolved": null|{...}, "active": null|{"candidate_id": cid, "app": app}|{"resolved": true, "app": app}, "offered": []}`, TTL `24 * 3600`.

**`observe` behavior:**
- `TARGET_TOOLS = {"agent_select_chat", "agent_draft_message", "agent_whatsapp_select_chat", "agent_whatsapp_draft_message", "agent_sms_select_chat", "agent_sms_draft_message"}`. App is `tool_args["app"]` or derived from the tool name (`whatsapp`/`sms`). If app not in `{"whatsapp", "sms"}` or no `contact_name`, return unchanged. Normalize `contact_name` with `contacts_tools.normalize_nickname`. Match, in order: a candidate whose normalized `display_name` equals it or whose `queried_as` contains it; else `resolved` whose normalized `real_name`, `nicknames`, or `queried_as` contains it. Set `active` accordingly (or clear it when nothing matches). Return result unchanged.
- `SEND_TOOLS = {"agent_send_message", "agent_whatsapp_send_message", "agent_sms_send_message"}`. App as above; require `whatsapp`/`sms`. Require `isinstance(result, dict)`, `"error" not in result`, `result.get("success") is not False`. Require `active`.
  - Candidate active: skip if `cid in offered` or `contacts_tools.is_import_declined(user_id, cid)` (import lazily inside the function to avoid a cycle). Else set `result["suggest_import"] = {"candidate_id", "display_name", "source", "used_app": app, "spoken_as": queried_as, "instruction": "The message was sent to a contact that is not saved in Whiz. After confirming it was sent, ask once, in one short sentence, whether to save <display_name> as a <app> contact. If yes, call import_contact with this candidate_id and preferred_app='<app>'. If no, call import_contact with this candidate_id and decline=true. Do not ask again in this conversation."}` and append cid to `offered`.
  - Resolved active with `preferred_app != app`: skip if `"resolved" in offered`. Else set `result["suggest_update_preferred_app"] = {"real_name", "current_preferred_app", "used_app": app, "instruction": "This contact is saved with <current> as preferred app but the message went via <app>. Ask once whether to switch; if yes call add_contact_preference with real_name and preferred_app='<app>'."}` and append `"resolved"` to `offered`.
- Any other tool: return unchanged. `_redis_client is None` or `conversation_id is None` or `user_id` falsy: return unchanged.

- [ ] **Step 1:** Write `tests/test_contact_import.py` with an in-memory fake redis (`async get`, `async set(key, value, ex=None)`) installed via `init_redis_client`. Tests:

```python
async def test_record_candidates_assigns_ids_and_stores_snapshot()
    # phone contact with lookup_key "k1" -> candidate_id "phone:k1"; snapshot has queried_as ["jonny"]
async def test_record_candidates_falls_back_to_display_name_without_lookup_key()
    # candidate_id == "phone:Jonathan Smith"
async def test_record_candidates_noop_without_conversation_id()
    # fake redis has no keys afterwards; contact dict has no candidate_id
async def test_observe_draft_then_send_attaches_hint_once()
    # record_candidates; observe agent_draft_message(app=whatsapp, contact_name="Jonny");
    # observe agent_send_message(app=whatsapp) with {"status":"sent"} -> suggest_import present with used_app whatsapp, spoken_as ["jonny"];
    # second send -> no suggest_import
async def test_observe_send_failure_does_not_consume_offer()
    # send result {"error": "..."} -> no hint; next successful send -> hint present
async def test_observe_send_skips_declined()
    # patch contacts_tools.is_import_declined -> True; no hint
async def test_observe_resolved_contact_on_other_app_suggests_update()
    # record_resolved preferred_app sms; draft via whatsapp; send -> suggest_update_preferred_app with used_app whatsapp
async def test_observe_non_dict_result_passthrough()
    # result "ok" returned unchanged
```

- [ ] **Step 2:** Implement `contact_import.py` per the Interfaces block.

---

### Task 3: Wire candidates into `get_contact_preference` and Google results

**Files:**
- Modify: `whizvoice/google_contacts.py:149-166` (`_person_to_contact`)
- Modify: `whizvoice/contacts_tools.py:205-408` (`get_contact_preference`), `:658-677` (tool schema description)
- Test: `whizvoice/tests/test_contacts_tools.py` (new)

**Interfaces:**
- Consumes: `contact_import.record_candidates`, `contact_import.record_resolved`.
- Produces: every entry in `device_contacts` and `google_contacts` returned by `get_contact_preference` carries `candidate_id`. `_person_to_contact` output gains `"resource_name"`.

- [ ] **Step 1:** Tests in `tests/test_contacts_tools.py`, patching `contacts_tools.get_preference` to return an empty store and `device_control_tools.agent_lookup_phone_contacts` with `AsyncMock`:

```python
async def test_device_candidates_get_candidate_id()
    # device result contacts=[{"display_name":"Jonathan Smith","lookup_key":"k1"}] with websocket=object(), conversation_id="c1"
    # -> result["device_contacts"][0]["candidate_id"] == "phone:k1"
async def test_found_whiz_contact_records_resolved()
    # store with contact real_name "Robin Pham" preferred sms; patch contact_import.record_resolved AsyncMock; assert awaited with that contact
def test_person_to_contact_includes_resource_name()
```

- [ ] **Step 2:** In `_person_to_contact`, add `"resource_name": person.get("resourceName")`.
- [ ] **Step 3:** In `get_contact_preference`: hoist the nested `_build_result` to module-level `_contact_result(contact)`. In `_build_and_enrich`, `await contact_import.record_resolved(user_id, conversation_id, name, contact)` before returning. After `device_contacts` is non-empty, `await contact_import.record_candidates(user_id, conversation_id, name, "phone", device_contacts)`; do the same with `"google"` for `matches` from `_google_matches_if_missing` and for `google_contacts` in the final fallback.
- [ ] **Step 4:** Append to the `get_contact_preference` tool description: "Phone and Google matches carry a candidate_id; pass it to import_contact if the user later agrees to save the contact."

---

### Task 4: `import_contact` tool

**Files:**
- Modify: `whizvoice/contacts_tools.py` (new functions + schema appended to `contacts_tools` list)
- Test: `whizvoice/tests/test_contacts_tools.py`

**Interfaces:**
- Consumes: `contact_import.get_candidate`.
- Produces:
  - `is_import_declined(user_id, candidate_id) -> bool` reads `data.get("import_declined", {})`.
  - `async import_contact(user_id, candidate_id, preferred_app=None, nicknames=None, decline=False, conversation_id=None) -> dict`.
  - Tool schema `import_contact` with properties `candidate_id` (string, required), `preferred_app` (enum whatsapp/sms), `nicknames` (array of string), `decline` (boolean). Description: "Save a phone or Google contact into Whiz using the candidate_id returned by get_contact_preference or a suggest_import hint. Copies all phone numbers, emails and addresses and sets the preferred messaging app. Pass nicknames for any other way the user refers to this person. Pass decline=true to record that the user does not want this contact saved, so it is not offered again. Use add_contact_preference instead for a contact that exists nowhere else."

**`import_contact` behavior:**
- `decline=True`: load store, set `data.setdefault("import_declined", {})[candidate_id] = <iso timestamp>`, save, return `{"success": True, "declined": True, "message": ...}`.
- Otherwise `preferred_app` must be `whatsapp`/`sms` else `{"success": False, "error": ...}`. `candidate = await contact_import.get_candidate(...)`; if `None` return `{"success": False, "error": "Contact candidate expired or unknown. Call get_contact_preference again and use the new candidate_id."}`.
- `real_name = candidate["display_name"]`. `existing = _find_contact_by_name(data, normalize_nickname(real_name))`. Create a new contact (same shape as `add_contact_preference` builds) or merge into `existing`: for each of `phone_numbers`, `emails`, `addresses`, `setdefault` each label (existing values win). `primary_phone` / `primary_email` = existing value, else `candidate.get("primary_phone")` / `candidate.get("primary_email")`, else first label. `primary_address` likewise with first label. Set `preferred_app`, `source`, `source_id`.
- Nicknames: `nicknames or []` plus `candidate.get("queried_as", [])`, normalized, deduped, excluding `normalize_nickname(real_name)` (that one is added only when the contact is new, matching `add_contact_preference`). For each: if `nickname_index` maps it to a different contact, append to `skipped_nicknames`; else add to `contact["nicknames"]` and index.
- Save. Return `{"success": True, "merged": bool, "contact": _contact_result(contact), "skipped_nicknames": [...], "message": "Saved <real_name> (<preferred_app>) with nicknames [...]"}`.

- [ ] **Step 1:** Tests:

```python
async def test_import_creates_contact_with_all_fields_and_spoken_nicknames()
    # candidate phone:k1 display "Jonathan Smith", phone_numbers {"mobile":"+1","work":"+2"}, primary_phone "work", emails {"personal":"a@b"}, queried_as ["jonny"]
    # -> saved store has one contact, preferred_app whatsapp, primary_phone "work", nicknames ["jonathan smith","jonny"], source "phone", source_id "k1"
async def test_import_merges_into_existing_contact_without_overwriting()
    # existing "Jonathan Smith" with phone_numbers {"mobile":"+9"}, preferred sms
    # -> merged True, phone_numbers {"mobile":"+9","work":"+2"}, preferred_app whatsapp
async def test_import_skips_nickname_owned_by_other_contact()
    # index has "jonny" -> other id; result skipped_nicknames == ["jonny"]
async def test_import_unknown_candidate_errors()
async def test_decline_persists_and_is_import_declined()
```

- [ ] **Step 2:** Implement per the behavior block.

---

### Task 5: Register the tool and the observer in `app.py`; update send-tool descriptions

**Files:**
- Modify: `whizvoice/app.py:32` (import), `:916` (init redis), `:2149-2200` (registry), `:2371-2425` (`execute_tool_with_queue`)
- Modify: `whizvoice/messaging_tools.py` tool descriptions for `agent_send_message`, `agent_whatsapp_send_message`, `agent_sms_send_message`
- Test: `whizvoice/tests/test_contact_import_integration.py`

**Interfaces:**
- Consumes: `contact_import.observe`, `contact_import.init_redis_client`, `contacts_tools.import_contact`.

- [ ] **Step 1:** Integration test, following `test_tool_dedupe_integration.py`: patch `app.execute_tool` (AsyncMock returning `{"status": "sent"}`) and `app.contact_import.observe` (AsyncMock returning the result with `suggest_import` added); assert `execute_tool_with_queue("agent_send_message", {...}, "u1", conversation_id="c1")` returns the annotated dict and `observe` was awaited with `("agent_send_message", args, "u1", "c1", exec_result)`. Also assert `"import_contact" in TOOL_REGISTRY` and that it is rejected without `candidate_id`.
- [ ] **Step 2:** `import contact_import` and `from contacts_tools import ... import_contact`. Call `contact_import.init_redis_client(redis_client)` next to `tool_dedupe.init_redis_client`.
- [ ] **Step 3:** Registry entry `"import_contact"`: `requires_auth True`, `is_async True`, `needs_websocket True` (for `conversation_id`), `args_mapping` → `(user_id, args.get('candidate_id'), args.get('preferred_app'), args.get('nicknames'), bool(args.get('decline')), kwargs.get('conversation_id'))`, validation: `candidate_id` required; `preferred_app` must be whatsapp/sms unless `decline`.
- [ ] **Step 4:** In `execute_tool_with_queue`, after `result` is obtained and before `tool_dedupe.record_call`: `result = await contact_import.observe(tool_name, tool_args, user_id, context.get("conversation_id"), result)`.
- [ ] **Step 5:** Append to the three send-tool descriptions: "If the result contains suggest_import or suggest_update_preferred_app, follow its instruction after confirming the send."
