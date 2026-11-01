package org.connectbot.terminal

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/** Debug test APK only: observe a real IME without logging input from the production app. */
class CommittedImeProbeActivity : Activity() {
    private lateinit var terminal: TerminalEmulatorImpl
    private lateinit var status: TextView
    private var sent = ""
    private var pending = ""
    private val editorInputType get() = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
        (if (intent.getBooleanExtra("suggestions", false)) 0 else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) or
        (if (intent.getBooleanExtra("autocorrect", false)) InputType.TYPE_TEXT_FLAG_AUTO_CORRECT else 0)
    private fun trace(event: String) { Log.i("PpmImeProbe", event) }
    private fun quoted(text: CharSequence?): String = text.toString().codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }
    private fun showState() { status.text = "Native bytes: $sent\nPreedit: $pending" }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        terminal = TerminalEmulatorFactory.create(onKeyboardInput = {
            val text = it.toString(Charsets.UTF_8)
            sent += text
            trace("OUTPUT ${quoted(text)}")
            showState()
        }) as TerminalEmulatorImpl
        val keyboard = KeyboardHandler(QueuedTerminal(terminal))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 40, 24, 16) }
        status = TextView(this).apply { textSize = 22f }
        root.addView(TextView(this).apply { text = "Committed terminal IME probe — touch the blue editor"; textSize = 22f })
        root.addView(status)
        val editor = object : View(this) {
            var connection: CommittedImeConnection? = null
            var serial = 0
            val imm = getSystemService(InputMethodManager::class.java)
            init { isFocusable = true; isFocusableInTouchMode = true; setBackgroundColor(0xffbbddff.toInt()) }
            override fun onCheckIsTextEditor() = true
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) { requestFocus(); imm.showSoftInput(this, 0) }
                return true
            }
            override fun onKeyDown(code: Int, event: KeyEvent): Boolean =
                connection?.handleRawKeyEvent(event) ?: keyboard.onKeyEvent(ComposeKeyEvent(event))
            override fun onCreateInputConnection(info: EditorInfo): InputConnection {
                val id = ++serial
                fun trace(event: String) = this@CommittedImeProbeActivity.trace("#$id $event")
                trace("CREATE ${Throwable().stackTrace.take(7).joinToString()}")
                // A speculative framework create can be abandoned while the earlier editor
                // stays served. Let the framework close its returned connection.
                info.inputType = editorInputType
                info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
                info.initialSelStart = 0; info.initialSelEnd = 0
                val next = CommittedImeConnection(this,
                    onCommit = { text, modifiers -> trace("EMIT ${quoted(text)} modifiers=$modifiers"); keyboard.onCommittedText(text, modifiers) },
                    onKey = { trace("KEY ${it.action}/${it.keyCode}/${it.metaState}"); keyboard.onKeyEvent(ComposeKeyEvent(it)) },
                    onPreedit = { pending = it; showState(); trace("PREEDIT ${quoted(it)}") },
                    onSelection = { a, b, c, d -> trace("SELECTION $a $b $c $d"); imm.updateSelection(this, a, b, c, d) },
                    onExtractedText = { token, text -> imm.updateExtractedText(this, token, text) },
                    onInvalidate = { trace("RESTART"); imm.restartInput(this) },
                )
                connection = next
                return object : InputConnectionWrapper(next, false) {
                    override fun setComposingText(text: CharSequence, cursor: Int, attr: TextAttribute?): Boolean { trace("COMPOSE3 ${quoted(text)} cursor=$cursor active=${next.isActive}"); return super.setComposingText(text, cursor, attr) }
                    override fun commitText(text: CharSequence, cursor: Int, attr: TextAttribute?): Boolean { trace("COMMIT3 ${quoted(text)} cursor=$cursor active=${next.isActive}"); return super.commitText(text, cursor, attr) }
                    override fun replaceText(a: Int, b: Int, text: CharSequence, cursor: Int, attr: TextAttribute?): Boolean { trace("REPLACE $a $b ${quoted(text)} cursor=$cursor active=${next.isActive}"); return super.replaceText(a, b, text, cursor, attr) }
                    override fun setComposingText(text: CharSequence?, cursor: Int): Boolean { trace("COMPOSE ${quoted(text)} cursor=$cursor"); return super.setComposingText(text, cursor) }
                    override fun commitText(text: CharSequence?, cursor: Int): Boolean { trace("COMMIT ${quoted(text)} cursor=$cursor"); return super.commitText(text, cursor) }
                    override fun finishComposingText(): Boolean { trace("FINISH"); return super.finishComposingText() }
                    override fun setComposingRegion(a: Int, b: Int): Boolean { trace("REGION $a $b"); return super.setComposingRegion(a, b) }
                    override fun setSelection(a: Int, b: Int): Boolean { trace("SET_SELECTION $a $b"); return super.setSelection(a, b) }
                    override fun deleteSurroundingText(a: Int, b: Int): Boolean { trace("DELETE $a $b"); return super.deleteSurroundingText(a, b) }
                    override fun deleteSurroundingTextInCodePoints(a: Int, b: Int): Boolean { trace("DELETE_CP $a $b"); return super.deleteSurroundingTextInCodePoints(a, b) }
                    override fun sendKeyEvent(event: KeyEvent): Boolean { trace("SEND_KEY ${event.action}/${event.keyCode}/${event.metaState}"); return super.sendKeyEvent(event) }
                    override fun beginBatchEdit(): Boolean { trace("BEGIN"); return super.beginBatchEdit() }
                    override fun endBatchEdit(): Boolean { trace("END"); return super.endBatchEdit() }
                    override fun closeConnection() { trace("CLOSE"); super.closeConnection() }
                }
            }
        }
        root.addView(editor, LinearLayout.LayoutParams(-1, 110))
        root.addView(Button(this).apply { text = "Reset probe"; setOnClickListener { sent = ""; pending = ""; editor.connection?.closeConnection(); editor.imm.restartInput(editor); editor.requestFocus(); showState() } })
        root.addView(TextView(this).apply { text = "Control: standard Android EditText, same input flags"; textSize = 18f })
        root.addView(EditText(this).apply {
            inputType = editorInputType
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            textSize = 22f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { trace("CONTROL ${quoted(s)}") }
                override fun afterTextChanged(s: Editable?) {}
            })
        })
        setContentView(root)
        showState()
        editor.requestFocus()
    }

    override fun onDestroy() { terminal.close(); super.onDestroy() }
}
