package com.music.bitchord.data.personal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
data class PrivateRecord(val kind: String, val id: String, val data: JsonObject, val deleted: Boolean = false, val seq: Long = 0) {
    val key: String get() = "$kind\u0000$id"
}
@Serializable
data class PrivateChange(val mutation: String, val kind: String, val id: String, val data: JsonObject, val deleted: Boolean = false) {
    fun record() = PrivateRecord(kind, id, data, deleted)
}
@Serializable
data class PrivateSyncResponse(val cursor: Long, val records: List<PrivateRecord>, val ack: List<String>, val more: Boolean)
@Serializable
data class PrivateSyncRequest(val cursor: Long, val changes: List<PrivateChange>)
@Serializable
data class PrivateDocument(
    val cursor: Long = 0,
    val records: Map<String, PrivateRecord> = emptyMap(),
    val pending: List<PrivateChange> = emptyList(),
) {
    fun change(kind: String, id: String, data: JsonObject, deleted: Boolean = false): PrivateDocument {
        val record = PrivateRecord(kind, id, data, deleted)
        val previous = records[record.key]
        if (previous?.data == data && previous.deleted == deleted) return this
        return copy(records = records + (record.key to record), pending = pending + PrivateChange(UUID.randomUUID().toString(), kind, id, data, deleted))
    }

    fun merge(response: PrivateSyncResponse): PrivateDocument {
        val ack = response.ack.toSet()
        val remaining = pending.filterNot { it.mutation in ack }
        val merged = records.toMutableMap()
        response.records.forEach { merged[it.key] = it }
        // An edit made during the HTTP request is newer than that response.
        remaining.forEach { merged[it.record().key] = it.record() }
        return PrivateDocument(response.cursor, merged, remaining)
    }
}
