package com.hienlh.ppm.design

import androidx.compose.runtime.staticCompositionLocalOf

/** A picture's file as the lightbox shows, saves and copies it: its name, its bytes, and the type it was served as. */
class ViewerImage(val name: String, val bytes: ByteArray, val type: String?)

/**
 * One picture the lightbox can move to (`GalleryImage` in image-overlay-store.ts). [key] tells it
 * from the others, the same picture shown twice having the same one; [read] fetches its file once
 * it is shown, null when it will not load.
 */
class GalleryImage(val key: String, val name: String, val read: suspend () -> ViewerImage?)

/** The lightbox (`useImageOverlay().open`): [images] in the order the page shows them, opened on the one at [index]. */
fun interface ImageViewer {
    fun show(images: List<GalleryImage>, index: Int)
}

/** Where a picture opens full size; none, and a tap on one does nothing. */
val LocalImageViewer = staticCompositionLocalOf<ImageViewer?> { null }
