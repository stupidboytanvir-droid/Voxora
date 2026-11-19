package com.voxora.player

import com.voxora.player.model.BatterySaverMode
import com.voxora.player.model.BatterySavingPolicy
import com.voxora.player.model.BatteryStatus
import com.voxora.player.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatterySavingPolicyTest {

    @Test
    fun `auto mode activates when battery is at or below threshold and not charging`() {
        val prefs = UserPreferences(
            batterySaverMode = BatterySaverMode.AUTO,
            batterySaverThresholdPercent = 20,
            batterySaverCapRefreshRate = true,
            batterySaverDisableAnimations = true
        )
        val status = BatteryStatus(
            batteryPercent = 15,
            isCharging = false,
            isSystemPowerSave = false
        )

        val result = BatterySavingPolicy.evaluate(prefs, status)
        assertTrue(result.isActive)
        assertTrue(result.capRefreshRate)
        assertTrue(result.disableAnimations)
        assertEquals("Low Battery (15%)", result.reasonLabel)
    }

    @Test
    fun `auto mode stays inactive when battery is low but device is charging`() {
        val prefs = UserPreferences(
            batterySaverMode = BatterySaverMode.AUTO,
            batterySaverThresholdPercent = 20
        )
        val status = BatteryStatus(
            batteryPercent = 10,
            isCharging = true,
            isSystemPowerSave = false
        )

        val result = BatterySavingPolicy.evaluate(prefs, status)
        assertFalse(result.isActive)
        assertFalse(result.capRefreshRate)
        assertFalse(result.disableAnimations)
        assertEquals("Charging (10%)", result.reasonLabel)
    }

    @Test
    fun `auto mode activates when system battery saver is enabled`() {
        val prefs = UserPreferences(
            batterySaverMode = BatterySaverMode.AUTO,
            batterySaverThresholdPercent = 15,
            batterySaverCapRefreshRate = true,
            batterySaverDisableAnimations = false
        )
        val status = BatteryStatus(
            batteryPercent = 68,
            isCharging = false,
            isSystemPowerSave = true
        )

        val result = BatterySavingPolicy.evaluate(prefs, status)
        assertTrue(result.isActive)
        assertTrue(result.capRefreshRate)
        assertFalse(result.disableAnimations)
        assertEquals("System Battery Saver", result.reasonLabel)
    }

    @Test
    fun `always on mode activates regardless of battery level or charging`() {
        val prefs = UserPreferences(
            batterySaverMode = BatterySaverMode.ALWAYS_ON,
            batterySaverCapRefreshRate = false,
            batterySaverDisableAnimations = true
        )
        val status = BatteryStatus(
            batteryPercent = 100,
            isCharging = true,
            isSystemPowerSave = false
        )

        val result = BatterySavingPolicy.evaluate(prefs, status)
        assertTrue(result.isActive)
        assertFalse(result.capRefreshRate)
        assertTrue(result.disableAnimations)
        assertEquals("Always On", result.reasonLabel)
    }

    @Test
    fun `off mode remains inactive even at critical battery and system saver`() {
        val prefs = UserPreferences(
            batterySaverMode = BatterySaverMode.OFF,
            batterySaverCapRefreshRate = true,
            batterySaverDisableAnimations = true
        )
        val status = BatteryStatus(
            batteryPercent = 4,
            isCharging = false,
            isSystemPowerSave = true
        )

        val result = BatterySavingPolicy.evaluate(prefs, status)
        assertFalse(result.isActive)
        assertFalse(result.capRefreshRate)
        assertFalse(result.disableAnimations)
        assertEquals("Off", result.reasonLabel)
    }
}
