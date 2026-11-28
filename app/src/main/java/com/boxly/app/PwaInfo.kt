package com.boxly.app

import android.net.Uri
import org.json.JSONObject

/** Finds an app name and icon inside a web project (manifest.json, <link> tags, common file names). */
object PwaInfo {
    class Result(val name: String?, val iconPath: String?)

    private val manifestNames = listOf("manifest.json", "manifest.webmanifest", "site.webmanifest", "app.webmanifest")
    private val iconNames = listOf("apple-touch-icon.png", "icon.png", "logo.png", "favicon.png", "icon-512.png", "icon-192.png", "favicon.ico")
    private val linkRe = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)
            ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    /** Resolve [src] (as written in [base]) to a path inside the project. */
    private fun resolve(base: String, src: String): String {
        val s = Uri.decode(src.substringBefore('#').substringBefore('?').trim())
        val parts = ArrayList<String>()
        if (!s.startsWith("/")) base.split('/').dropLast(1).forEach { parts.add(it) }
        for (seg in s.trimStart('/').split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun maxSize(sizes: String): Int =
        sizes.split(' ').mapNotNull { it.substringBefore('x').toIntOrNull() }.maxOrNull() ?: 0

    fun inspect(files: Map<String, ByteArray>): Result {
        var name: String? = null
        var icon: String? = null
        val html = files["index.html"]?.toString(Charsets.UTF_8) ?: ""
        val links = linkRe.findAll(html).map { it.value }.toList()

        var mpath: String? = manifestNames.firstOrNull { files.containsKey(it) }
        if (mpath == null) {
            for (t in links) {
                if (attr(t, "rel")?.lowercase()?.contains("manifest") == true) {
                    val p = attr(t, "href")?.let { resolve("index.html", it) }
                    if (p != null && files.containsKey(p)) mpath = p
                    break
                }
            }
        }
        if (mpath != null) {
            val mp: String = mpath
            runCatching {
                val o = JSONObject(files[mp]!!.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
                val n = o.optString("name").trim()
                val sn = o.optString("short_name").trim()
                name = (if (n.isNotEmpty() && n.length <= 32) n else sn.ifEmpty { n }).takeIf { it.isNotEmpty() }
                val arr = o.optJSONArray("icons")
                var best = -1
                if (arr != null) for (i in 0 until arr.length()) {
                    val ic = arr.optJSONObject(i) ?: continue
                    val p = resolve(mp, ic.optString("src"))
                    if (!files.containsKey(p) || p.endsWith(".svg", true)) continue
                    val purpose = ic.optString("purpose")
                    var size = maxSize(ic.optString("sizes"))
                    if (purpose.contains("maskable") && !purpose.contains("any")) size -= 1
                    if (size > best) { best = size; icon = p }
                }
            }
        }

        if (icon == null) {
            var best = -1
            for (t in links) {
                val rel = attr(t, "rel")?.lowercase() ?: continue
                if (!rel.contains("icon")) continue
                val href = attr(t, "href") ?: continue
                val p = resolve("index.html", href)
                if (!files.containsKey(p) || p.endsWith(".svg", true)) continue
                val score = maxSize(attr(t, "sizes") ?: "") + if (rel.contains("apple")) 1000 else 0
                if (score > best) { best = score; icon = p }
            }
        }
        if (icon == null) icon = iconNames.firstOrNull { files.containsKey(it) }

        if (name == null) {
            val t = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()
            if (!t.isNullOrEmpty() && t.length <= 32) name = t
        }
        return Result(name, icon)
    }
}
