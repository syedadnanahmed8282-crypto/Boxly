package com.boxly.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Router: a generated app contains assets/boxly_runner and opens the WebView runner; the original app opens the builder. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isRunner = try { assets.open("boxly_runner").close(); true } catch (e: Exception) { false }
        startActivity(Intent(this, if (isRunner) RunnerActivity::class.java else BuilderActivity::class.java))
        finish()
    }
}
