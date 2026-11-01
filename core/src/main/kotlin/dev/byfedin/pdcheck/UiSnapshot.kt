package dev.byfedin.pdcheck

/**
 * The user-visible state that should survive process death: text typed into fields and the state of
 * toggles. Scroll positions, loaded lists and timestamps are deliberately out of scope because apps
 * legitimately reload them.
 */
public data class UiSnapshot(val values: Map<String, String>) {

    public fun diff(after: UiSnapshot): List<StateDifference> =
        values.mapNotNull { (key, before) ->
            val restored = after.values[key]
            if (restored == before) null else StateDifference(key, before, restored)
        }

    public companion object {
        public fun capture(root: UiNode?, packageName: String): UiSnapshot {
            if (root == null) return UiSnapshot(emptyMap())
            val nodes = root.flatten().filter { it.packageName == packageName }.toList()
            val values = LinkedHashMap<String, String>()
            val fields = nodes.filter { it.isEditable && !it.password }
            fields.forEachIndexed { index, node -> values[node.key("field", index)] = node.text }
            val toggles = nodes.filter { it.checkable }
            toggles.forEachIndexed { index, node ->
                values[node.key("toggle", index)] = if (node.checked) "checked" else "unchecked"
            }
            // Selected tabs and chips; only selected ones, since "not selected" is the default.
            nodes
                .filter { it.selected && !it.checkable }
                .forEach {
                    values["selected:${it.descriptiveLabel.ifBlank { it.label }}"] = "selected"
                }
            return UiSnapshot(values)
        }

        private val UiNode.isEditable: Boolean
            get() = className.endsWith("EditText")

        private fun UiNode.key(kind: String, index: Int): String =
            when {
                resourceId.isNotBlank() -> "$kind:${resourceId.substringAfter(":id/")}"
                kind == "toggle" && descriptiveLabel.isNotBlank() -> "$kind:$descriptiveLabel"
                else -> "$kind#${index + 1}"
            }
    }
}

public data class StateDifference(val key: String, val before: String, val after: String?)
