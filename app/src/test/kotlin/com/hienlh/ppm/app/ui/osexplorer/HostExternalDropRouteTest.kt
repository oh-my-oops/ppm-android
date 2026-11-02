package com.hienlh.ppm.app.ui.osexplorer

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.DragDriver
import com.hienlh.ppm.app.ui.DropRouter
import com.hienlh.ppm.app.ui.LocalDropRouter
import com.hienlh.ppm.app.ui.LocalWindowContentVisible
import com.hienlh.ppm.app.ui.tablet.WorkingAreaLayers
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.files.FileMutations
import com.hienlh.ppm.state.osexplorer.HostFileActions
import com.hienlh.ppm.state.osexplorer.HostUploads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A window's place for files dragged in from another app ([hostExternalDrop]) is a zone of the working area's drop router, so that what is on
 * top of it decides what a drop on it is, and it is the window's own: it takes a drag that began in another app and none that began in this one,
 * and nothing at all while the window that holds it is minimized.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w700dp-h600dp")
class HostExternalDropRouteTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val session = explorerSessionOn(
        HostDirectory(emptyList(), "/home/me", "/home", listOf(HostPlace("me", "/home/me")), "/", truncated = false),
    )
    private var view: View? = null
    private var router: DropRouter? = null
    private var visible by mutableStateOf(true)
    private val drag = DragDriver(compose, local = null) { view!! }

    @Before
    fun setUp() {
        server.start()
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        val api = ApiClient(server.url("/"), "secret")
        model.api = api
        val commands = HostExplorerCommands({}, api, session, HostFileActions(api, FileMutations(api), "/", null), null, scope)
        val queue = HostUploads(scope, api, "/")
        compose.setContent {
            view = LocalView.current
            WorkingAreaLayers(
                Modifier.size(300.dp),
                panels = {
                    router = LocalDropRouter.current
                    CompositionLocalProvider(LocalWindowContentVisible provides visible) {
                        Box(Modifier.size(100.dp).then(hostExternalDrop(model, queue, commands, "/home/me")))
                    }
                },
                windows = {},
            )
        }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        session.close()
    }

    /** The zone that has the middle of the box, of those that took the drag. */
    private fun zoneAt50(): Any? {
        var zone: Any? = null
        compose.runOnUiThread { zone = router!!.zoneAt(Offset(50f, 50f)) }
        return zone
    }

    @Test
    fun `files dragged in from another app are taken by it`() {
        drag.begin()

        assertNotNull(zoneAt50())
        drag.end()
    }

    @Test
    fun `a drag that began in this app is not`() {
        drag.local = "a window's entries"
        drag.begin()

        assertNull(zoneAt50())
        drag.end()
    }

    @Test
    fun `nothing is taken while the window is minimized`() {
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        drag.begin()

        assertNull(zoneAt50())
        drag.end()
    }
}
