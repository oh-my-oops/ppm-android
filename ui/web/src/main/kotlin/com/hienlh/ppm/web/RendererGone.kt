package com.hienlh.ppm.web

import android.webkit.RenderProcessGoneDetail

/** Why a WebView's page went away, for the render error the app reports (`PpmLog.renderError`). */
internal fun rendererGone(detail: RenderProcessGoneDetail): String =
    if (detail.didCrash()) "The WebView renderer crashed" else "The system stopped the WebView renderer to free memory"
