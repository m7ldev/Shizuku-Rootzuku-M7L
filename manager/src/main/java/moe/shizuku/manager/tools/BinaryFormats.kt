package moe.shizuku.manager.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Readers for binary files that the viewer shows as text: the compiled XML of apk files and
 * dex files.
 */
object BinaryFormats {

    private const val MAX_LINES = 30000

    fun isBinaryXml(data: ByteArray): Boolean {
        return data.size >= 8 && data[0] == 0x03.toByte() && data[1] == 0x00.toByte() &&
                data[2] == 0x08.toByte() && data[3] == 0x00.toByte()
    }

    fun isDex(data: ByteArray): Boolean {
        return data.size >= 0x70 && data[0] == 'd'.code.toByte() && data[1] == 'e'.code.toByte() &&
                data[2] == 'x'.code.toByte() && data[3] == '\n'.code.toByte()
    }

    // ---- compiled XML

    private fun readPoolString(b: ByteBuffer, at: Int, utf8: Boolean): String {
        var pos = at
        if (utf8) {
            // Length in characters, then length in bytes, each in one or two bytes
            if ((b.get(pos).toInt() and 0x80) != 0) pos += 2 else pos += 1
            var length = b.get(pos).toInt() and 0xFF
            if ((length and 0x80) != 0) {
                length = ((length and 0x7F) shl 8) or (b.get(pos + 1).toInt() and 0xFF)
                pos += 2
            } else {
                pos += 1
            }
            val bytes = ByteArray(length)
            for (i in 0 until length) bytes[i] = b.get(pos + i)
            return String(bytes, Charsets.UTF_8)
        }
        var length = b.getShort(pos).toInt() and 0xFFFF
        pos += 2
        if ((length and 0x8000) != 0) {
            length = ((length and 0x7FFF) shl 16) or (b.getShort(pos).toInt() and 0xFFFF)
            pos += 2
        }
        val chars = CharArray(length)
        for (i in 0 until length) chars[i] = b.getShort(pos + i * 2).toInt().toChar()
        return String(chars)
    }

    private fun complex(data: Int): Float {
        val factors = floatArrayOf(1f / (1 shl 8), 1f / (1 shl 15), 1f / (1 shl 23), 1f / (1L shl 31))
        return (data and -256).toFloat() * factors[(data shr 4) and 3]
    }

    private fun number(value: Float): String {
        return if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
    }

    private fun typedValue(type: Int, data: Int, strings: List<String>): String {
        return when (type) {
            0x00 -> ""
            0x01 -> String.format(Locale.ROOT, "@0x%08x", data)
            0x02 -> String.format(Locale.ROOT, "?0x%08x", data)
            0x03 -> strings.getOrElse(data) { "" }
            0x04 -> java.lang.Float.intBitsToFloat(data).toString()
            0x05 -> number(complex(data)) + arrayOf("px", "dp", "sp", "pt", "in", "mm").getOrElse(data and 0xF) { "" }
            0x06 -> number(complex(data) * 100) + if ((data and 0xF) == 1) "%p" else "%"
            0x10 -> data.toString()
            0x11 -> String.format(Locale.ROOT, "0x%x", data)
            0x12 -> if (data != 0) "true" else "false"
            in 0x1c..0x1f -> String.format(Locale.ROOT, "#%08x", data)
            else -> String.format(Locale.ROOT, "0x%x", data)
        }
    }

    private fun escape(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;").replace("\n", "&#10;")
    }

    /**
     * Turns the compiled XML of an apk file, such as AndroidManifest.xml, back into text.
     */
    fun decodeXml(data: ByteArray): String {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val strings = ArrayList<String>()
        var resourceIds = IntArray(0)
        val prefixes = LinkedHashMap<String, String>()
        val pendingNamespaces = ArrayList<Pair<String, String>>()
        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var depth = 0
        var pos = (b.getShort(2).toInt() and 0xFFFF)

        fun string(index: Int): String = if (index < 0) "" else strings.getOrElse(index) { "" }
        fun qualified(namespace: Int, name: Int): String {
            var text = string(name)
            if (text.isEmpty() && name in resourceIds.indices) {
                text = String.format(Locale.ROOT, "attr_0x%08x", resourceIds[name])
            }
            val prefix = prefixes[string(namespace)]
            return if (prefix.isNullOrEmpty()) text else "$prefix:$text"
        }

        while (pos + 8 <= data.size) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val headerSize = b.getShort(pos + 2).toInt() and 0xFFFF
            val size = b.getInt(pos + 4)
            if (size < 8 || pos + size > data.size) break
            when (type) {
                0x0001 -> {
                    val count = b.getInt(pos + 8)
                    val utf8 = (b.getInt(pos + 16) and (1 shl 8)) != 0
                    val start = pos + b.getInt(pos + 20)
                    for (i in 0 until count) {
                        strings.add(
                            try {
                                readPoolString(b, start + b.getInt(pos + headerSize + i * 4), utf8)
                            } catch (e: Throwable) {
                                ""
                            }
                        )
                    }
                }
                0x0180 -> resourceIds = IntArray((size - headerSize) / 4) { b.getInt(pos + headerSize + it * 4) }
                0x0100 -> {
                    val prefix = string(b.getInt(pos + 16))
                    val uri = string(b.getInt(pos + 20))
                    prefixes[uri] = prefix
                    pendingNamespaces.add(prefix to uri)
                }
                0x0102 -> {
                    val body = pos + headerSize
                    val name = qualified(b.getInt(body), b.getInt(body + 4))
                    val attributeStart = b.getShort(body + 8).toInt() and 0xFFFF
                    val attributeSize = (b.getShort(body + 10).toInt() and 0xFFFF).coerceAtLeast(20)
                    val attributeCount = b.getShort(body + 12).toInt() and 0xFFFF
                    val indent = "    ".repeat(depth)
                    out.append(indent).append('<').append(name)
                    for ((prefix, uri) in pendingNamespaces) {
                        out.append("\n").append(indent).append("    xmlns:").append(prefix)
                            .append("=\"").append(escape(uri)).append('"')
                    }
                    pendingNamespaces.clear()
                    for (i in 0 until attributeCount) {
                        val at = body + attributeStart + i * attributeSize
                        val raw = b.getInt(at + 8)
                        val value = if (raw >= 0) string(raw) else typedValue(
                            b.get(at + 15).toInt() and 0xFF, b.getInt(at + 16), strings
                        )
                        out.append("\n").append(indent).append("    ")
                            .append(qualified(b.getInt(at), b.getInt(at + 4)))
                            .append("=\"").append(escape(value)).append('"')
                    }
                    out.append(">\n")
                    depth++
                }
                0x0103 -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    val body = pos + headerSize
                    out.append("    ".repeat(depth)).append("</")
                        .append(qualified(b.getInt(body), b.getInt(body + 4))).append(">\n")
                }
                0x0104 -> {
                    val text = string(b.getInt(pos + headerSize)).trim()
                    if (text.isNotEmpty()) out.append("    ".repeat(depth)).append(escape(text)).append('\n')
                }
            }
            pos += size
        }
        return out.toString()
    }

    // ---- writing compiled XML

    /** Attributes of the manifest that are often added by hand, with their fixed ids. */
    private val KNOWN_ATTRIBUTES = mapOf(
        "theme" to 0x01010000, "label" to 0x01010001, "icon" to 0x01010002, "name" to 0x01010003,
        "permission" to 0x01010006, "enabled" to 0x0101000e, "debuggable" to 0x0101000f,
        "exported" to 0x01010010, "process" to 0x01010011, "authorities" to 0x01010018,
        "value" to 0x01010024, "minSdkVersion" to 0x0101020c, "versionCode" to 0x0101021b,
        "versionName" to 0x0101021c, "targetSdkVersion" to 0x01010270, "maxSdkVersion" to 0x01010271,
        "testOnly" to 0x01010272, "allowBackup" to 0x01010280, "hardwareAccelerated" to 0x010102d3,
        "largeHeap" to 0x0101035a, "extractNativeLibs" to 0x010104ea, "usesCleartextTraffic" to 0x010104ec,
        "networkSecurityConfig" to 0x01010527, "requestLegacyExternalStorage" to 0x01010603
    )

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    private class TypedValue(val type: Int, val data: Int, val text: String?)

    /** What is taken over from the file as it was before editing. */
    private class Original {
        /** Namespace and name of an attribute to its resource id. */
        val ids = HashMap<String, Int>()

        /** Attribute and value text to the exact value it had. */
        val values = HashMap<String, TypedValue>()

        /** Attribute to the type of value it holds. */
        val types = HashMap<String, Int>()
    }

    private fun scanOriginal(data: ByteArray): Original {
        val result = Original()
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val strings = ArrayList<String>()
        var resourceIds = IntArray(0)
        var pos = (b.getShort(2).toInt() and 0xFFFF)
        fun string(index: Int): String = if (index < 0) "" else strings.getOrElse(index) { "" }
        while (pos + 8 <= data.size) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val headerSize = b.getShort(pos + 2).toInt() and 0xFFFF
            val size = b.getInt(pos + 4)
            if (size < 8 || pos + size > data.size) break
            when (type) {
                0x0001 -> {
                    val count = b.getInt(pos + 8)
                    val utf8 = (b.getInt(pos + 16) and (1 shl 8)) != 0
                    val start = pos + b.getInt(pos + 20)
                    for (i in 0 until count) {
                        strings.add(
                            try {
                                readPoolString(b, start + b.getInt(pos + headerSize + i * 4), utf8)
                            } catch (e: Throwable) {
                                ""
                            }
                        )
                    }
                }
                0x0180 -> resourceIds = IntArray((size - headerSize) / 4) { b.getInt(pos + headerSize + it * 4) }
                0x0102 -> {
                    val body = pos + headerSize
                    val attributeStart = b.getShort(body + 8).toInt() and 0xFFFF
                    val attributeSize = (b.getShort(body + 10).toInt() and 0xFFFF).coerceAtLeast(20)
                    val attributeCount = b.getShort(body + 12).toInt() and 0xFFFF
                    for (i in 0 until attributeCount) {
                        val at = body + attributeStart + i * attributeSize
                        val nameIndex = b.getInt(at + 4)
                        var name = string(nameIndex)
                        if (name.isEmpty() && nameIndex in resourceIds.indices) {
                            name = String.format(Locale.ROOT, "attr_0x%08x", resourceIds[nameIndex])
                        }
                        val key = string(b.getInt(at)) + "\u0000" + name
                        if (nameIndex in resourceIds.indices) result.ids[key] = resourceIds[nameIndex]
                        val raw = b.getInt(at + 8)
                        val valueType = b.get(at + 15).toInt() and 0xFF
                        val valueData = b.getInt(at + 16)
                        val text = if (raw >= 0) string(raw) else typedValue(valueType, valueData, strings)
                        val value = if (raw >= 0 || valueType == 0x03) TypedValue(0x03, 0, text) else TypedValue(valueType, valueData, null)
                        result.values[key + "\u0000" + text] = value
                        result.types[key] = value.type
                    }
                }
            }
            pos += size
        }
        return result
    }

    private fun parseInt(text: String): Int? {
        val t = text.trim()
        return try {
            when {
                t.startsWith("0x") || t.startsWith("0X") -> java.lang.Long.parseLong(t.substring(2), 16).toInt()
                t.startsWith("-0x") -> -java.lang.Long.parseLong(t.substring(3), 16).toInt()
                else -> t.toInt()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * The value for the text of an attribute. [type] is what the attribute held before, or
     * null for an attribute that is new.
     */
    private fun valueOf(text: String, type: Int?): TypedValue {
        val t = text.trim()
        fun reference(): TypedValue? {
            if (t.length < 4 || (t[0] != '@' && t[0] != '?')) return null
            val id = parseInt(t.substring(1)) ?: return null
            return TypedValue(if (t[0] == '@') 0x01 else 0x02, id, null)
        }
        fun boolean(): TypedValue? = when (t) {
            "true" -> TypedValue(0x12, -1, null)
            "false" -> TypedValue(0x12, 0, null)
            else -> null
        }
        return when (type) {
            0x03 -> TypedValue(0x03, 0, text)
            0x12 -> boolean()
            0x10 -> parseInt(t)?.let { TypedValue(if (t.contains("0x")) 0x11 else 0x10, it, null) }
            0x11 -> parseInt(t)?.let { TypedValue(0x11, it, null) }
            0x01, 0x02 -> reference()
            0x04 -> t.toFloatOrNull()?.let { TypedValue(0x04, java.lang.Float.floatToIntBits(it), null) }
            null -> boolean() ?: reference()
            ?: (if (t.matches(Regex("-?\\d+"))) parseInt(t)?.let { TypedValue(0x10, it, null) } else null)
            else -> null
        } ?: reference() ?: TypedValue(0x03, 0, text)
    }

    private class Chunks {
        val out = java.io.ByteArrayOutputStream()

        fun int(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        fun short(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
    }

    /**
     * Turns edited XML text into the compiled form again. [original] is the file as it was:
     * the ids of attributes and the exact form of values that did not change come from it.
     *
     * @throws IllegalArgumentException with a message for the user when the text cannot be compiled
     */
    fun encodeXml(text: String, original: ByteArray): ByteArray {
        val before = scanOriginal(original)

        class Attribute(val namespace: String, val name: String, val resourceId: Int?, val value: TypedValue)
        class Event(
            val kind: Int, val line: Int, val first: String, val second: String,
            val attributes: List<Attribute> = emptyList()
        )

        // ---- read the text
        val events = ArrayList<Event>()
        val factory = javax.xml.parsers.SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        val handler = object : org.xml.sax.helpers.DefaultHandler() {
            var locator: org.xml.sax.Locator? = null
            val characters = StringBuilder()
            val line: Int get() = (locator?.lineNumber ?: 1).coerceAtLeast(1)

            fun flush() {
                val content = characters.toString().trim()
                characters.setLength(0)
                if (content.isNotEmpty()) events.add(Event(0x0104, line, content, ""))
            }

            override fun setDocumentLocator(value: org.xml.sax.Locator?) {
                locator = value
            }

            override fun startPrefixMapping(prefix: String?, uri: String?) {
                flush()
                events.add(Event(0x0100, line, prefix.orEmpty(), uri.orEmpty()))
            }

            override fun endPrefixMapping(prefix: String?) {
                flush()
                events.add(Event(0x0101, line, prefix.orEmpty(), ""))
            }

            override fun startElement(uri: String?, localName: String?, qName: String?, atts: org.xml.sax.Attributes) {
                flush()
                val attributes = ArrayList<Attribute>()
                for (i in 0 until atts.length) {
                    val namespace = atts.getURI(i).orEmpty()
                    val name = atts.getLocalName(i).orEmpty().ifEmpty { atts.getQName(i).orEmpty() }
                    val value = atts.getValue(i).orEmpty()
                    val key = namespace + "\u0000" + name
                    val id = before.ids[key]
                        ?: (if (name.startsWith("attr_0x")) parseInt(name.substring(5)) else null)
                        ?: (if (namespace == ANDROID_NAMESPACE) KNOWN_ATTRIBUTES[name] else null)
                    if (id == null && namespace.isNotEmpty()) {
                        throw IllegalArgumentException("Line $line: the attribute \"$name\" is not known, it cannot be added")
                    }
                    attributes.add(Attribute(namespace, name, id, before.values[key + "\u0000" + value] ?: valueOf(value, before.types[key])))
                }
                // The system looks attributes up by their id, so they must be in that order
                val sorted = attributes.filter { it.resourceId != null }.sortedBy { (it.resourceId ?: 0).toLong() and 0xFFFFFFFFL } +
                        attributes.filter { it.resourceId == null }
                events.add(Event(0x0102, line, uri.orEmpty(), localName.orEmpty().ifEmpty { qName.orEmpty() }, sorted))
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                flush()
                events.add(Event(0x0103, line, uri.orEmpty(), localName.orEmpty().ifEmpty { qName.orEmpty() }))
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                characters.append(ch, start, length)
            }
        }
        try {
            factory.newSAXParser().parse(org.xml.sax.InputSource(java.io.StringReader(text)), handler)
        } catch (e: org.xml.sax.SAXParseException) {
            throw IllegalArgumentException("Line ${e.lineNumber}: ${e.message}")
        } catch (e: org.xml.sax.SAXException) {
            throw IllegalArgumentException(e.cause?.message ?: e.message ?: e.toString())
        }
        if (events.none { it.kind == 0x0102 }) throw IllegalArgumentException("The text has no XML element")

        // ---- strings: names of attributes with an id come first, in the order of the ids
        val resourceIds = events.flatMap { it.attributes }.mapNotNull { it.resourceId }.distinct()
            .sortedBy { it.toLong() and 0xFFFFFFFFL }
        val idIndex = HashMap<Int, Int>()
        val pool = ArrayList<String>()
        val idNames = HashMap<Int, String>()
        for (event in events) for (attribute in event.attributes) {
            attribute.resourceId?.let { idNames[it] = if (attribute.name.startsWith("attr_0x")) "" else attribute.name }
        }
        for (id in resourceIds) {
            idIndex[id] = pool.size
            pool.add(idNames[id].orEmpty())
        }
        val others = HashMap<String, Int>()
        fun intern(value: String): Int = others.getOrPut(value) {
            pool.add(value)
            pool.size - 1
        }
        fun optional(value: String): Int = if (value.isEmpty()) -1 else intern(value)

        // ---- the elements
        val body = Chunks()
        fun header(type: Int, size: Int, line: Int) {
            body.short(type)
            body.short(16)
            body.int(size)
            body.int(line)
            body.int(-1)
        }
        for (event in events) {
            when (event.kind) {
                0x0100, 0x0101 -> {
                    val uri = if (event.kind == 0x0100) event.second else events.firstOrNull {
                        it.kind == 0x0100 && it.first == event.first
                    }?.second.orEmpty()
                    header(event.kind, 24, event.line)
                    body.int(intern(event.first))
                    body.int(intern(uri))
                }
                0x0102 -> {
                    header(0x0102, 36 + event.attributes.size * 20, event.line)
                    body.int(optional(event.first))
                    body.int(intern(event.second))
                    body.short(20)
                    body.short(20)
                    body.short(event.attributes.size)
                    fun position(name: String, withNamespace: Boolean): Int {
                        return event.attributes.indexOfFirst { it.name == name && it.namespace.isNotEmpty() == withNamespace } + 1
                    }
                    body.short(position("id", true))
                    body.short(position("class", false))
                    body.short(position("style", false))
                    for (attribute in event.attributes) {
                        body.int(optional(attribute.namespace))
                        body.int(attribute.resourceId?.let { idIndex[it] } ?: intern(attribute.name))
                        val value = attribute.value
                        val stringIndex = if (value.type == 0x03) intern(value.text.orEmpty()) else -1
                        body.int(stringIndex)
                        body.short(8)
                        body.out.write(0)
                        body.out.write(value.type)
                        body.int(if (value.type == 0x03) stringIndex else value.data)
                    }
                }
                0x0103 -> {
                    header(0x0103, 24, event.line)
                    body.int(optional(event.first))
                    body.int(intern(event.second))
                }
                0x0104 -> {
                    header(0x0104, 28, event.line)
                    body.int(intern(event.first))
                    body.short(8)
                    body.out.write(0)
                    body.out.write(0)
                    body.int(0)
                }
            }
        }

        // ---- the string pool, written as UTF-16
        val stringData = Chunks()
        val offsets = IntArray(pool.size)
        for ((index, value) in pool.withIndex()) {
            offsets[index] = stringData.out.size()
            if (value.length > 0x7FFF) {
                stringData.short(0x8000 or (value.length shr 16))
                stringData.short(value.length and 0xFFFF)
            } else {
                stringData.short(value.length)
            }
            for (c in value) stringData.short(c.code)
            stringData.short(0)
        }
        while (stringData.out.size() % 4 != 0) stringData.out.write(0)

        val poolSize = 28 + pool.size * 4 + stringData.out.size()
        val mapSize = 8 + resourceIds.size * 4
        val result = Chunks()
        result.short(0x0003)
        result.short(8)
        result.int(8 + poolSize + mapSize + body.out.size())
        result.short(0x0001)
        result.short(28)
        result.int(poolSize)
        result.int(pool.size)
        result.int(0)
        result.int(0)
        result.int(28 + pool.size * 4)
        result.int(0)
        for (offset in offsets) result.int(offset)
        result.out.write(stringData.out.toByteArray())
        result.short(0x0180)
        result.short(8)
        result.int(mapSize)
        for (id in resourceIds) result.int(id)
        result.out.write(body.out.toByteArray())
        return result.out.toByteArray()
    }

    // ---- dex

    /**
     * A summary of a dex file: its version, how much it holds and the names of its classes.
     */
    fun describeDex(data: ByteArray): String {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val stringCount = b.getInt(0x38)
        val stringOffsets = b.getInt(0x3C)
        val typeCount = b.getInt(0x40)
        val typeOffsets = b.getInt(0x44)
        val fieldCount = b.getInt(0x50)
        val methodCount = b.getInt(0x58)
        val classCount = b.getInt(0x60)
        val classOffsets = b.getInt(0x64)

        fun dexString(index: Int): String {
            if (index < 0 || index >= stringCount) return "?"
            var pos = b.getInt(stringOffsets + index * 4)
            // The length in characters comes first, it is not needed to find the end
            while ((data[pos].toInt() and 0x80) != 0) pos++
            pos++
            var end = pos
            while (end < data.size && data[end].toInt() != 0) end++
            return String(data, pos, end - pos, Charsets.UTF_8)
        }

        val classes = ArrayList<String>()
        for (i in 0 until classCount) {
            try {
                val type = b.getInt(classOffsets + i * 32)
                if (type < 0 || type >= typeCount) continue
                classes.add(dexString(b.getInt(typeOffsets + type * 4)).removePrefix("L").removeSuffix(";").replace('/', '.'))
            } catch (e: Throwable) {
                break
            }
        }
        classes.sort()

        val out = StringBuilder()
        out.append("DEX version: ").append(String(data, 4, 3, Charsets.US_ASCII)).append('\n')
        out.append("Classes: ").append(classCount).append('\n')
        out.append("Methods: ").append(methodCount).append('\n')
        out.append("Fields: ").append(fieldCount).append('\n')
        out.append("Strings: ").append(stringCount).append('\n')
        out.append("Types: ").append(typeCount).append("\n\n")
        for ((index, name) in classes.withIndex()) {
            if (index >= MAX_LINES) {
                out.append("… ").append(classes.size - MAX_LINES).append(" more\n")
                break
            }
            out.append(name).append('\n')
        }
        return out.toString()
    }

    // ---- anything else

    fun looksLikeText(data: ByteArray): Boolean {
        val limit = minOf(data.size, 8192)
        for (i in 0 until limit) {
            if (data[i].toInt() == 0) return false
        }
        return true
    }

    fun hexDump(data: ByteArray, limit: Int): String {
        val out = StringBuilder()
        val end = minOf(data.size, limit)
        var pos = 0
        while (pos < end) {
            out.append(String.format(Locale.ROOT, "%06x ", pos))
            val text = StringBuilder()
            for (i in 0 until 8) {
                if (pos + i < end) {
                    val value = data[pos + i].toInt() and 0xFF
                    out.append(String.format(Locale.ROOT, " %02x", value))
                    text.append(if (value in 32..126) value.toChar() else '.')
                } else {
                    out.append("   ")
                }
            }
            out.append("  ").append(text).append('\n')
            pos += 8
        }
        return out.toString()
    }
}
