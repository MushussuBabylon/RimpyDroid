package com.rimpydroid.manager.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** XML helpers. Mod files are often sloppy (BOMs, stray control characters), so be forgiving. */
object XmlUtil {

    fun readText(file: File, maxBytes: Int = 4 * 1024 * 1024): String {
        val out = ByteArrayOutputStream()
        file.inputStream().use { ins ->
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) break
                out.write(buf, 0, n)
            }
        }
        return decode(out.toByteArray())
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private val invalidChars = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFE\\uFFFF]")

    /** Removes BOM, leading whitespace and characters that are illegal in XML 1.0. */
    fun sanitize(text: String): String {
        var t = text
        if (t.isNotEmpty() && t[0] == '﻿') t = t.substring(1)
        t = invalidChars.replace(t, "")
        return t.trimStart()
    }

    private val quietHandler = object : ErrorHandler {
        override fun warning(exception: SAXParseException?) {}
        override fun error(exception: SAXParseException?) {}
        override fun fatalError(exception: SAXParseException?) {
            if (exception != null) throw exception
        }
    }

    private fun trySetFeature(f: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            f.setFeature(name, value)
        } catch (e: Exception) {
            // feature not supported by this parser: ignore
        }
    }

    /**
     * Parses [xml] into a DOM tree. Throws if it is not well-formed, or if it defines entities of its own.
     * External entities are disabled.
     */
    fun parse(xml: String): Document {
        // Entity definitions of its own let a few bytes of XML expand into gigabytes in some parsers
        // ("billion laughs"), and the file may come from any Workshop mod. No About.xml or ModsConfig.xml needs
        // them; the callers fall back to their tolerant text readers.
        if (xml.contains("<!ENTITY")) throw IllegalArgumentException("XML that defines entities is not accepted")
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        f.isExpandEntityReferences = false
        trySetFeature(f, "http://xml.org/sax/features/external-general-entities", false)
        trySetFeature(f, "http://xml.org/sax/features/external-parameter-entities", false)
        trySetFeature(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val b = f.newDocumentBuilder()
        b.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        b.setErrorHandler(quietHandler)
        return b.parse(InputSource(StringReader(sanitize(xml))))
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private val entityRegex = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

    fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        return entityRegex.replace(s) { m ->
            val g = m.groupValues[1]
            when {
                g == "amp" -> "&"
                g == "lt" -> "<"
                g == "gt" -> ">"
                g == "quot" -> "\""
                g == "apos" -> "'"
                g.startsWith("#x") -> g.substring(2).toIntOrNull(16)?.let { cp -> codePointToString(cp) } ?: m.value
                g.startsWith("#") -> g.substring(1).toIntOrNull()?.let { cp -> codePointToString(cp) } ?: m.value
                else -> m.value
            }
        }
    }

    private fun codePointToString(cp: Int): String? {
        return try {
            if (cp < 0 || cp > 0x10FFFF) null else String(Character.toChars(cp))
        } catch (e: Exception) {
            null
        }
    }
}

// ---- DOM helpers ---------------------------------------------------------------------------------

fun Element.childElements(): List<Element> {
    val out = ArrayList<Element>()
    var n: Node? = this.firstChild
    while (n != null) {
        if (n is Element) out.add(n)
        n = n.nextSibling
    }
    return out
}

/** First child element with this tag name (case-sensitive first, then case-insensitive). */
fun Element.child(name: String): Element? {
    val kids = this.childElements()
    for (k in kids) if (k.tagName == name) return k
    for (k in kids) if (k.tagName.equals(name, ignoreCase = true)) return k
    return null
}

fun Element.childText(name: String): String = this.child(name)?.textContent?.trim() ?: ""

/** Text of all <li> children, trimmed, blanks removed. */
fun Element.liTexts(): List<String> {
    val out = ArrayList<String>()
    for (k in this.childElements()) {
        if (k.tagName.equals("li", ignoreCase = true)) {
            val t = k.textContent?.trim() ?: ""
            if (t.isNotEmpty()) out.add(t)
        }
    }
    return out
}
