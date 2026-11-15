package com.robertlangat.todoplanner.core

import com.robertlangat.todoplanner.core.sync.HybridLogicalClock
import com.robertlangat.todoplanner.core.sync.SnapshotCodec
import com.robertlangat.todoplanner.core.sync.SyncMerger
import com.robertlangat.todoplanner.core.sync.SyncRecord
import com.robertlangat.todoplanner.core.sync.SyncSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergerTest {
    private val now = 1_800_000_000_000L
    private fun v(millis: Long, counter: Int = 0) = (millis shl 16) or counter.toLong()
    private fun rec(id: String, version: Long, device: String, deleted: Boolean = false, title: String = id, seen: Long = 0) =
        SyncRecord("task", id, version, device, deleted, mapOf("title" to title), seen)

    @Test fun twoDevicesWithDifferentTasksUnionWithoutDuplicates() {
        val phone = listOf(rec("a", v(now), "phone"), rec("shared", v(now), "phone"))
        val tablet = listOf(rec("b", v(now), "tablet"), rec("shared", v(now), "phone"))
        val r = SyncMerger.merge(phone, tablet, now)
        assertEquals(setOf("a", "b", "shared"), r.merged.map { it.id }.toSet())
        assertEquals(3, r.merged.size)
        assertEquals(listOf("b"), r.changedLocally.map { it.id })
        assertTrue(r.remoteNeedsUpdate)
    }

    @Test fun syncedDeletionStaysDeletedEvenIfOfflineDeviceEditedLater() {
        val tomb = rec("a", v(now - 1000), "phone", deleted = true)
        // Tablet was offline, never saw the delete, and edited the title afterwards.
        val offlineEdit = rec("a", v(now), "tablet", title = "renamed")
        val r = SyncMerger.merge(listOf(offlineEdit), listOf(tomb), now)
        assertTrue(r.merged.single().deleted)
        assertTrue(r.changedLocally.single().deleted)
        // and in the other direction
        assertTrue(SyncMerger.merge(listOf(tomb), listOf(offlineEdit), now).merged.single().deleted)
    }

    @Test fun explicitUndoAfterSeeingDeleteRestores() {
        val tomb = rec("a", v(now - 1000), "phone", deleted = true)
        val restored = rec("a", v(now), "phone", seen = tomb.version)
        assertFalse(SyncMerger.merge(listOf(restored), listOf(tomb), now).merged.single().deleted)
    }

    @Test fun concurrentEditsLastWriterWinsWithDeterministicTieBreak() {
        val a = rec("x", v(now), "phone", title = "phone title")
        val b = rec("x", v(now, 1), "tablet", title = "tablet title")
        assertEquals("tablet title", SyncMerger.merge(listOf(a), listOf(b), now).merged.single().data["title"])
        val tieA = rec("x", v(now), "aaa", title = "A")
        val tieB = rec("x", v(now), "bbb", title = "B")
        assertEquals("B", SyncMerger.merge(listOf(tieA), listOf(tieB), now).merged.single().data["title"])
        assertEquals("B", SyncMerger.merge(listOf(tieB), listOf(tieA), now).merged.single().data["title"])
    }

    @Test fun reSyncingIsIdempotent() {
        val local = listOf(rec("a", v(now), "phone"), rec("b", v(now), "phone", deleted = true))
        val first = SyncMerger.merge(local, emptyList(), now)
        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(SyncSnapshot(1, "phone", now, first.merged))).records
        val second = SyncMerger.merge(local, decoded, now)
        assertTrue(second.changedLocally.isEmpty())
        assertFalse(second.remoteNeedsUpdate)
    }

    @Test fun oldTombstonesArePurged() {
        val oldTomb = rec("a", v(now - SyncMerger.DEFAULT_TOMBSTONE_RETENTION_MILLIS - 1), "phone", deleted = true)
        val r = SyncMerger.merge(listOf(oldTomb), listOf(oldTomb), now)
        assertTrue(r.merged.isEmpty())
    }

    @Test fun hlcIsMonotonicWhenClockGoesBackwards() {
        var wall = 1000L
        val clock = HybridLogicalClock(wallClock = { wall })
        val a = clock.now()
        wall = 500L
        val b = clock.now()
        assertTrue(b > a)
        clock.observe(v(5000))
        assertTrue(clock.now() > v(5000))
    }

    @Test fun codecRejectsNewerSchemaAndGarbage() {
        val newer = """{"schemaVersion": 99, "records": []}"""
        assertTrue(runCatching { SnapshotCodec.decode(newer) }.isFailure)
        assertTrue(runCatching { SnapshotCodec.decode("not json") }.isFailure)
    }
}
