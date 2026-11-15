# Sync, backup and conflict rules

Code: `core/.../sync/Sync.kt` (tests in `SyncMergerTest.kt`), `app/.../sync/SyncManager.kt`.

## Storage

Sync uses one JSON file, `todoplanner-sync-v1.json`, in the hidden Google Drive **appDataFolder**
(scope `drive.appdata`). The app can't see the user's other Drive files, and the user can't see
this file in the Drive UI. Local file backups use the same format (`SnapshotCodec`) and are saved
and opened through Android's file picker.

## Record model

Every list, task, subtask and occurrence completion is a record with:

- `id`: a random UUID, created once and never reused. Records are matched only by id, never by
  title, so nothing gets duplicated.
- `version`: a hybrid logical clock (`millis << 16 | counter`). It keeps increasing even if a
  device's clock is wrong, and it advances past any version seen from another device.
- `deviceId`, used to break exact ties.
- `deleted`: a tombstone. Deleted rows stay in the database and in the sync file.
- `seenDeleteVersion`: on a live record, the newest tombstone version the editing device had
  seen.
- `schemaVersion` on the snapshot. A device refuses to read a snapshot from a newer app version.

## Merge rules (simultaneous edits)

1. **Live vs live**: higher version wins (last writer wins, per record). Ties go to the higher
   `deviceId`, so every device picks the same winner.
2. **Deleted vs live**: **deletion wins**. The only exception is a live edit made *after its
   device saw that deletion*, such as Undo after a delete
   (`live.version > tomb.version && live.seenDeleteVersion >= tomb.version`). An offline device
   that edits a task without knowing it was deleted therefore cannot bring it back.
3. **Deleted vs deleted**: higher version wins.
4. Completions are separate records. Completing a task on the watch while renaming it on a
   tablet keeps both changes.
5. Tombstones are kept for 180 days, then removed everywhere.

The merge is idempotent. Syncing the same data twice changes nothing.

## Sync procedure

1. Get an access token silently. If consent is needed, background sync stops and Settings shows
   "Sign in again".
2. List the sync files. If two devices created the file at the same moment, all copies are
   merged and the extras are deleted afterwards.
3. Download, then merge with all local records, tombstones included.
4. If anything changes locally, a safety copy of local data is written to
   `files/safety/before-*.json` (the last 3 are kept). The changes are then applied in **one
   Room transaction**.
5. Reminders are rebuilt (`reconcile(rebuildAll = true)`), and the widget and watch refresh.
6. If the remote copy is behind, it's uploaded after a revision check. If another device uploaded
   in the meantime, the whole cycle repeats (up to 3 times).

**Failure safety**: a download or network error leaves local data untouched. A failure during
apply rolls the transaction back. A failed upload leaves local data merged and correct, and the
next sync uploads it. Local changes are never discarded.

- **Back up now**: a full sync that always uploads.
- **Restore**: a pull-only merge. Nothing on this device is lost, but deletions from Drive apply.
- **Automatic sync**: a periodic job every 6 hours, plus a debounced job 30 seconds after local
  edits. Both need a network connection.

## Reminders on multiple devices

"Fire reminders on this device" (Settings → Reminders) is a per-device setting and isn't
synced. Turn it off on a tablet so only the phone rings. Snoozes are also per device.

## Wear OS

The phone publishes a `/today` DataItem with today's tasks, overdue one-offs, the next 7 days of
deadlines and an `updatedAt` timestamp. It does this after every change, at midnight, and when
the watch asks via a `/refresh` message.

When you complete a task on the watch, it writes a `/complete/<taskId>/<date>` DataItem. The Data
Layer queues it while the phone is disconnected. The phone applies it through the normal
repository path, so reminders, the widget and Drive sync all update. It then deletes the item and
republishes. The watch shows "Updated hh:mm" and "N waiting to sync" until the phone confirms.
