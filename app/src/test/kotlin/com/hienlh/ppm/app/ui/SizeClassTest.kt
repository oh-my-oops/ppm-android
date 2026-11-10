package com.hienlh.ppm.app.ui

import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.LocalPrefs
import com.hienlh.ppm.app.redesign
import com.hienlh.ppm.client.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeClassTest {
    @Test
    fun `599 dp is Phone and 600 dp is Tablet`() {
        assertEquals(SizeClass.Phone, SizeClass.of(599))
        assertEquals(SizeClass.Tablet, SizeClass.of(600))
        assertEquals(SizeClass.Phone, SizeClass.of(599.dp))
        assertEquals(SizeClass.Tablet, SizeClass.of(600.dp))
        assertEquals(SizeClass.Phone, SizeClass.of(599.9f))
        assertEquals(SizeClass.Tablet, SizeClass.of(600.0f))
    }

    @Test
    fun `common phone and tablet widths classify as expected`() {
        for (w in listOf(320, 360, 390, 412, 480, 599)) {
            val sc = SizeClass.of(w)
            assertEquals("width=$w", SizeClass.Phone, sc)
            assertTrue(sc.isPhone)
            assertFalse(sc.isTablet)
        }
        for (w in listOf(600, 720, 768, 840, 1024, 1280)) {
            val sc = SizeClass.of(w)
            assertEquals("width=$w", SizeClass.Tablet, sc)
            assertTrue(sc.isTablet)
            assertFalse(sc.isPhone)
        }
    }

    @Test
    fun `compact height under 480 dp stays on Phone even when width is 600 dp or more`() {
        // Phone rotated to landscape (915 x 412) stays on Phone shell
        assertEquals(SizeClass.Phone, SizeClass.of(915, 412))
        assertEquals(SizeClass.Phone, SizeClass.of(600, 479))
        assertEquals(SizeClass.Tablet, SizeClass.of(600, 480))
        assertEquals(SizeClass.Tablet, SizeClass.of(1280, 800))
        assertEquals(SizeClass.Phone, SizeClass.of(915.dp, 412.dp))
        assertEquals(SizeClass.Tablet, SizeClass.of(1280.dp, 800.dp))
    }

    @Test
    fun `expanded tablet breakpoint is 840 dp`() {
        assertFalse(SizeClass.isExpandedTablet(839))
        assertTrue(SizeClass.isExpandedTablet(840))
        assertFalse(SizeClass.isExpandedTablet(839.dp))
        assertTrue(SizeClass.isExpandedTablet(840.dp))
    }

    @Test
    fun `TabScrollStore preserves scroll positions per tab across size class transitions`() {
        val store = TabScrollStore()
        assertEquals(0, store.scrollOffset("tab-chat-1"))
        assertEquals(0 to 0, store.lazyPosition("tab-chat-1"))

        store.saveScrollOffset("tab-files-1", 420)
        store.saveLazyPosition("tab-chat-1", 14, 68)

        assertEquals(420, store.scrollOffset("tab-files-1"))
        assertEquals(14 to 68, store.lazyPosition("tab-chat-1"))
    }

    @Test
    fun `LocalPrefs redesign defaults to false and persists to KeyValueStore`() {
        val mem = object : KeyValueStore {
            val map = mutableMapOf<String, String>()
            override fun get(key: String): String? = map[key]
            override fun put(key: String, value: String?) {
                if (value == null) map.remove(key) else map[key] = value
            }
        }
        val prefs = LocalPrefs.bind(mem)
        assertFalse(prefs.redesign)
        assertFalse(mem.redesign)

        prefs.redesign = true
        assertTrue(prefs.redesign)
        assertTrue(mem.redesign)
        assertEquals("true", mem.get(LocalPrefs.REDESIGN_KEY))

        prefs.redesign = false
        assertFalse(prefs.redesign)
        assertFalse(mem.redesign)
        assertEquals("false", mem.get(LocalPrefs.REDESIGN_KEY))
    }
}
