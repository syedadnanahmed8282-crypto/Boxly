package com.boxly.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Runs either local files (assets/www, or in-memory files for Preview) or a remote website (assets/boxly_url).
 * Local mode gets the full `Android` JS bridge; website mode only gets a retry button (no native access for remote pages).
 */
class RunnerActivity : Activity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var camFile: File? = null
    private var pendingPerm: PermissionRequest? = null
    private var saveBytes: ByteArray? = null
    private var remoteUrl: String? = null

    private fun readAsset(name: String): String? = runCatching {
        assets.open(name).bufferedReader().use { it.readText().trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun serve(path: String): WebResourceResponse? {
        var p = path.trimStart('/')
        if (p.isEmpty() || p.endsWith("/")) p += "index.html"
        val b = PreviewStore.files[p]
            ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val ext = p.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val text = mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("xml")
        return WebResourceResponse(mime, if (text) "utf-8" else null, ByteArrayInputStream(b))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = intent.getBooleanExtra("preview", false)
        remoteUrl = intent.getStringExtra("url") ?: if (!preview) readAsset("boxly_url") else null

        web = WebView(this)
        setContentView(web)
        val lb = WebViewAssetLoader.Builder()
        if (preview) lb.addPathHandler("/assets/www/", WebViewAssetLoader.PathHandler { path -> serve(path) })
        else lb.addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
        val loader = lb.build()
        val checkJs = if (preview) readAsset("ui/check.js") else null

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        if (remoteUrl != null) CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setDownloadListener { url, _, _, _, _ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                if (checkJs != null) view.evaluateJavascript(checkJs, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                val scheme = u.scheme ?: ""
                if (remoteUrl != null && (scheme == "http" || scheme == "https")) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && remoteUrl != null) {
                    view.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "utf-8", null)
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val wantsImage = p.acceptTypes.isEmpty() || p.acceptTypes.any { it.isEmpty() || it.contains("image") }
                val initial = mutableListOf<Intent>()
                if (wantsImage) {
                    if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        val f = File(cacheDir, "cam").apply { mkdirs() }.let { File(it, "${System.currentTimeMillis()}.jpg") }
                        camFile = f
                        val uri = FileProvider.getUriForFile(this@RunnerActivity, "$packageName.fileprovider", f)
                        initial += Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    } else {
                        requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 10)
                    }
                }
                val intent = if (p.isCaptureEnabled && initial.isNotEmpty()) initial[0]
                else Intent.createChooser(p.createIntent(), "Choose").putExtra(Intent.EXTRA_INITIAL_INTENTS, initial.toTypedArray())
                @Suppress("DEPRECATION")
                startActivityForResult(intent, 1)
                return true
            }

            override fun onPermissionRequest(r: PermissionRequest) {
                runOnUiThread {
                    val originOk = remoteUrl == null || r.origin.host == Uri.parse(remoteUrl).host
                    if (originOk && r.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            r.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        } else {
                            pendingPerm = r
                            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 11)
                        }
                    } else r.deny()
                }
            }
        }

        if (remoteUrl != null) web.addJavascriptInterface(RetryBridge(), "Android")
        else web.addJavascriptInterface(NativeBridge(), "Android")
        web.loadUrl(remoteUrl ?: "https://appassets.androidplatform.net/assets/www/index.html")
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 11) {
            val r = pendingPerm; pendingPerm = null
            if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) r?.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else r?.deny()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            var result: Array<Uri>? = if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            val cf = camFile
            if (result == null && resultCode == RESULT_OK && cf != null && cf.length() > 0) {
                result = arrayOf(FileProvider.getUriForFile(this, "$packageName.fileprovider", cf))
            }
            fileCb?.onReceiveValue(result); fileCb = null; camFile = null
        } else if (requestCode == 2 && resultCode == RESULT_OK) {
            val uri = data?.data; val b = saveBytes
            if (uri != null && b != null) contentResolver.openOutputStream(uri)?.use { it.write(b) }
            saveBytes = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    inner class RetryBridge {
        @JavascriptInterface fun retry() { runOnUiThread { remoteUrl?.let { web.loadUrl(it) } } }
    }

    inner class NativeBridge {
        @JavascriptInterface fun copyText(text: String) {
            runOnUiThread {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Boxly", text))
                Toast.makeText(this@RunnerActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface fun toast(msg: String) { runOnUiThread { Toast.makeText(this@RunnerActivity, msg, Toast.LENGTH_SHORT).show() } }

        @Suppress("DEPRECATION")
        @JavascriptInterface fun vibrate(ms: Long) { (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(ms) }

        @JavascriptInterface fun showNotification(title: String, body: String) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("boxly", "Notifications", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(this@RunnerActivity, 0, Intent(this@RunnerActivity, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(System.currentTimeMillis().toInt(), Notification.Builder(this@RunnerActivity, "boxly")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
                .setContentIntent(pi).setAutoCancel(true).build())
        }

        @JavascriptInterface fun share(text: String) {
            runOnUiThread { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }
        }

        /** Save a file chosen by the user. `base64` is the file content. */
        @JavascriptInterface fun saveFile(name: String, mime: String, base64: String) {
            saveBytes = Base64.decode(base64, Base64.DEFAULT)
            runOnUiThread {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime).putExtra(Intent.EXTRA_TITLE, name), 2)
            }
        }
    }

    companion object {
        private const val OFFLINE_HTML = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<body style='margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;" +
            "font-family:sans-serif;background:#F7F3FF;color:#1E1B3A;text-align:center;padding:24px;box-sizing:border-box'>" +
            "<div style='font-size:22px;font-weight:800'>No connection</div><div style='opacity:.7'>Check your internet and try again.</div>" +
            "<button onclick='Android.retry()' style='border:0;border-radius:16px;padding:14px 28px;font-size:16px;font-weight:700;color:#fff;" +
            "background:linear-gradient(100deg,#6A44F0,#C92F78)'>Retry</button></body>"
    }
}
