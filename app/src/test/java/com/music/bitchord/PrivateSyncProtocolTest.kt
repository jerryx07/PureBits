package com.music.bitchord

import com.music.bitchord.data.personal.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PrivateSyncProtocolTest {
    private val song = buildJsonObject { put("song", buildJsonObject { put("videoId", "song"); put("title", "A") }) }

    @Test fun `in-flight acknowledgement cannot erase a newer local unlike`() {
        val liked = PrivateDocument().change("like", "song", song)
        val unliked = liked.change("like", "song", song, true)
        val merged = unliked.merge(PrivateSyncResponse(1, listOf(PrivateRecord("like", "song", song, seq = 1)), listOf(liked.pending.single().mutation), false))
        assertTrue(merged.records.getValue("like\u0000song").deleted)
        assertEquals(1, merged.pending.size)
        assertEquals(unliked.pending.last().mutation, merged.pending.single().mutation)
    }
    @Test fun `remote additions retain unrelated offline changes`() {
        val local = PrivateDocument().change("playlist", "a", buildJsonObject { put("title", "Phone") })
        val remote = PrivateRecord("playlist", "b", buildJsonObject { put("title", "PC") }, seq = 4)
        val merged = local.merge(PrivateSyncResponse(4, listOf(remote), emptyList(), false))
        assertEquals(setOf("playlist\u0000a", "playlist\u0000b"), merged.records.keys)
        assertEquals(1, merged.pending.size)
    }
    @Test fun `tombstones survive serialization and do not requeue unchanged snapshots`() {
        val local = PrivateDocument().change("like", "song", song, true)
        val codec = Json { encodeDefaults = true }
        val restored = codec.decodeFromString<PrivateDocument>(codec.encodeToString(PrivateDocument.serializer(), local))
        assertTrue(restored.records.getValue("like\u0000song").deleted)
        assertEquals(restored, restored.change("like", "song", song, true))
    }
}
