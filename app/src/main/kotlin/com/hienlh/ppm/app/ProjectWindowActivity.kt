package com.hienlh.ppm.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID

/** An internal document task owns its model across Activity recreation. */
class ProjectWindowActivity : MainActivity() {
    private val windowId: String by lazy { requireNotNull(intent.getStringExtra(WINDOW_ID)) }

    override fun createModel(app: PpmApplication): AppModel = app.windowModel(
        windowId, DebugLaunch.from(this, intent), intent.getStringExtra(SERVER_ID), intent.getStringExtra(PROJECT),
    )

    override fun onDestroy() {
        if (isFinishing) (application as PpmApplication).releaseWindow(windowId)
        super.onDestroy()
    }

    companion object {
        private const val WINDOW_ID = "ppm.window.id"
        private const val SERVER_ID = "ppm.window.server"
        private const val PROJECT = "ppm.window.project"

        fun open(context: Context, model: AppModel, project: String, adjacent: Boolean = true) {
            val id = UUID.randomUUID().toString()
            val flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                (if (adjacent) Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT else 0)
            val intent = Intent(context, ProjectWindowActivity::class.java)
                .setData(Uri.parse("ppm-window://project/$id"))
                .putExtra(WINDOW_ID, id)
                .putExtra(SERVER_ID, model.store.selected?.id)
                .putExtra(PROJECT, project)
                .addFlags(flags)
            // Emulator launches have no saved server entry. These debug-only extras are read
            // only in debuggable builds; this Activity is not exported to other applications.
            model.debug?.let {
                intent.putExtra("url", it.url.toString()).putExtra("password", it.password).putExtra("project", project)
            }
            context.startActivity(intent)
        }
    }
}
