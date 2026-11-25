package com.boxly.app

/** In-memory files used by "Preview" (served to the runner WebView). */
object PreviewStore {
    @Volatile var files: Map<String, ByteArray> = emptyMap()
}

/** Safe, low-risk fixes for pages that were not written mobile-first. */
object MobileFix {
    private val viewportRe = Regex("<meta[^>]+name\\s*=\\s*[\"']?viewport", RegexOption.IGNORE_CASE)
    private val headRe = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val htmlRe = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private const val VIEWPORT = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
    private const val STYLE = "<style id=\"boxly-fix\">html{-webkit-text-size-adjust:100%;text-size-adjust:100%}body{overflow-x:hidden}img,video,canvas,svg{max-width:100%}</style>"

    /** Returns the fixed files and how many HTML files were changed. */
    fun apply(files: Map<String, ByteArray>): Pair<Map<String, ByteArray>, Int> {
        var n = 0
        val out = LinkedHashMap<String, ByteArray>()
        for ((name, bytes) in files) {
            if (name.endsWith(".html", true) || name.endsWith(".htm", true)) {
                val (s, changed) = fix(String(bytes, Charsets.UTF_8))
                if (changed) n++
                out[name] = s.toByteArray(Charsets.UTF_8)
            } else out[name] = bytes
        }
        return out to n
    }

    private fun fix(src: String): Pair<String, Boolean> {
        if (src.contains("id=\"boxly-fix\"")) return src to false
        val inject = (if (viewportRe.containsMatchIn(src)) "" else VIEWPORT) + STYLE
        val head = headRe.find(src)
        val html = htmlRe.find(src)
        val out = when {
            head != null -> src.substring(0, head.range.last + 1) + inject + src.substring(head.range.last + 1)
            html != null -> src.substring(0, html.range.last + 1) + "<head>" + inject + "</head>" + src.substring(html.range.last + 1)
            else -> inject + src
        }
        return out to true
    }
}
