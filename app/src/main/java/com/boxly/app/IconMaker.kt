package com.boxly.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.Base64
import java.io.ByteArrayOutputStream

class IconAssets(val fg: ByteArray, val bg: ByteArray)

/** Turns a user's logo into adaptive-icon layers (432x432 PNGs) plus a small preview data URL. */
object IconMaker {
    private fun png(b: Bitmap): ByteArray {
        val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, o); return o.toByteArray()
    }

    fun make(src: Bitmap): Pair<IconAssets, String> {
        val size = 432
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val corners = listOf(
            src.getPixel(0, 0), src.getPixel(src.width - 1, 0),
            src.getPixel(0, src.height - 1), src.getPixel(src.width - 1, src.height - 1)
        )
        val opaque = corners.all { Color.alpha(it) >= 200 }

        val fg = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val safe = size * (if (opaque) 0.80f else 0.62f)
        val sc = minOf(safe / src.width, safe / src.height)
        val w = src.width * sc; val h = src.height * sc
        Canvas(fg).drawBitmap(src, null, RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), p)

        val bg = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bg)
        if (opaque) {
            c.drawColor(Color.rgb(corners.sumOf { Color.red(it) } / 4, corners.sumOf { Color.green(it) } / 4, corners.sumOf { Color.blue(it) } / 4))
        } else {
            p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF7C5CFF.toInt(), 0xFFE0457B.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
            p.shader = null
        }

        val pv = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val cv = Canvas(pv)
        cv.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, 160f, 160f), 46f, 46f, Path.Direction.CW) })
        cv.drawBitmap(bg, null, Rect(0, 0, 160, 160), p)
        cv.drawBitmap(fg, null, Rect(0, 0, 160, 160), p)
        val url = "data:image/png;base64," + Base64.encodeToString(png(pv), Base64.NO_WRAP)
        val out = IconAssets(png(fg), png(bg))
        fg.recycle(); bg.recycle(); pv.recycle()
        return out to url
    }
}
