package com.rimpydroid.manager

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory log (last lines only) that the user can read and copy from Settings when something goes wrong. */
object AppLog {

    private const val MAX_LINES = 600
    private const val MAX_LINE_LENGTH = 500

    private val lines = ArrayDeque<String>()
    private val _version = MutableStateFlow(0L)

    /** Changes whenever a line is added. */
    val version: StateFlow<Long> = _version

    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun i(tag: String, message: String) = add('I', tag, message, null)

    fun w(tag: String, message: String, t: Throwable? = null) = add('W', tag, message, t)

    fun e(tag: String, message: String, t: Throwable? = null) = add('E', tag, message, t)

    private fun add(level: Char, tag: String, message: String, t: Throwable?) {
        val text = buildString {
            append(timeFormat.get()!!.format(Date()))
            append(' ').append(level).append(' ').append(tag).append(": ").append(message)
            if (t != null) append(" (").append(t.javaClass.simpleName).append(": ").append(t.message).append(')')
        }.let { if (it.length > MAX_LINE_LENGTH) it.substring(0, MAX_LINE_LENGTH) + "…" else it }

        synchronized(lines) {
            lines.addLast(text)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        _version.value = _version.value + 1

        try {
            when (level) {
                'E' -> Log.e("RimpyDroid", "$tag: $message", t)
                'W' -> Log.w("RimpyDroid", "$tag: $message", t)
                else -> Log.i("RimpyDroid", "$tag: $message")
            }
        } catch (e: Throwable) {
            // not running on Android (unit tests)
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun asText(): String = snapshot().joinToString("\n")

    fun clear() {
        synchronized(lines) { lines.clear() }
        _version.value = _version.value + 1
    }
}
