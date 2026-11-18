package com.hienlh.ppm.app.ui

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.designShot

/** What `LocalDesignShots` reads a `design_screenshot` picture with: `/api/design-shots/<id>.png`. */
internal fun designShotReader(api: ApiClient?): (suspend (String) -> ByteArray?)? =
    api?.let { client -> { id -> client.designShot(id) } }
