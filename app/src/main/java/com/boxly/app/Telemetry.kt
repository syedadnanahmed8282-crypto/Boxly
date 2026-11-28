package com.boxly.app

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

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

    // One worker thread so register_install always runs before track_usage.
    private val io = Executors.newSingleThreadExecutor()

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
        io.execute {
            runCatching {
                post("register_install", JSONObject()
                    .put("p_id", installId(app))
                    .put("p_device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("p_android", Build.VERSION.RELEASE ?: "")
                    .put("p_version", pkgInfo(app).versionName ?: ""))
            }
        }
    }

    /** Adds foreground seconds (and optionally one "open") to today's usage row for this install. */
    fun usage(ctx: Context, seconds: Int, open: Boolean) {
        if (!enabled) return
        val app = ctx.applicationContext
        io.execute {
            runCatching {
                post("track_usage", JSONObject().put("p_id", installId(app)).put("p_seconds", seconds).put("p_open", open))
            }
        }
    }

    private const val DEFAULT_MAX_OFFLINE_HOURS = 48

    /** True when the app has gone too long without a successful check (or the clock was turned back). */
    private fun offlineExpired(ctx: Context, cfg: JSONObject?): Boolean {
        val p = prefs(ctx)
        val limitH = cfg?.optInt("max_offline_hours", DEFAULT_MAX_OFFLINE_HOURS) ?: DEFAULT_MAX_OFFLINE_HOURS
        if (limitH <= 0) return false
        val ref = p.getLong("last_ok", 0L).takeIf { it > 0 } ?: p.getLong("first_run", 0L)
        if (ref <= 0) return false
        val elapsed = System.currentTimeMillis() - ref
        return elapsed < 0 || elapsed > limitH * 3_600_000L
    }

    /**
     * cb(json, ok): ok = true when Supabase answered.
     * Offline for longer than max_offline_hours => a blocking "turn on the internet" config.
     */
    fun fetchConfig(ctx: Context, cb: (String, Boolean) -> Unit) {
        if (!enabled) return
        val app = ctx.applicationContext
        val p = prefs(app)
        if (p.getLong("first_run", 0L) == 0L) p.edit().putLong("first_run", System.currentTimeMillis()).apply()
        Thread {
            try {
                val o = JSONObject(post("get_app_config", JSONObject().put("p_version", versionCode(app)).put("p_version_name", pkgInfo(app).versionName ?: "")))
                val clean = JSONObject()
                    .put("blocking", o.optBoolean("blocking"))
                    .put("title", o.optString("title"))
                    .put("message", o.optString("message"))
                    .put("contact_label", o.optString("contact_label"))
                    .put("contact_link", o.optString("contact_link"))
                    .put("max_offline_hours", o.optInt("max_offline_hours", DEFAULT_MAX_OFFLINE_HOURS))
                    .toString()
                p.edit().putString("cfg", clean).putLong("last_ok", System.currentTimeMillis()).apply()
                cb(clean, true)
            } catch (e: Throwable) {
                val cached = p.getString("cfg", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (offlineExpired(app, cached)) {
                    val j = JSONObject()
                        .put("blocking", true)
                        .put("title", "ইন্টারনেট চালু করুন")
                        .put("message", "চালিয়ে যেতে ইন্টারনেট দরকার। ইন্টারনেট চালু করে নিচের বোতাম চাপুন।")
                        .put("contact_label", "")
                        .put("contact_link", "")
                    cb(j.toString(), false)
                } else if (cached != null) {
                    cb(cached.toString(), false)
                }
            }
        }.start()
    }
}
