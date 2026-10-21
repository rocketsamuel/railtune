package com.example.railtune.model

import java.util.UUID

data class Loco(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: Int, // DCC Adresse (1 .. 9999)
    val maxSpeedKmH: Int = 160,
    val category: String = "Elektro", // Elektro, Diesel, Dampf, Triebwagen
    val decoderManufacturer: String = "",
    val illustrationId: String = "",
    val note: String = "",
    val dccFunctions: DccFunctions = DccFunctions()
)

data class DccFunctions(
    val light: Int? = null,
    val sound: Int? = null,
    val hornHigh: Int? = null,
    val hornLow: Int? = null,
    val cabLight: Int? = null,
    val zg12Fst1: Int? = null,
    val zg12Fst2: Int? = null
)

data class CvResult(
    val cvNumber: Int,
    val cvValue: Int,
    val timestampMs: Long = System.currentTimeMillis(),
    val isSuccess: Boolean = true,
    val message: String = ""
)

enum class CvProgrammingMode {
    DIRECT_MODE, // Programmiergleis (Direct CV Mode)
    POM_MODE     // Hauptgleisprogrammieren (Program on Main)
}
