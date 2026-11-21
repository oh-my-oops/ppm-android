package com.hienlh.ppm.app

import android.app.Application
import com.hienlh.ppm.app.recentphoto.RecentPhotos
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.SyntaxHighlighter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Retains the primary model and each document task's model across Activity recreation.
 * Activities own visibility; finishing a document task releases only that task's model.
 */
class PpmApplication : Application() {
    private var model: AppModel? = null
    private val windowModels = mutableMapOf<String, AppModel>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun windowModel(id: String, launch: DebugLaunch?, serverId: String?, project: String?): AppModel =
        windowModels.getOrPut(id) {
            AppModel(this, launch, id, project).also { next ->
                if (PrefsStore(this).get("window.$id.selectedServer") == null) {
                    serverId?.let { name -> next.store.servers.value.firstOrNull { it.id == name }?.let(next.store::select) }
                }
            }
        }

    /** A picture just taken, offered to the chat on screen: one for every window, so none is offered twice. */
    val recentPhotos: RecentPhotos by lazy { RecentPhotos.forDevice(this) }

    fun releaseWindow(id: String) { windowModels.remove(id)?.disposeWindow(cleanupScope) }

    /** The model, made on first use with the [launch] the first activity was started with. */
    fun model(launch: DebugLaunch?): AppModel = model ?: AppModel(this, launch).also { model = it }

    override fun onCreate() {
        super.onCreate()
        // First, so whatever fails below already reaches PPM's Logs.
        AppTrace.install(this)
        // Once per process; creating another window must not remove live attachment copies.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { clearAttachmentCopies(this@PpmApplication) }
        SyntaxHighlighter.get(this).warm()
        PpmFonts.warm()
    }
}
