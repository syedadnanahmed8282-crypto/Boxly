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
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File

/** Runs the user's HTML/CSS/JS (assets/www) and exposes a small native bridge as window.Android. */
class RunnerActivity : Activity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var camFile: File? = null
    private var pendingPerm: PermissionRequest? = null
    private var saveBytes: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
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
                    if (r.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
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
        web.addJavascriptInterface(NativeBridge(), "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
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

    inner class NativeBridge {
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
}
