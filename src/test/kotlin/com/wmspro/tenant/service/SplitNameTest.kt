package com.wmspro.tenant.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [splitName]. Pure function — no Spring context.
 *
 * FreighAi's user payload carries a single `name`; the mobile app's Profile and
 * Home screens read `firstName`/`lastName` separately. Getting this wrong is
 * user-visible on the handheld's greeting, so the single-word case (the common
 * one for this tenant — "Gautam", "Cibin", "Nipin") is covered explicitly.
 */
class SplitNameTest {

    @Test
    fun `splits a two-part name on the first space`() {
        assertEquals("Gautam" to "Aggarwal", splitName("Gautam Aggarwal"))
    }

    @Test
    fun `a single-word name yields an empty last name`() {
        assertEquals("Gautam" to "", splitName("Gautam"))
    }

    @Test
    fun `everything after the first space becomes the last name`() {
        assertEquals("Ahmed" to "Seddiqi Al Sons", splitName("Ahmed Seddiqi Al Sons"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Gautam" to "Aggarwal", splitName("  Gautam Aggarwal  "))
    }

    @Test
    fun `repeated inner spaces do not leak into the last name`() {
        assertEquals("Gautam" to "Aggarwal", splitName("Gautam   Aggarwal"))
    }

    @Test
    fun `an empty name yields two empty strings rather than throwing`() {
        assertEquals("" to "", splitName(""))
        assertEquals("" to "", splitName("   "))
    }
}
