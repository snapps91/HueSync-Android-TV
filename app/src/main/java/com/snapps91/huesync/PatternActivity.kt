package com.snapps91.huesync

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/** Full-screen deterministic source for checking left/right mapping and temporal response on the TV. */
class PatternActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val started = android.os.SystemClock.elapsedRealtime()
            private val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE)
            private val labels = arrayOf(English.LEFT, English.CENTER, English.RIGHT)

            override fun onDraw(canvas: Canvas) {
                val seconds = (android.os.SystemClock.elapsedRealtime() - started) / 1000f
                val phase = (seconds / 4).toInt() % 3
                for (i in 0..2) {
                    paint.color = colors[(i + phase) % 3]
                    canvas.drawRect(i * width / 3f, 0f, (i + 1) * width / 3f, height.toFloat(), paint)
                }
                paint.color = Color.WHITE
                paint.textSize = height / 24f
                paint.setShadowLayer(5f, 1f, 1f, Color.BLACK)
                paint.textAlign = Paint.Align.CENTER
                labels.forEachIndexed { i, label ->
                    canvas.drawText(label, (i + .5f) * width / 3f, height * .12f, paint)
                }
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = height / 32f
                canvas.drawText(English.HUESYNC_RGB_TEST_PATTERN_CHANGES_EVERY, width * .08f, height * .9f, paint)
                paint.clearShadowLayer()
                postInvalidateDelayed(33)
            }
        })
    }
}
