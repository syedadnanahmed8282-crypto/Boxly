package com.boxly.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

class BuilderActivity : Activity() {
    private lateinit var web: WebView
    private var zipFiles: Map<String, ByteArray>? = null
    private val prefs by lazy { getSharedPreferences("boxly", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.addJavascriptInterface(Bridge(), "Boxly")
        web.loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript("window.$code", null) }

    private fun apkFile(path: String): File? {
        val f = File(path).canonicalFile
        return if (f.path.startsWith(File(filesDir, "apks").canonicalPath) && f.exists()) f else null
    }

    private fun installApk(f: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun shareApk(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share APK"))
    }

    private fun readZip(uri: Uri): Map<String, ByteArray> {
        val raw = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(contentResolver.openInputStream(uri)!!.buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name.replace('\\', '/').trimStart('/')
                if (!e.isDirectory && !n.startsWith("__MACOSX/") && !n.contains("..") && !n.endsWith(".DS_Store")) {
                    val b = z.readBytes()
                    total += b.size
                    if (total > 80_000_000) throw IllegalStateException("ZIP is too large (max 80 MB)")
                    raw[n] = b
                }
                e = z.nextEntry
            }
        }
        val tops = raw.keys.map { it.substringBefore('/', "") }.toSet()
        val strip = if (!raw.containsKey("index.html") && tops.size == 1 && tops.first().isNotEmpty()) tops.first() + "/" else ""
        return raw.mapKeys { it.key.removePrefix(strip) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 1 && resultCode == RESULT_OK && uri != null) {
            Thread {
                try {
                    val files = readZip(uri)
                    zipFiles = files
                    var name = "project.zip"
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) }
                    }
                    val kb = files.values.sumOf { it.size.toLong() } / 1024
                    js("onZipPicked(${JSONObject.quote(name)}, ${files.size}, $kb, ${files.containsKey("index.html")})")
                } catch (e: Throwable) {
                    zipFiles = null
                    js("onZipError(${JSONObject.quote(e.message ?: "Could not read ZIP")})")
                }
            }.start()
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun pickZip() = runOnUiThread {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }, 1)
        }

        @JavascriptInterface
        fun build(name: String, json: String) {
            Thread {
                try {
                    val files: Map<String, ByteArray> = if (json == "zip") {
                        zipFiles ?: throw IllegalStateException("Choose a ZIP first")
                    } else {
                        val o = JSONObject(json)
                        o.keys().asSequence().associateWith { o.getString(it).toByteArray() }
                    }
                    if (!files.containsKey("index.html")) throw IllegalStateException("index.html was not found in your project")
                    val r = ApkBuilder.build(this@BuilderActivity, name, files) { step, pct -> js("onProgress($step,$pct)") }
                    val info = JSONObject().put("name", r.label).put("pkg", r.pkg).put("path", r.file.path)
                        .put("size", r.file.length()).put("time", System.currentTimeMillis())
                    val old = JSONArray(prefs.getString("h", "[]"))
                    val list = JSONArray().put(info)
                    for (i in 0 until minOf(old.length(), 19)) list.put(old.get(i))
                    prefs.edit().putString("h", list.toString()).apply()
                    js("onDone($info)")
                } catch (e: Throwable) {
                    js("onError(${JSONObject.quote(e.message ?: e.toString())})")
                }
            }.start()
        }

        @JavascriptInterface fun history(): String = prefs.getString("h", "[]") ?: "[]"
        @JavascriptInterface fun clearHistory() { prefs.edit().remove("h").apply() }
        @JavascriptInterface fun install(path: String) { apkFile(path)?.let { f -> runOnUiThread { installApk(f) } } }
        @JavascriptInterface fun share(path: String) { apkFile(path)?.let { f -> runOnUiThread { shareApk(f) } } }
    }
}
