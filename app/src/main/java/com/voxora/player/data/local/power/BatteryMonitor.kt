package com.voxora.player.data.local.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.voxora.player.model.BatteryStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class BatteryMonitor(private val context: Context) {

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun currentBatteryStatus(): BatteryStatus {
        val stickyIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        return extractBatteryStatus(stickyIntent)
    }

    val batteryStatusFlow: Flow<BatteryStatus> = callbackFlow {
        trySend(currentBatteryStatus())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val batteryIntent = if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    intent
                } else {
                    appContext.registerReceiver(
                        null,
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    )
                }
                trySend(extractBatteryStatus(batteryIntent))
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        appContext.registerReceiver(receiver, filter)

        awaitClose {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }.distinctUntilChanged()

    private fun extractBatteryStatus(intent: Intent?): BatteryStatus {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
        } else {
            100
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val systemPowerSave = runCatching { powerManager.isPowerSaveMode }.getOrDefault(false)

        return BatteryStatus(
            batteryPercent = percent,
            isCharging = isCharging,
            isSystemPowerSave = systemPowerSave
        )
    }
}
