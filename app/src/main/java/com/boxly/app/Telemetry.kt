package com.boxly.app

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Boxly builder app only (generated apps never call this).
 *  - register(): counts installs (device model, Android version, app version, random install id)
 *  - fetchConfig(): reads the remote "update required" popup settings
 *
 * Paste your Supabase Project URL and the anon / publishable key below.
 * NEVER put the service_role key in the app.
 */
object Telemetry {
    const val SUPABASE_URL = "https://qmoyhsjukudtknuzlgjh.supabase.co"
    const val SUPABASE_KEY = "sb_publishable_mXgfCx9A2twtEB85RITa5Q_JVJtOCyq"

    private val enabled: Boolean
        get() = SUPABASE_URL.startsWith("https://") && !SUPABASE_URL.contains("YOUR-") && !SUPABASE_KEY.contains("YOUR-")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("boxly", Context.MODE_PRIVATE)

    private fun installId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("install_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("install_id", it).apply() }
    }

    private fun pkgInfo(ctx: Context) = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    private fun versionCode(ctx: Context): Int = PackageInfoCompat.getLongVersionCode(pkgInfo(ctx)).toInt()

    private fun post(path: String, body: JSONObject): String {
        val c = URL("$SUPABASE_URL/rest/v1/rpc/$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 6000; c.readTimeout = 6000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("apikey", SUPABASE_KEY)
            if (SUPABASE_KEY.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun register(ctx: Context) {
        if (!enabled) return
        val app = ctx.applicationContext
        Thread {
            runCatching {
                post("register_install", JSONObject()
                    .put("p_id", installId(app))
                    .put("p_device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("p_android", Build.VERSION.RELEASE ?: "")
                    .put("p_version", pkgInfo(app).versionName ?: ""))
            }
        }.start()
    }

    /** Calls back with a clean JSON object string. Falls back to the last known config when offline. */
    fun fetchConfig(ctx: Context, cb: (String) -> Unit) {
        if (!enabled) return
        val app = ctx.applicationContext
        Thread {
            val json = try {
                val o = JSONObject(post("get_app_config", JSONObject().put("p_version", versionCode(app)).put("p_version_name", pkgInfo(app).versionName ?: "")))
                val clean = JSONObject()
                    .put("blocking", o.optBoolean("blocking"))
                    .put("title", o.optString("title"))
                    .put("message", o.optString("message"))
                    .put("contact_label", o.optString("contact_label"))
                    .put("contact_link", o.optString("contact_link"))
                    .toString()
                prefs(app).edit().putString("cfg", clean).apply()
                clean
            } catch (e: Throwable) {
                prefs(app).getString("cfg", null)
            }
            if (json != null) cb(json)
        }.start()
    }
}
