package com.example.railtune

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import com.example.railtune.model.ConnectionState
import com.example.railtune.model.LiveDataPacket
import com.example.railtune.model.Loco
import com.example.railtune.ui.screens.SpeedMeasurementScreen
import com.example.railtune.ui.theme.RailTuneTheme
import java.util.Locale

/** Device smoke test without a BLE peripheral or additional test dependencies. */
class MeasurementSmokeTestRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val originalLocale = Locale.getDefault()
        var activity: MainActivity? = null
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val testActivity = activity
            val cases = listOf(
                Triple(Locale.GERMANY, "0.00,0.0,4100,90,0.000", "Akku 90 % · 4,10 V"),
                Triple(Locale.GERMANY, "0.00,0.0,3300,0,0.000", "Akku 0 % · 3,30 V"),
                Triple(Locale.US, "0.00,0.0,4200,100,0.000", "Akku 100 % · 4.20 V")
            )
            for ((locale, packet, expected) in cases) {
                Locale.setDefault(locale)
                runOnMainSync {
                    testActivity.setContent {
                        RailTuneTheme(darkTheme = true, dynamicColor = false) {
                            SpeedMeasurementScreen(
                                selectedLocoId = "test",
                                onSelectedLocoChange = {},
                                savedLocos = listOf(Loco(id = "test", name = "Testlok", address = 3)),
                                railComReadings = emptyMap(), locoStates = emptyMap(),
                                onSetLocoSpeed = { _, _, _ -> }, onGetLocoInfo = {},
                                connectionState = ConnectionState.Connected("Test-Messwagen", "00:00:00:00:00:00"),
                                isScanning = false, lastConnectedName = "Test-Messwagen",
                                lastConnectedAddress = "00:00:00:00:00:00", isAutoConnectEnabled = false,
                                discoveredDevices = emptyList(),
                                liveDataPacket = LiveDataPacket(rawBytes = packet.toByteArray(), textValue = packet),
                                dataHistory = emptyList(), connectionLog = emptyList(),
                                onClearConnectionLog = {}, onStartScan = {}, onStopScan = {},
                                onDisconnect = {}, onToggleAutoConnect = {}, onForgetDevice = {},
                                onConnectDevice = {}, onClearHistory = {}, onResetTripDistance = { true }
                            )
                        }
                    }
                }
                waitForIdleSync()
                uiAutomation.waitForIdle(200, 3_000)
                val root = uiAutomation.rootInActiveWindow ?: error("No measurement window")
                fun texts(node: android.view.accessibility.AccessibilityNodeInfo): List<String> =
                    listOfNotNull(node.text?.toString()) + (0 until node.childCount).flatMap {
                        node.getChild(it)?.let(::texts).orEmpty()
                    }
                val renderedTexts = texts(root)
                check(expected in renderedTexts) {
                    "Missing rendered battery label: $expected; window=${root.packageName}; texts=$renderedTexts"
                }
            }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PASS: measurement screen rendered battery readings in all 3 cases.\n")
            })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", error.stackTraceToString()) })
        } finally {
            Locale.setDefault(originalLocale)
            activity?.let { runOnMainSync { it.finish() } }
        }
    }
}
