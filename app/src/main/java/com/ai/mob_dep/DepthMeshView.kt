package com.ai.mob_dep

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class DepthMeshView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.argb(80, 255, 255, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 30f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }

    private var depth: FloatArray? = null
    private var depthWidth = 0
    private var depthHeight = 0
    private var ratioMeters = 3f

    // Rotation and Zoom state
    private var rotationX = 25f
    private var rotationY = -35f
    private var scaleFactor = 1.0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = max(0.2f, minOf(scaleFactor, 5.0f))
            invalidate()
            return true
        }
    })

    fun setDepth(depth01: FloatArray, width: Int, height: Int, ratioMeters: Float) {
        this.depth = depth01
        this.depthWidth = width
        this.depthHeight = height
        this.ratioMeters = ratioMeters
        invalidate()
    }

    fun clear() {
        depth = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        
        if (scaleDetector.isInProgress) return true

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                
                rotationY += dx * 0.5f
                rotationX -= dy * 0.5f
                
                // Clamp rotationX to avoid flipping
                rotationX = max(-80f, minOf(rotationX, 80f))
                
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackgroundGrid(canvas)
        val values = depth
        if (values == null || depthWidth <= 0 || depthHeight <= 0) {
            drawPlaceholder(canvas)
            return
        }
        drawPointCloud(canvas, values)
    }

    private fun drawBackgroundGrid(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val step = max(36f, w / 9f)
        var x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, linePaint)
            x += step
        }
        var y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, linePaint)
            y += step
        }
    }

    private fun drawPlaceholder(canvas: Canvas) {
        val message = "3D depth point cloud (Touch to Rotate/Zoom)"
        val x = (width - textPaint.measureText(message)) / 2f
        canvas.drawText(message, x, height / 2f, textPaint)
    }

    private fun drawPointCloud(canvas: Canvas, values: FloatArray) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.5f
        
        // Dynamic step to keep performance okay
        val step = 10 
        
        val radX = Math.toRadians(rotationX.toDouble()).toFloat()
        val radY = Math.toRadians(rotationY.toDouble()).toFloat()

        for (y in 0 until depthHeight step step) {
            for (x in 0 until depthWidth step step) {
                val idx = y * depthWidth + x
                if (idx >= values.size) continue
                
                val z01 = values[idx].coerceIn(0f, 1f)
                
                // Normalized coordinates [-1, 1]
                val nx = (x.toFloat() / (depthWidth - 1).coerceAtLeast(1) - 0.5f) * 2f
                val ny = (y.toFloat() / (depthHeight - 1).coerceAtLeast(1) - 0.5f) * 2f
                val nz = (z01 - 0.5f) * 2f
                
                // Simple 3D Rotation
                // Rotate around Y
                var rx = nx * cos(radY) + nz * sin(radY)
                val ry = ny
                var rz = -nx * sin(radY) + nz * cos(radY)
                
                // Rotate around X
                val finalY = ry * cos(radX) - rz * sin(radX)
                val finalZ = ry * sin(radX) + rz * cos(radX)
                val finalX = rx
                
                // Projection with Scale
                val projScale = scaleFactor * (w * 0.4f)
                val px = cx + finalX * projScale
                val py = cy + finalY * projScale
                
                // Z-based sizing and coloring
                val radius = (1.5f + z01 * 3f) * scaleFactor
                pointPaint.color = colorForDepth(z01)
                canvas.drawCircle(px, py, radius, pointPaint)
            }
        }
        
        textPaint.textSize = 25f
        canvas.drawText("Z Range: 0 - ${"%.2f".format(ratioMeters)} m", 22f, 34f, textPaint)
        canvas.drawText("Rotate: Single touch | Zoom: Pinch", 22f, 70f, textPaint)
    }

    private fun colorForDepth(v: Float): Int {
        val t = v.coerceIn(0f, 1f)
        val r = (60 + 195 * t).toInt().coerceIn(0, 255)
        val g = (220 - 120 * t).toInt().coerceIn(0, 255)
        val b = (255 - 220 * t).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
