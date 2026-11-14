package com.bernkastel5.haqomu.service

import com.bernkastel5.haqomu.lookup.SelectionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф2/Ф3: правила сервиса доступности без Android — режимы, условие показа кнопки,
 * отсев служебных полей, сопоставление id сервиса и память о выделении.
 * Память проверяется с явным временем: срок жизни ограничен, текст не «залипает».
 */
class AccessibilityRulesTest {

    // ---------------------------------------------------------------- режимы

    @Test
    fun modeIsOffUnlessExplicitlyStored() {
        assertEquals(AccessibilityMode.Off, AccessibilityMode.fromStored(null))
        assertEquals(AccessibilityMode.Off, AccessibilityMode.fromStored(""))
        assertEquals(AccessibilityMode.Off, AccessibilityMode.fromStored("on"))
        assertEquals(AccessibilityMode.Floating, AccessibilityMode.fromStored("floating"))
        assertEquals(AccessibilityMode.Context, AccessibilityMode.fromStored("context"))
    }

    @Test
    fun modeRoundTripsThroughStoredValue() {
        for (mode in AccessibilityMode.values()) {
            assertEquals(mode, AccessibilityMode.fromStored(AccessibilityMode.storedValue(mode)))
        }
        assertFalse(AccessibilityMode.Off.isEnabled)
        assertTrue(AccessibilityMode.Floating.isEnabled)
        assertTrue(AccessibilityMode.Context.isEnabled)
    }

    // ------------------------------------------------------------ плавающая кнопка

    @Test
    fun buttonOnlyInFloatingMode() {
        assertTrue(FloatingButtonRules.shouldShow(AccessibilityMode.Floating, 4))
        assertFalse(FloatingButtonRules.shouldShow(AccessibilityMode.Context, 4))
        assertFalse(FloatingButtonRules.shouldShow(AccessibilityMode.Off, 4))
    }

    @Test
    fun tapOnASingleLetterDoesNotShowTheButton() {
        assertFalse(FloatingButtonRules.shouldShow(AccessibilityMode.Floating, 1))
        assertTrue(
            FloatingButtonRules.shouldShow(
                AccessibilityMode.Floating,
                FloatingButtonRules.MIN_SELECTION_CHARS,
            )
        )
    }

    @Test
    fun passwordsAndBlankTextAreNotRead() {
        assertFalse(FloatingButtonRules.isUsableText("hunter2", isPassword = true))
        assertFalse(FloatingButtonRules.isUsableText("   ", isPassword = false))
        assertFalse(FloatingButtonRules.isUsableText(null, isPassword = false))
        assertTrue(FloatingButtonRules.isUsableText("ذهب الطفل", isPassword = false))
    }

    // ------------------------------------------------------------ системный id

    @Test
    fun serviceIdMatchesBothClassForms() {
        val pkg = "com.bernkastel5.haqomu"
        val cls = "$pkg.service.HaqomuAccessibilityService"
        assertTrue(AccessibilityServiceId.matches("$pkg/.service.HaqomuAccessibilityService", pkg, cls))
        assertTrue(AccessibilityServiceId.matches("$pkg/$cls", pkg, cls))
    }

    @Test
    fun foreignOrBrokenIdsDoNotMatch() {
        val pkg = "com.bernkastel5.haqomu"
        val cls = "$pkg.service.HaqomuAccessibilityService"
        assertFalse(AccessibilityServiceId.matches(null, pkg, cls))
        assertFalse(AccessibilityServiceId.matches("", pkg, cls))
        assertFalse(AccessibilityServiceId.matches("без класса", pkg, cls))
        assertFalse(AccessibilityServiceId.matches("$pkg/", pkg, cls))
        assertFalse(AccessibilityServiceId.matches("com.other/.HaqomuAccessibilityService", pkg, cls))
        assertFalse(AccessibilityServiceId.matches("$pkg/.service.Other", pkg, cls))
    }

    // ------------------------------------------------------------ память о выделении

    @Test
    fun recentSelectionIsReturnedForTheSameWord() {
        SelectionHub.reset()
        SelectionHub.setEnabled(true)
        SelectionHub.remember(SelectionInfo("ذهب", "ذهب الطفل إلى البيت."), now = 1_000L)
        val info = SelectionHub.recentFor("ذهب", now = 2_000L)
        assertEquals("ذهب الطفل إلى البيت.", info?.sentence)
    }

    @Test
    fun selectionExpiresAndDoesNotStick() {
        SelectionHub.reset()
        SelectionHub.setEnabled(true)
        SelectionHub.remember(SelectionInfo("ذهب", "ذهب الطفل."), now = 1_000L)
        assertNull(SelectionHub.recentFor("ذهب", now = 1_000L + SelectionHub.TTL_MS + 1))
    }

    @Test
    fun otherWordGetsNoContext() {
        SelectionHub.reset()
        SelectionHub.setEnabled(true)
        SelectionHub.remember(SelectionInfo("ذهب", "ذهب الطفل."), now = 1_000L)
        assertNull(SelectionHub.recentFor("بيت", now = 1_500L))
    }

    @Test
    fun disabledServiceProvidesNoContext() {
        SelectionHub.reset()
        SelectionHub.remember(SelectionInfo("ذهب", "ذهب الطفل."), now = 1_000L)
        assertNull(SelectionHub.recentFor("ذهب", now = 1_100L))

        SelectionHub.setEnabled(true)
        SelectionHub.remember(SelectionInfo("ذهب", "ذهب الطفل."), now = 2_000L)
        SelectionHub.setEnabled(false)
        assertNull(SelectionHub.recentFor("ذهب", now = 2_100L))
    }
}
