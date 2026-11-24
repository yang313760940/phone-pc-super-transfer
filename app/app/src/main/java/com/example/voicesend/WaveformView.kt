package com.example.voicesend

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * 实时波形：由外部 setLevel(0..1) 推入当前音量，内部维护滚动历史并绘成对称波形条。
 * 录音时用 MediaRecorder.maxAmplitude 驱动，营造"真的在录"的反馈。
 */
class WaveformView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    private val bars = FloatArray(28) { 0.06f }
    private var head = 0
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2563EB.toInt() // 波形蓝（accent）
        style = Paint.Style.FILL
    }
    private var running = false

    fun start() { running = true; invalidate() }
    fun stop() { running = false; bars.fill(0.06f); head = 0; invalidate() }

    /** level: 0..1 归一化音量 */
    fun pushLevel(level: Float) {
        // 提增益：sqrt 让轻声也有可见起伏；保留极小基线避免完全扁平
        val boosted = kotlin.math.sqrt(level.coerceIn(0f, 1f))
        bars[head] = max(0.12f, boosted)
        head = (head + 1) % bars.size
        phase += 0.5f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val n = bars.size
        val gap = w * 0.012f
        val bw = (w - gap * (n - 1)) / n
        val mid = h / 2f
        for (i in 0 until n) {
            // 从旧到新绘制，叠加一点正弦让它更"活"
            val idx = (head + i) % n
            val breathe = 0.85f + 0.15f * sin(phase + i * 0.5f)
            val amp = bars[idx] * breathe
            val bh = max(bw * 1.6f, h * amp)
            val x = i * (bw + gap)
            val top = mid - bh / 2f
            val r = bw / 2f
            canvas.drawRoundRect(x, top, x + bw, top + bh, r, r, paint)
        }
        if (running) postInvalidateOnAnimation()
    }
}
