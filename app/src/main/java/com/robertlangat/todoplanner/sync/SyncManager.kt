package com.robertlangat.todoplanner.sync

import android.content.Context
import android.net.Uri
import com.robertlangat.todoplanner.core.sync.MergeResult
import com.robertlangat.todoplanner.core.sync.SnapshotCodec
import com.robertlangat.todoplanner.core.sync.SyncMerger
import com.robertlangat.todoplanner.core.sync.SyncRecord
import com.robertlangat.todoplanner.core.sync.SyncSnapshot
import com.robertlangat.todoplanner.data.SettingsRepository
import com.robertlangat.todoplanner.data.TaskRepository
import com.robertlangat.todoplanner.reminders.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/**
 * Google Drive sync + backup/restore and local file import/export.
 *
 * Flow (see docs/SYNC.md): download remote snapshot -> merge with local records using stable ids,
 * HLC versions and tombstones -> write a safety copy of local data -> apply the merged changes in
 * one database transaction -> upload if the remote copy is behind (with a revision check so two
 * devices uploading at once don't overwrite each other). Any failure before the transaction leaves
 * local data untouched; the transaction itself is all-or-nothing.
 */
class SyncManager(
    private val context: Context,
    private val repository: TaskRepository,
    private val settings: SettingsRepository,
    private val auth: DriveAuth,
    private val reminders: ReminderScheduler,
) {
    enum class Mode { SYNC, BACKUP, RESTORE }

    sealed interface Result {
        data class Success(val message: String) : Result
        data object NeedsSignIn : Result
        data class Failure(val message: String, val retryable: Boolean) : Result
    }

    private val mutex = Mutex()

    suspend fun run(mode: Mode, accessToken: String? = null): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val token = accessToken ?: auth.silentToken()
            if (token == null) {
                settings.setSyncResult(null, "Sign in to Google to sync")
                return@withContext Result.NeedsSignIn
            }
            val result = try {
                runWithToken(mode, DriveClient(token))
            } catch (e: DriveHttpException) {
                if (e.code == 401 || e.code == 403) Result.NeedsSignIn
                else Result.Failure("Google Drive error (${e.code}). Your tasks on this device are safe.", e.code >= 500 || e.code == 429)
            } catch (e: IOException) {
                Result.Failure("Couldn't reach Google Drive. Your tasks on this device are safe.", true)
            } catch (e: IllegalArgumentException) {
                Result.Failure(e.message ?: "The Drive copy couldn't be read.", false)
            }
            val now = System.currentTimeMillis()
            when (result) {
                is Result.Success -> settings.setSyncResult(now, result.message)
                is Result.Failure -> settings.setSyncResult(null, "Last attempt failed: ${result.message}")
                Result.NeedsSignIn -> settings.setSyncResult(null, "Google access expired - sign in again")
            }
            result
        }
    }

    private suspend fun runWithToken(mode: Mode, drive: DriveClient): Result {
        repeat(3) {
            val files = drive.findSyncFiles()
            val primary = files.firstOrNull()
            // Two devices may have created the file at the same moment: merge every copy.
            val remote = files.flatMap { SnapshotCodec.decode(drive.download(it.id)).records }
                .groupBy { it.key }.values.map { group -> group.reduce(SyncMerger::winner) }
            val local = repository.exportRecords()
            val merge = SyncMerger.merge(local, remote, System.currentTimeMillis())
            applyLocally(local, merge)

            if (mode != Mode.RESTORE && (merge.remoteNeedsUpdate || primary == null || files.size > 1)) {
                val content = SnapshotCodec.encode(
                    SyncSnapshot(SyncSnapshot.CURRENT_SCHEMA, settings.deviceId(), System.currentTimeMillis(), merge.merged),
                )
                if (primary == null) {
                    drive.create(content)
                } else {
                    val latest = drive.findSyncFiles().firstOrNull { it.id == primary.id }
                    if (latest?.headRevisionId != primary.headRevisionId) return@repeat // someone else uploaded; merge again
                    drive.update(primary.id, content)
                    files.drop(1).forEach { runCatching { drive.delete(it.id) } }
                }
            }
            val changed = merge.changedLocally.size
            return Result.Success(
                when (mode) {
                    Mode.RESTORE -> "Restored from Google Drive ($changed change${if (changed == 1) "" else "s"})"
                    Mode.BACKUP -> "Backed up to Google Drive"
                    Mode.SYNC -> "Synced" + if (changed > 0) " · $changed update${if (changed == 1) "" else "s"} received" else ""
                },
            )
        }
        return Result.Failure("Another device kept syncing at the same time. Try again in a moment.", true)
    }

    private suspend fun applyLocally(local: List<SyncRecord>, merge: MergeResult) {
        if (merge.changedLocally.isEmpty()) return
        writeSafetyCopy(local)
        val mergedKeys = merge.merged.map { it.key }.toSet()
        val purge = merge.changedLocally.map { it.key }.filterNot { it in mergedKeys }.toSet()
        repository.applyRecords(merge.changedLocally, purge)
        reminders.reconcile(rebuildAll = true)
    }

    /** Keeps the last few pre-merge copies in app storage so a bad restore can be undone by support. */
    private suspend fun writeSafetyCopy(local: List<SyncRecord>) = runCatching {
        val dir = File(context.filesDir, "safety").apply { mkdirs() }
        File(dir, "before-${System.currentTimeMillis()}.json").writeText(
            SnapshotCodec.encode(SyncSnapshot(SyncSnapshot.CURRENT_SCHEMA, settings.deviceId(), System.currentTimeMillis(), local)),
        )
        dir.listFiles()?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ local file backup (SAF)

    suspend fun exportTo(uri: Uri): String = withContext(Dispatchers.IO) {
        val records = repository.exportRecords()
        val json = SnapshotCodec.encode(SyncSnapshot(SyncSnapshot.CURRENT_SCHEMA, settings.deviceId(), System.currentTimeMillis(), records))
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Couldn't open the file for writing")
        "Exported ${records.count { it.type == "task" && !it.deleted }} tasks"
    }

    /** Imports a backup file by merging it (nothing local is lost; deletions in the file apply). */
    suspend fun importFrom(uri: Uri): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IOException("Couldn't open the file")
            val snapshot = SnapshotCodec.decode(text)
            val local = repository.exportRecords()
            val merge = SyncMerger.merge(local, snapshot.records, System.currentTimeMillis())
            applyLocally(local, merge)
            "Imported backup from ${DateFormat.getDateTimeInstance().format(Date(snapshot.generatedAt))} (${merge.changedLocally.size} changes)"
        }
    }
}
