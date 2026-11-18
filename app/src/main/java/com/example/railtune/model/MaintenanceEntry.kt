package com.example.railtune.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MaintenanceEntry(
    val id: String = UUID.randomUUID().toString(),
    val date: String = "",
    val motorCleaned: Boolean = false,
    val bogiesCleaned: Boolean = false,
    val comment: String = ""
)

object MaintenanceHistory {
    fun encode(entries: List<MaintenanceEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("id", entry.id)
                put("date", entry.date)
                put("motorCleaned", entry.motorCleaned)
                put("bogiesCleaned", entry.bogiesCleaned)
                put("comment", entry.comment)
            })
        }
    }.toString()

    fun decode(json: String): List<MaintenanceEntry> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            MaintenanceEntry(
                id = item.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                date = item.optString("date"),
                motorCleaned = item.optBoolean("motorCleaned"),
                bogiesCleaned = item.optBoolean("bogiesCleaned"),
                comment = item.optString("comment")
            )
        }
    }
}
