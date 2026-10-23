package com.music.spotui.ui.components

import org.junit.Assert.*
import org.junit.Test

class AudioOutputOptionsTest {
    private val phone = AudioOutput(AudioOutputOptions.PHONE_ID, "Phone", true, false)
    private val bluetooth = AudioOutput("buds", "Earbuds", true, true)

    @Test fun phoneIsAvailableWhileBluetoothIsSelected() {
        val result = AudioOutputOptions.includePhone(listOf(bluetooth), phone)
        assertTrue(result.any { it.id == phone.id && !it.selected })
        assertEquals("buds", result.single { it.selected }.id)
    }

    @Test fun existingPhoneRouteIsNotDuplicatedOrOverwritten() {
        val actual = phone.copy(name = "This phone", selected = false)
        val result = AudioOutputOptions.includePhone(listOf(bluetooth, actual, actual), phone)
        assertEquals(2, result.size)
        assertEquals(actual, result.single { it.id == phone.id })
    }

    @Test fun connectedBluetoothDoesNotOverrideSelectedPhone() {
        val result = AudioOutputOptions.includePhone(listOf(bluetooth.copy(selected = false)), phone)
        assertEquals(phone.id, result.single { it.selected }.id)
    }

    @Test fun missingModernRoutesRetainTheReportedLocalRoute() {
        assertEquals(listOf(phone), AudioOutputOptions.includePhone(emptyList(), phone))
    }
}
