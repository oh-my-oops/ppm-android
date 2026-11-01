package com.hienlh.ppm.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Minimized floating windows keep their composition, but their content must suspend work. */
val LocalWindowContentVisible = staticCompositionLocalOf { true }
