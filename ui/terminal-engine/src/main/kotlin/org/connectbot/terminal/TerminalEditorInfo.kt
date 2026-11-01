package org.connectbot.terminal

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What the soft keyboard is told about the terminal's editor: the two numbers Chrome gives it for
 * xterm.js's textarea (`inputType` 0x2200a1 and `imeOptions` 0x12000001 in `dumpsys input_method`),
 * with one flag added to the second (below).
 *
 * `TYPE_TEXT_FLAG_NO_SUGGESTIONS` is not how a terminal keeps a keyboard from correcting what is typed:
 * with it (inputType 0xa0001) Gboard still showed its strip and turned "ech " into "echo ". The web's
 * field is a web edit text with neither `AUTO_CORRECT` nor `NO_SUGGESTIONS`, and Gboard leaves what is
 * typed into it alone, so this asks for exactly that.
 *
 * One flag is the app's own: Chrome omits `IME_FLAG_NO_PERSONALIZED_LEARNING`, so Gboard learns what is
 * typed into the web terminal. A terminal is where passwords are typed through the soft keyboard (sudo,
 * ssh), and a keyboard must not learn those, so [IME_OPTIONS] adds it. It is the one difference from
 * Chrome's 0x12000001, and the first thing to drop if autocorrect ever comes back with it.
 */
internal object TerminalEditorInfo {
    /**
     * Text, a web edit text, multi-line; and from Android 17 the suggestion-selected flag Chrome adds
     * (the SDK documents nothing about it, it is here because Chrome sends it).
     */
    fun inputType(sdk: Int = Build.VERSION.SDK_INT): Int =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            if (sdk >= Build.VERSION_CODES.CINNAMON_BUN) InputType.TYPE_TEXT_FLAG_ENABLE_TEXT_SUGGESTION_SELECTED else 0

    /**
     * No action key, no full-screen editor and no extracted-text UI, as Chrome asks (Enter is a key,
     * never an action), and no personalised learning, which Chrome does not ask.
     */
    const val IME_OPTIONS = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
}
