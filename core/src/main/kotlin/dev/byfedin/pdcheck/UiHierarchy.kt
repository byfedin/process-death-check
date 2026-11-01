package dev.byfedin.pdcheck

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

public data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int
        get() = (left + right) / 2

    val centerY: Int
        get() = (top + bottom) / 2

    val isEmpty: Boolean
        get() = right <= left || bottom <= top
}

/** A node from `uiautomator dump`. Compose exposes the same attributes through semantics. */
public data class UiNode(
    val text: String,
    val resourceId: String,
    val className: String,
    val packageName: String,
    val contentDescription: String,
    val checkable: Boolean,
    val checked: Boolean,
    val clickable: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
    val selected: Boolean,
    val password: Boolean,
    val bounds: Bounds,
    val children: List<UiNode>,
) {
    public fun flatten(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.flatten()) }
    }

    /**
     * Own text or description, else the first text inside. Compose rows built with
     * `Modifier.toggleable` keep the visible label in a child node.
     */
    public val descriptiveLabel: String
        get() =
            listOf(text, contentDescription).firstOrNull { it.isNotBlank() }
                ?: flatten().drop(1).map { it.text }.firstOrNull { it.isNotBlank() }
                ?: ""

    /** Short human-readable label used in reports. */
    public val label: String
        get() =
            listOf(text, contentDescription, resourceId.substringAfter(":id/")).firstOrNull {
                it.isNotBlank()
            } ?: className.substringAfterLast('.')
}

public object UiHierarchy {
    private val boundsPattern = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    /** Returns the root node, or null when the dump has no window yet. */
    public fun parse(xml: String): UiNode? {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val nodes = document.documentElement.childElements().filter { it.tagName == "node" }
        if (nodes.isEmpty()) return null
        if (nodes.size == 1) return nodes.single().toNode()
        // Several windows (e.g. an IME on top): wrap them so callers see one tree.
        return UiNode(
            text = "",
            resourceId = "",
            className = "hierarchy",
            packageName = "",
            contentDescription = "",
            checkable = false,
            checked = false,
            clickable = false,
            enabled = true,
            scrollable = false,
            selected = false,
            password = false,
            bounds = Bounds(0, 0, 0, 0),
            children = nodes.map { it.toNode() },
        )
    }

    private fun Element.toNode(): UiNode =
        UiNode(
            text = getAttribute("text"),
            resourceId = getAttribute("resource-id"),
            className = getAttribute("class"),
            packageName = getAttribute("package"),
            contentDescription = getAttribute("content-desc"),
            checkable = getAttribute("checkable") == "true",
            checked = getAttribute("checked") == "true",
            clickable = getAttribute("clickable") == "true",
            enabled = getAttribute("enabled") != "false",
            scrollable = getAttribute("scrollable") == "true",
            selected = getAttribute("selected") == "true",
            password = getAttribute("password") == "true",
            bounds = parseBounds(getAttribute("bounds")),
            children = childElements().filter { it.tagName == "node" }.map { it.toNode() },
        )

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun parseBounds(value: String): Bounds {
        val match = boundsPattern.find(value) ?: return Bounds(0, 0, 0, 0)
        val (left, top, right, bottom) = match.destructured
        return Bounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }
}
