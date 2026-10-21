package com.example.railtune.model

import org.json.JSONArray
import org.json.JSONObject

/** Portable JSON format for moving the locomotive list between RailTune installations. */
object LocoTransfer {
    private const val FORMAT = "railtune-locos"
    private const val VERSION = 1

    fun encode(locos: List<Loco>): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("locos", JSONArray().apply {
            locos.forEach { loco ->
                put(JSONObject().apply {
                    put("id", loco.id)
                    put("name", loco.name)
                    put("address", loco.address)
                    put("maxSpeedKmH", loco.maxSpeedKmH)
                    put("category", loco.category)
                    put("decoderManufacturer", loco.decoderManufacturer)
                    put("illustrationId", loco.illustrationId)
                    put("note", loco.note)
                    put("dccFunctions", JSONObject().apply {
                        putNullable("light", loco.dccFunctions.light)
                        putNullable("sound", loco.dccFunctions.sound)
                        putNullable("hornHigh", loco.dccFunctions.hornHigh)
                        putNullable("hornLow", loco.dccFunctions.hornLow)
                        putNullable("cabLight", loco.dccFunctions.cabLight)
                        putNullable("zg12Fst1", loco.dccFunctions.zg12Fst1)
                        putNullable("zg12Fst2", loco.dccFunctions.zg12Fst2)
                    })
                })
            }
        })
    }.toString(2)

    fun decode(json: String): List<Loco> {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "Das ist keine RailTune-Fahrzeugdatei." }
        require(root.optInt("version", -1) == VERSION) { "Diese Dateiversion wird nicht unterstützt." }
        val array = root.optJSONArray("locos") ?: error("Die Datei enthält keine Fahrzeugliste.")
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val functions = item.optJSONObject("dccFunctions") ?: JSONObject()
            val address = item.getInt("address")
            require(address in 1..9999) { "Ungültige DCC-Adresse: $address" }
            Loco(
                id = item.optString("id").takeIf(String::isNotBlank) ?: java.util.UUID.randomUUID().toString(),
                name = item.optString("name", "Lok $address"),
                address = address,
                maxSpeedKmH = item.optInt("maxSpeedKmH", 160).coerceAtLeast(1),
                category = item.optString("category", "Elektro"),
                decoderManufacturer = item.optString("decoderManufacturer"),
                illustrationId = item.optString("illustrationId"),
                note = item.optString("note"),
                dccFunctions = DccFunctions(
                    light = functions.intOrNull("light"), sound = functions.intOrNull("sound"),
                    hornHigh = functions.intOrNull("hornHigh"), hornLow = functions.intOrNull("hornLow"),
                    cabLight = functions.intOrNull("cabLight"), zg12Fst1 = functions.intOrNull("zg12Fst1"),
                    zg12Fst2 = functions.intOrNull("zg12Fst2")
                )
            )
        }
    }

    private fun JSONObject.putNullable(key: String, value: Int?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.intOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key).takeIf { it >= 0 }
}
