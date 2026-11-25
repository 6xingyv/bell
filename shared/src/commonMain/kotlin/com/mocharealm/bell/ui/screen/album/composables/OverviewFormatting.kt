// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.album.composables

/** HTML parsers collapse source newlines, including paragraph breaks in plain overviews. */
internal fun musicOverviewHtml(value: String): String {
    val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
    val tags = Regex("</?[a-zA-Z][^>]*>")
    if (!tags.containsMatchIn(normalized)) {
        return normalized
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br />")
    }

    // Formatting whitespace next to an HTML paragraph or explicit break is already represented.
    val blockEnd =
        Regex(
            "(?:</?(?:p|div|h[1-6]|ul|ol|li|blockquote)[^>]*>|<br\\s*/?>)[ \\t]*$",
            RegexOption.IGNORE_CASE,
        )
    val blockStart =
        Regex("^[ \\t]*</?(?:p|div|h[1-6]|ul|ol|li|blockquote|br)\\b", RegexOption.IGNORE_CASE)
    return Regex("\n[ \\t\n]*").replace(normalized) { match ->
        val before = normalized.substring(0, match.range.first)
        val after = normalized.substring(match.range.last + 1)
        if (blockEnd.containsMatchIn(before) || blockStart.containsMatchIn(after)) ""
        else "<br />".repeat(match.value.count { it == '\n' })
    }
}
