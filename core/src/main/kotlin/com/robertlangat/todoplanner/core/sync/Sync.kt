package com.robertlangat.todoplanner.core.sync

import com.robertlangat.todoplanner.core.json.Json
import com.robertlangat.todoplanner.core.json.asList
import com.robertlangat.todoplanner.core.json.asObject
import com.robertlangat.todoplanner.core.json.bool
import com.robertlangat.todoplanner.core.json.int
import com.robertlangat.todoplanner.core.json.long
import com.robertlangat.todoplanner.core.json.str

/**
 * Hybrid logical clock. Versions are `(physicalMillis << 16) | counter`, so they sort by time but
 * still increase monotonically if the wall clock goes backwards or another device is ahead.
 */
class HybridLogicalClock(private var last: Long = 0L, private val wallClock: () -> Long = System::currentTimeMillis) {
    @Synchronized
    fun now(): Long {
        val physical = wallClock() shl 16
        last = if (physical > last) physical else last + 1
        return last
    }

    /** Call with every version seen from another device so local edits always sort after it. */
    @Synchronized
    fun observe(remote: Long) {
        if (remote > last) last = remote
    }

    val current: Long @Synchronized get() = last

    companion object {
        fun millisOf(version: Long): Long = version ushr 16
    }
}

/**
 * One syncable row (task, list, subtask, occurrence completion, ...).
 * [seenDeleteVersion] records the newest tombstone version the editing device knew about when it
 * made this live edit; see [SyncMerger] for why.
 */
data class SyncRecord(
    val type: String,
    val id: String,
    val version: Long,
    val deviceId: String,
    val deleted: Boolean,
    val data: Map<String, Any?>,
    val seenDeleteVersion: Long = 0L,
) {
    val key: String get() = "$type:$id"
}

data class SyncSnapshot(
    val schemaVersion: Int,
    val generatedBy: String,
    val generatedAt: Long,
    val records: List<SyncRecord>,
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}

data class MergeResult(
    val merged: List<SyncRecord>,
    /** Records whose winning version differs from what the local device had. Apply these locally. */
    val changedLocally: List<SyncRecord>,
    /** True if the remote copy is missing something we have, so it must be uploaded. */
    val remoteNeedsUpdate: Boolean,
    val conflictsResolved: Int,
)

/**
 * Merge rules (also in docs/SYNC.md):
 * 1. Records are matched by stable id. Nothing is ever matched by title, so no duplicates appear.
 * 2. Live vs live: the higher version wins (last writer wins); ties break on deviceId.
 * 3. Tombstone vs live: deletion wins, unless the live edit was made *after the editor saw that
 *    deletion* (an explicit undo/restore records the tombstone's version in seenDeleteVersion).
 *    An offline device that edits a task it hasn't heard was deleted therefore cannot resurrect it.
 * 4. Tombstone vs tombstone: the higher version wins.
 * 5. Tombstones are kept for [tombstoneRetentionMillis] so long-offline devices still learn of
 *    deletions; older ones are purged.
 * Occurrence completions are separate records, so completing on one device and renaming on
 * another both survive.
 */
object SyncMerger {
    const val DEFAULT_TOMBSTONE_RETENTION_MILLIS = 180L * 24 * 60 * 60 * 1000

    fun winner(a: SyncRecord, b: SyncRecord): SyncRecord {
        if (a.deleted != b.deleted) {
            val tomb = if (a.deleted) a else b
            val live = if (a.deleted) b else a
            return if (live.version > tomb.version && live.seenDeleteVersion >= tomb.version) live else tomb
        }
        return when {
            a.version != b.version -> if (a.version > b.version) a else b
            else -> if (a.deviceId >= b.deviceId) a else b
        }
    }

    /** Compares revision metadata only (JSON number types may differ after a round trip). */
    fun sameRevision(a: SyncRecord, b: SyncRecord): Boolean =
        a.version == b.version && a.deviceId == b.deviceId && a.deleted == b.deleted &&
            a.seenDeleteVersion == b.seenDeleteVersion

    fun merge(
        local: List<SyncRecord>,
        remote: List<SyncRecord>,
        nowMillis: Long,
        tombstoneRetentionMillis: Long = DEFAULT_TOMBSTONE_RETENTION_MILLIS,
    ): MergeResult {
        val localByKey = local.associateBy { it.key }
        val remoteByKey = remote.associateBy { it.key }
        val keys = LinkedHashSet<String>().apply { addAll(localByKey.keys); addAll(remoteByKey.keys) }
        val merged = ArrayList<SyncRecord>(keys.size)
        val changedLocally = ArrayList<SyncRecord>()
        var remoteNeedsUpdate = false
        var conflicts = 0
        val purgeBefore = nowMillis - tombstoneRetentionMillis

        for (key in keys) {
            val l = localByKey[key]
            val r = remoteByKey[key]
            val w = when {
                l == null -> r!!
                r == null -> l
                else -> {
                    if (!sameRevision(l, r)) conflicts++
                    winner(l, r)
                }
            }
            if (w.deleted && HybridLogicalClock.millisOf(w.version) < purgeBefore) {
                // Old tombstone: drop from the merged set; also make sure the row is gone locally.
                if (l != null) changedLocally += w
                if (r != null) remoteNeedsUpdate = true
                continue
            }
            merged += w
            if (l == null || !sameRevision(l, w)) changedLocally += w
            if (r == null || !sameRevision(r, w)) remoteNeedsUpdate = true
        }
        return MergeResult(merged, changedLocally, remoteNeedsUpdate, conflicts)
    }
}

object SnapshotCodec {
    fun encode(snapshot: SyncSnapshot): String = Json.stringify(
        mapOf(
            "schemaVersion" to snapshot.schemaVersion,
            "generatedBy" to snapshot.generatedBy,
            "generatedAt" to snapshot.generatedAt,
            "records" to snapshot.records.map {
                mapOf(
                    "type" to it.type, "id" to it.id, "version" to it.version, "deviceId" to it.deviceId,
                    "deleted" to it.deleted, "seenDeleteVersion" to it.seenDeleteVersion, "data" to it.data,
                )
            },
        ),
    )

    /** Throws [IllegalArgumentException] for unreadable files or snapshots from a newer app. */
    fun decode(text: String): SyncSnapshot {
        val root = runCatching { Json.parse(text).asObject() }.getOrNull()
            ?: throw IllegalArgumentException("Not a Things to Do Next Planner backup")
        val schema = root.int("schemaVersion") ?: throw IllegalArgumentException("Missing schemaVersion")
        require(schema <= SyncSnapshot.CURRENT_SCHEMA) {
            "Backup was made by a newer version of the app (schema $schema). Please update."
        }
        val records = (root["records"].asList() ?: emptyList()).mapNotNull { raw ->
            val o = raw.asObject() ?: return@mapNotNull null
            SyncRecord(
                type = o.str("type") ?: return@mapNotNull null,
                id = o.str("id") ?: return@mapNotNull null,
                version = o.long("version") ?: 0L,
                deviceId = o.str("deviceId") ?: "",
                deleted = o.bool("deleted") ?: false,
                data = o["data"].asObject() ?: emptyMap(),
                seenDeleteVersion = o.long("seenDeleteVersion") ?: 0L,
            )
        }
        return SyncSnapshot(schema, root.str("generatedBy") ?: "", root.long("generatedAt") ?: 0L, records)
    }
}
