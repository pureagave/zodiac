package org.pureagave.zodiac.control.burnin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnInDeviceProfileTest {
    @Test
    fun amazon_lcd_skips_the_brightness_modulation_layer() {
        assertFalse(BurnInDeviceProfile.visualModulationSupported("Amazon"))
        assertFalse(BurnInDeviceProfile.visualModulationSupported("amazon")) // case-insensitive
    }

    @Test
    fun oled_devices_run_the_layer() {
        assertTrue(BurnInDeviceProfile.visualModulationSupported("Samsung"))
        assertTrue(BurnInDeviceProfile.visualModulationSupported("Google"))
    }
}
