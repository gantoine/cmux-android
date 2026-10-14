package com.gantoine.cmux.terminal

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

interface TerminalInputSink {
    fun text(text: String)
    fun key(name: String)
}

class TerminalInputView(context: Context) : View(context) {
    var sink: TerminalInputSink? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun showKeyboard() {
        requestFocus()
        inputMethodManager().showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        inputMethodManager().hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)

    fun handleKey(event: KeyEvent): Boolean {
        val target = sink ?: return false
        when (val input = TerminalKeys.translate(event)) {
            is TerminalKeys.Input.Key -> target.key(input.name)
            is TerminalKeys.Input.Text -> target.text(input.text)
            null -> return false
        }
        return true
    }

    fun deliverText(text: String) {
        val target = sink ?: return
        TerminalKeys.splitText(text).forEach { piece ->
            when (piece) {
                is TerminalKeys.Input.Key -> target.key(piece.name)
                is TerminalKeys.Input.Text -> target.text(piece.text)
            }
        }
    }

    fun deliverKey(name: String) {
        sink?.key(name)
    }

    private fun inputMethodManager(): InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
}

private class TerminalInputConnection(private val view: TerminalInputView) : BaseInputConnection(view, false) {
    private var composing = ""

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        replaceComposing(text.toString())
        composing = ""
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        replaceComposing(text.toString())
        composing = text.toString()
        return true
    }

    override fun finishComposingText(): Boolean {
        composing = ""
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength.coerceAtMost(MAX_DELETE)) { view.deliverKey("backspace") }
        repeat(afterLength.coerceAtMost(MAX_DELETE)) { view.deliverKey("delete") }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) view.handleKey(event)
        return true
    }

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = composing.takeLast(length)

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""

    private fun replaceComposing(replacement: String) {
        val edit = TerminalKeys.diff(composing, replacement)
        repeat(edit.backspaces) { view.deliverKey("backspace") }
        if (edit.added.isNotEmpty()) view.deliverText(edit.added)
    }

    private companion object {
        const val MAX_DELETE = 256
    }
}

data class StickyModifiers(val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false) {
    val any: Boolean get() = ctrl || alt || shift

    fun prefix(includeShift: Boolean): String =
        listOfNotNull("ctrl".takeIf { ctrl }, "alt".takeIf { alt }, "shift".takeIf { shift && includeShift })
            .joinToString("") { "$it+" }
}

object TerminalKeys {
    sealed interface Input {
        data class Key(val name: String) : Input
        data class Text(val text: String) : Input
    }

    private val combinableKeys = setOf(
        "up", "down", "left", "right", "home", "end", "pageup", "pagedown", "enter", "tab", "backspace", "delete",
    )

    fun withModifiers(text: String, modifiers: StickyModifiers): Input {
        val single = text.codePointCount(0, text.length) == 1
        if (single && (modifiers.ctrl || modifiers.alt) && text[0].isLetterOrDigit()) {
            return Input.Key(modifiers.prefix(includeShift = false) + text.lowercase())
        }
        if (single && modifiers.shift && !modifiers.ctrl && !modifiers.alt) return Input.Text(text.uppercase())
        return Input.Text(text)
    }

    fun keyWithModifiers(name: String, modifiers: StickyModifiers): Input.Key {
        if (!modifiers.any || name !in combinableKeys) return Input.Key(name)
        if (name == "tab" && modifiers.shift && !modifiers.ctrl && !modifiers.alt) return Input.Key("shift+tab")
        return Input.Key(modifiers.prefix(includeShift = true) + name)
    }

    data class Edit(val backspaces: Int, val added: String)

    fun diff(before: String, after: String): Edit {
        val common = before.commonPrefixWith(after).length
        return Edit(backspaces = before.length - common, added = after.substring(common))
    }

    fun splitText(text: String): List<Input> {
        val pieces = mutableListOf<Input>()
        val pending = StringBuilder()
        fun flush() {
            if (pending.isNotEmpty()) pieces += Input.Text(pending.toString())
            pending.clear()
        }
        for (character in text) {
            when (character) {
                '\n', '\r' -> {
                    flush()
                    pieces += Input.Key("enter")
                }
                '\t' -> {
                    flush()
                    pieces += Input.Key("tab")
                }
                else -> pending.append(character)
            }
        }
        flush()
        return pieces
    }

    fun translate(event: KeyEvent): Input? {
        named(event.keyCode, event.isShiftPressed)?.let { name ->
            return Input.Key(withModifiers(name, event, includeShift = false))
        }
        val unicode = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (unicode == 0) return null
        val character = unicode.toChar()
        if ((event.isCtrlPressed || event.isAltPressed) && character.isLetterOrDigit()) {
            return Input.Key(withModifiers(character.lowercaseChar().toString(), event, includeShift = false))
        }
        return Input.Text(String(Character.toChars(unicode)))
    }

    private fun named(keyCode: Int, shift: Boolean): String? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
        KeyEvent.KEYCODE_DEL -> "backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
        KeyEvent.KEYCODE_TAB -> if (shift) "shift+tab" else "tab"
        KeyEvent.KEYCODE_ESCAPE -> "escape"
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_MOVE_HOME -> "home"
        KeyEvent.KEYCODE_MOVE_END -> "end"
        KeyEvent.KEYCODE_PAGE_UP -> "pageup"
        KeyEvent.KEYCODE_PAGE_DOWN -> "pagedown"
        else -> null
    }

    private fun withModifiers(name: String, event: KeyEvent, includeShift: Boolean): String {
        val modifiers = buildList {
            if (event.isCtrlPressed) add("ctrl")
            if (event.isAltPressed) add("alt")
            if (includeShift && event.isShiftPressed) add("shift")
        }
        return (modifiers + name).joinToString("+")
    }
}
