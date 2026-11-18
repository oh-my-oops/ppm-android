package com.hienlh.ppm.state.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** `checkSessionTraceLimits` (storage-settings-view.test.ts). */
class SessionTraceLimitsTest {
    @Test
    fun `whole numbers at or above the floors pass, anything else says which field is wrong`() {
        assertEquals(SessionTraceLimitsCheck.Ok(7, 500), checkSessionTraceLimits(" 7 ", "500"))
        assertEquals(SessionTraceLimitsCheck.Problem("Keep for must be a whole number of at least 1 day."), checkSessionTraceLimits("0", "500"))
        assertEquals(SessionTraceLimitsCheck.Problem("Keep for must be a whole number of at least 1 day."), checkSessionTraceLimits("1.5", "500"))
        assertEquals(SessionTraceLimitsCheck.Problem("Max size must be a whole number of at least 50 MB."), checkSessionTraceLimits("3", "49"))
        assertEquals(SessionTraceLimitsCheck.Problem("Max size must be a whole number of at least 50 MB."), checkSessionTraceLimits("3", "1e3"))
    }
}
