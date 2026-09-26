package com.github.iamr8.dotnetoutdated.edit

import com.github.iamr8.dotnetoutdated.engine.Edit
import kotlin.math.abs

data class Splice(val start: Int, val end: Int, val text: String)

sealed class EditResult {
    data class Ok(val splices: List<Splice>) : EditResult()

    /** [cause]: set only when the failure came from an unexpected exception during planning (a possible plugin bug), never for an expected "no match" / "file changed" result. */
    data class Failed(val reason: String, val cause: Throwable? = null) : EditResult()
}

/**
 * Pure: finds where each engine edit goes in one file's text. The engine gives a key (item type,
 * identity, condition, expected text) and a location hint; the key must match, the hint only
 * breaks ties. Comments and CDATA never match. The engine sends MSBuild's DECODED attribute and
 * element text (Condition, identity, expected value), so every comparison decodes the file's raw
 * XML text first; writes re-encode the new value.
 *
 * MSBuild's unevaluated values keep their surrounding whitespace and use whatever line ending the
 * source file has (often `\r\n`), while an IDE document is always LF. So every Edit string is
 * first normalized to `\n`; child/property/attribute comparisons then trim both sides (the file's
 * own surrounding whitespace is left alone - only the trimmed value range is spliced); Condition
 * comparisons collapse all whitespace runs to one space, since a Condition can span lines.
 */
object EditText {

    private class Fail(val reason: String) : Exception(reason)

    fun plan(text: String, edits: List<Edit>): EditResult {
        val xml = XmlScan(text)
        // Normalize before dedup: two edits differing only by \r\n vs \n must count as one, not
        // as a false "two changes touch the same text" overlap.
        val normalized = edits.map(::normalize).distinct()
        val splices = ArrayList<Splice>()
        try {
            val inserts = normalized.filter { it.op == "insert" && it.target == "item" }
            val others = normalized.filter { !(it.op == "insert" && it.target == "item") }
            for (edit in others) {
                splices += when (edit.op to edit.target) {
                    "set" to "attribute" -> setAttribute(xml, edit)
                    "set" to "child" -> setChild(xml, edit)
                    "set" to "property" -> setProperty(xml, edit)
                    "set" to "directive" -> setDirective(text, edit)
                    else -> throw Fail("unknown edit '${edit.op} ${edit.target}'")
                }
            }
            splices += planInserts(xml, inserts)
        } catch (e: Fail) {
            return EditResult.Failed(e.reason)
        } catch (e: Exception) {
            // any other failure during planning (for example a stale hint that leaves no valid
            // match to index into) is a plan failure, never an uncaught exception - nothing is
            // written either way. This is not an expected "no match" case (that's `Fail`, caught
            // above), so carry the throwable: the caller may want to log it as a possible bug.
            return EditResult.Failed(e.message ?: "could not plan the edit", e)
        }
        val sorted = splices.sortedBy { it.start }
        for (i in 1 until sorted.size) {
            if (sorted[i].start < sorted[i - 1].end) return EditResult.Failed("two changes touch the same text")
        }
        return EditResult.Ok(splices)
    }

    fun applyAll(text: String, splices: List<Splice>): String {
        val sb = StringBuilder(text)
        splices.sortedByDescending { it.start }.forEach { sb.replace(it.start, it.end, it.text) }
        return sb.toString()
    }

    /** `\r\n`/`\r` -> `\n`. The helper's strings may carry the source file's original line ending; IDE document text never does. */
    private fun normalizeNl(s: String): String = s.replace("\r\n", "\n").replace("\r", "\n")

    /**
     * Newline-normalized, then every whitespace run OUTSIDE a `'...'` MSBuild string literal
     * collapsed to one space, and trimmed - for Condition comparisons, which can span lines.
     * Whitespace INSIDE a quoted literal is significant to MSBuild (`'a  b'` != `'a b'`) and is
     * kept exactly as written, after the CRLF normalization every Edit string already gets.
     */
    private fun collapseWs(s: String): String {
        val n = normalizeNl(s)
        val sb = StringBuilder(n.length)
        var inQuote = false
        var i = 0
        while (i < n.length) {
            val c = n[i]
            if (c == '\'') {
                inQuote = !inQuote
                sb.append(c)
                i++
                continue
            }
            if (!inQuote && c.isWhitespace()) {
                sb.append(' ')
                while (i < n.length && n[i].isWhitespace()) i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString().trim()
    }

    private fun normalize(e: Edit): Edit = e.copy(
        identity = e.identity?.let(::normalizeNl),
        // A whitespace-only (or empty) Condition means "no condition" on both sides - must agree
        // with `conditionOf`'s file-side null, or an item with `Condition=" "` never matches.
        condition = e.condition?.let(::collapseWs)?.takeIf { it.isNotEmpty() },
        groupCondition = e.groupCondition?.let(::collapseWs)?.takeIf { it.isNotEmpty() },
        expected = e.expected?.let(::normalizeNl),
        value = normalizeNl(e.value),
    )

    private fun setAttribute(xml: XmlScan, e: Edit): Splice {
        val candidates = items(xml, e).filter { it.attrCI(e.name)?.value?.trim() == e.expected?.trim() }
        val tag = pick(xml, e, candidates)
        val a = tag.attrCI(e.name) ?: throw Fail("file changed - re-scan")
        return Splice(a.valueStart, a.valueEnd, XmlScan.encode(e.value))
    }

    private fun setChild(xml: XmlScan, e: Edit): Splice {
        val found = items(xml, e).mapNotNull { tag -> xml.childText(tag, e.name)?.let { tag to it } }
            .filter { (_, range) -> XmlScan.decode(xml.text.substring(range.first, range.last + 1)).trim() == e.expected?.trim() }
        val (_, range) = pickPair(xml, e, found)
        return Splice(range.first, range.last + 1, XmlScan.encode(e.value.trim()))
    }

    private fun setProperty(xml: XmlScan, e: Edit): Splice {
        val found = xml.tags
            .filter {
                it.name == e.name && !it.selfClosing &&
                    conditionOf(it) == e.condition && groupConditionOf(xml, it) == e.groupCondition &&
                    // A property inside <Target><PropertyGroup> is not static - only a PropertyGroup
                    // that is itself a direct child of <Project> counts.
                    groupOf(xml, it)?.let { g -> g.name == "PropertyGroup" && isTopLevelGroup(xml, g) } == true
            }
            .mapNotNull { tag -> xml.innerText(tag)?.let { tag to it } }
            .filter { (_, range) -> XmlScan.decode(xml.text.substring(range.first, range.last + 1)).trim() == e.expected?.trim() }
        val (_, range) = pickPair(xml, e, found)
        return Splice(range.first, range.last + 1, XmlScan.encode(e.value.trim()))
    }

    private fun setDirective(text: String, e: Edit): Splice {
        val pattern = Regex("^#:package\\s+${Regex.escape(e.name)}@(\\S+)\\s*$", RegexOption.MULTILINE)
        val matches = pattern.findAll(text).filter { it.groupValues[1] == e.expected }.toList()
        if (matches.isEmpty()) throw Fail("file changed - re-scan")
        val m = matches.minBy { abs(lineOf(text, it.range.first) - e.line) }
        val group = m.groups[1]!!
        return Splice(group.range.first, group.range.last + 1, e.value)
    }

    private sealed class InsertOutcome {
        data class Spliced(val splice: Splice) : InsertOutcome()
        data object NeedsNewGroup : InsertOutcome()
    }

    private fun planInserts(xml: XmlScan, inserts: List<Edit>): List<Splice> {
        if (inserts.isEmpty()) return emptyList()
        val result = ArrayList<Splice>()
        val newGroupItems = ArrayList<Edit>()
        for (edit in inserts) {
            when (val outcome = insertItem(xml, edit)) {
                null -> {} // an unconditioned element with this id already exists (for example, from another TFM's insert)
                is InsertOutcome.Spliced -> result += outcome.splice
                InsertOutcome.NeedsNewGroup -> newGroupItems += edit
            }
        }
        // Two or more inserts that all need a brand new group go into ONE new group, in input
        // order, instead of one new <ItemGroup> per edit next to the others.
        if (newGroupItems.isNotEmpty()) result += newGroupSplice(xml, newGroupItems)
        return result
    }

    /** null: an unconditioned element with this id is already there (in a top-level ItemGroup). */
    private fun insertItem(xml: XmlScan, e: Edit): InsertOutcome? {
        val type = e.itemType ?: throw Fail("insert without item type")
        val id = e.identity ?: throw Fail("insert without id")
        val unconditioned = topLevelUnconditioned(xml, type)
        if (unconditioned.any { it.attr("Include")?.value.equals(id, ignoreCase = true) }) return null

        val element = elementText(type, id, e)
        val anchor = unconditioned.firstOrNull() ?: return InsertOutcome.NeedsNewGroup
        val lineStart = xml.lineStart(anchor.start)
        val prefix = xml.text.substring(lineStart, anchor.start)
        val splice = if (prefix.isBlank()) Splice(lineStart, lineStart, "$prefix$element\n")
        else Splice(anchor.start, anchor.start, "$element ")
        return InsertOutcome.Spliced(splice)
    }

    /** Items of [type] sitting in an unconditioned ItemGroup that is itself a DIRECT child of `<Project>` - a Target/Choose/When's ItemGroup never qualifies. */
    private fun topLevelUnconditioned(xml: XmlScan, type: String): List<XmlScan.Tag> = xml.tags.filter {
        it.name == type && it.attr("Condition") == null &&
            groupOf(xml, it)?.let { g -> g.name == "ItemGroup" && g.attr("Condition") == null && isTopLevelGroup(xml, g) } == true
    }

    /** True when [g] is a direct child of the document's `<Project>` root - not nested in a Target/Choose/When. */
    private fun isTopLevelGroup(xml: XmlScan, g: XmlScan.Tag): Boolean = xml.parentOf(g)?.name == "Project"

    private fun elementText(type: String, id: String, e: Edit): String = buildString {
        append("<").append(type).append(" Include=\"").append(XmlScan.encode(id)).append("\"")
        if (e.name.isNotEmpty() && e.value.isNotEmpty()) append(" ").append(e.name).append("=\"").append(XmlScan.encode(e.value)).append("\"")
        append(" />")
    }

    private fun newGroupSplice(xml: XmlScan, edits: List<Edit>): Splice {
        val close = xml.masked.lastIndexOf("</Project>")
        if (close < 0) throw Fail("no </Project> in the file")
        val indent = xml.indentUnit()
        val items = edits.joinToString("\n") { e ->
            "$indent$indent${elementText(e.itemType ?: throw Fail("insert without item type"), e.identity ?: throw Fail("insert without id"), e)}"
        }
        val group = "$indent<ItemGroup>\n$items\n$indent</ItemGroup>\n"
        val lineStart = xml.lineStart(close)
        return if (xml.text.substring(lineStart, close).isBlank()) Splice(lineStart, lineStart, group)
        else Splice(close, close, "\n$group")
    }

    private fun items(xml: XmlScan, e: Edit): List<XmlScan.Tag> {
        val identityAttr = e.identityAttr ?: throw Fail("edit without identity")
        return xml.tags.filter {
            it.name == e.itemType &&
                it.attr(identityAttr)?.value == e.identity &&
                conditionOf(it) == e.condition &&
                groupConditionOf(xml, it) == e.groupCondition
        }
    }

    private fun pick(xml: XmlScan, e: Edit, candidates: List<XmlScan.Tag>): XmlScan.Tag =
        pickPair(xml, e, candidates.map { it to Unit }).first

    private fun <T> pickPair(xml: XmlScan, e: Edit, candidates: List<Pair<XmlScan.Tag, T>>): Pair<XmlScan.Tag, T> {
        if (candidates.isEmpty()) throw Fail("file changed - re-scan")
        return candidates.minWith(compareBy({ abs(xml.lineOf(it.first.start) - e.line) }, { abs(xml.columnOf(it.first.start) - e.column) }))
    }

    private fun conditionOf(tag: XmlScan.Tag): String? = tag.attr("Condition")?.value?.let(::collapseWs)?.takeIf { it.isNotEmpty() }

    /** The tag's direct parent element, following real nesting (open/close counted) - not just the nearest preceding group by text offset. */
    private fun groupOf(xml: XmlScan, tag: XmlScan.Tag): XmlScan.Tag? = xml.parentOf(tag)

    private fun groupConditionOf(xml: XmlScan, tag: XmlScan.Tag): String? = groupOf(xml, tag)?.let(::conditionOf)

    private fun lineOf(text: String, offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1
}

/** Minimal scanner for MSBuild XML: start tags with attribute offsets, comments masked, and real parent linkage. */
internal class XmlScan(val text: String) {

    data class Attr(val name: String, val value: String, val valueStart: Int, val valueEnd: Int)

    data class Tag(val name: String, val start: Int, val end: Int, val selfClosing: Boolean, val attrs: List<Attr>) {
        fun attr(name: String): Attr? = attrs.firstOrNull { it.name == name }
        fun attrCI(name: String): Attr? = attrs.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** Same length as [text]; comments and CDATA are spaces (newlines kept). */
    val masked: String = MASK.replace(text) { m -> m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }

    val tags: List<Tag> = parseTags()

    /** Each tag's direct parent, computed with a real open/close stack - respects Target/Choose/When nesting and ignores a group that has already closed. */
    private val parents: Map<Tag, Tag?> = computeParents()

    fun parentOf(tag: Tag): Tag? = parents[tag]

    fun lineStart(offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1
    fun lineOf(offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1
    fun columnOf(offset: Int): Int = offset - lineStart(offset) + 1

    /** Trimmed inner text of `<Name>...</Name>` (no child elements), as an inclusive range. */
    fun innerText(tag: Tag): IntRange? {
        val close = masked.indexOf("</${tag.name}", tag.end)
        if (close < 0) return null
        val inner = masked.substring(tag.end, close)
        if (inner.contains('<')) return null
        return trimmed(tag.end, close)
    }

    /** Trimmed text of the child `<childName>` inside [tag] (metadata names are matched case-insensitively). */
    fun childText(tag: Tag, childName: String): IntRange? {
        if (tag.selfClosing) return null
        val close = masked.indexOf("</${tag.name}", tag.end)
        if (close < 0) return null
        val child = tags.firstOrNull { it.name.equals(childName, ignoreCase = true) && it.start >= tag.end && it.start < close && !it.selfClosing } ?: return null
        return innerText(child)
    }

    fun indentUnit(): String =
        Regex("^([ \\t]+)<", RegexOption.MULTILINE).find(masked)?.groupValues?.get(1) ?: "  "

    private fun trimmed(from: Int, to: Int): IntRange {
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return s until e
    }

    private fun parseTags(): List<Tag> {
        val result = ArrayList<Tag>()
        var i = masked.indexOf('<')
        while (i >= 0 && i < masked.length - 1) {
            val next = masked[i + 1]
            if (next.isLetter() || next == '_') {
                var j = i + 1
                while (j < masked.length && (masked[j].isLetterOrDigit() || masked[j] in "_.:-")) j++
                val name = masked.substring(i + 1, j)
                var k = j
                var quote: Char? = null
                while (k < masked.length) {
                    val c = masked[k]
                    if (quote != null) { if (c == quote) quote = null } else if (c == '"' || c == '\'') quote = c else if (c == '>') break
                    k++
                }
                if (k >= masked.length) break
                val selfClosing = masked[k - 1] == '/'
                result += Tag(name, i, k + 1, selfClosing, attrs(j, k))
                i = masked.indexOf('<', k + 1)
            } else {
                i = masked.indexOf('<', i + 1)
            }
        }
        return result
    }

    /** Direct-parent linkage via a real open/close stack over the whole document, matched by tag name. */
    private fun computeParents(): Map<Tag, Tag?> {
        val byStart = tags.associateBy { it.start }
        val stack = ArrayDeque<Tag>()
        val result = HashMap<Tag, Tag?>()
        var i = masked.indexOf('<')
        while (i >= 0 && i < masked.length - 1) {
            val open = byStart[i]
            if (open != null) {
                result[open] = stack.lastOrNull()
                if (!open.selfClosing) stack.addLast(open)
                i = masked.indexOf('<', open.end)
                continue
            }
            val close = CLOSE.matchAt(masked, i)
            if (close != null) {
                val name = close.groupValues[1]
                val depth = stack.indexOfLast { it.name == name }
                if (depth >= 0) while (stack.size > depth) stack.removeLast()
                i = masked.indexOf('<', i + close.value.length)
                continue
            }
            i = masked.indexOf('<', i + 1)
        }
        return result
    }

    private fun attrs(from: Int, to: Int): List<Attr> =
        ATTR.findAll(masked.substring(from, to)).map { m ->
            val group = m.groups[3] ?: m.groups[4]!!
            val start = from + group.range.first
            val end = from + group.range.last + 1
            Attr(m.groupValues[1], decode(text.substring(start, end)), start, end)
        }.toList()

    companion object {
        private val MASK = Regex("<!--[\\s\\S]*?-->|<!\\[CDATA\\[[\\s\\S]*?]]>")
        private val ATTR = Regex("([A-Za-z_][\\w.:-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
        private val CLOSE = Regex("</\\s*([A-Za-z_][\\w.:-]*)\\s*>")

        /**
         * XML entity decode: the five named entities plus numeric character references (decimal
         * `&#NN;` and hex `&#xHH;`). MSBuild hands the engine decoded text (Condition, identity,
         * expected values), so every comparison against the file's raw XML must go through this
         * first, or a merely-escaped-differently file reads as "changed" when it has not.
         */
        fun decode(s: String): String {
            if (s.indexOf('&') < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '&') {
                    val semi = s.indexOf(';', i + 1)
                    if (semi > i) {
                        val entity = s.substring(i + 1, semi)
                        val replacement = when {
                            entity == "quot" -> "\""
                            entity == "apos" -> "'"
                            entity == "lt" -> "<"
                            entity == "gt" -> ">"
                            entity == "amp" -> "&"
                            entity.startsWith("#x") || entity.startsWith("#X") ->
                                entity.substring(2).toIntOrNull(16)?.let { codePointOrNull(it) }
                            entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let { codePointOrNull(it) }
                            else -> null
                        }
                        if (replacement != null) {
                            sb.append(replacement)
                            i = semi + 1
                            continue
                        }
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        private fun codePointOrNull(codePoint: Int): String? =
            if (Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else null

        fun encode(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
    }
}
