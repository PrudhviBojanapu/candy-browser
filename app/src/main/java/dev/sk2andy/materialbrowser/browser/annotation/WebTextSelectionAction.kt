package dev.sk2andy.materialbrowser.browser.annotation

internal enum class WebTextSelectionAction(val maximumCharacters: Int = 8_000) {
    Search,
    Share,
    Highlight,
    Translate,
    ReadAloud(4_000),
}