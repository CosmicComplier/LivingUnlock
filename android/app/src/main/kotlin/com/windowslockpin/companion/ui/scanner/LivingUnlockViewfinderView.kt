package com.windowslockpin.companion.ui.scanner

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import com.journeyapps.barcodescanner.ViewfinderView

/**
 * Custom Viewfinder for LivingUnlock scanner with ice-blue theme,
 * geometric corner brackets, and dynamic scanning laser animation.
 */
class LivingUnlockViewfinderView(context: Context, attrs: AttributeSet) : ViewfinderView(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private val cornerLength = 24f * density
    private val cornerStrokeWidth = 4f * density
    private val cornerColor = Color.parseColor("#00E5FF")
    private val borderColor = Color.parseColor("#3300E5FF")
    private val overlayMaskColor = Color.parseColor("#A6071324")
    private val laserColorInt = Color.parseColor("#00E5FF")

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cornerColor
        strokeWidth = cornerStrokeWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColor
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }

    private val customMaskPaint = Paint().apply {
        color = overlayMaskColor
        style = Paint.Style.FILL
    }

    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val laserLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = laserColorInt
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
    }

    private var laserY = 0f
    private var laserGoingDown = true
    private val laserSpeed = 3f * density

    override fun onDraw(canvas: Canvas) {
        refreshSizes()
        val frame = framingRect ?: return
        val width = width.toFloat()
        val height = height.toFloat()

        // 1. Draw outer shaded mask (Ice Blue / Deep Navy tint)
        val l = frame.left.toFloat()
        val t = frame.top.toFloat()
        val r = frame.right.toFloat()
        val b = frame.bottom.toFloat()

        canvas.drawRect(0f, 0f, width, t, customMaskPaint)
        canvas.drawRect(0f, t, l, b, customMaskPaint)
        canvas.drawRect(r, t, width, b, customMaskPaint)
        canvas.drawRect(0f, b, width, height, customMaskPaint)

        // 2. Draw subtle border around frame
        canvas.drawRect(l, t, r, b, borderPaint)

        // 3. Draw geometric corner brackets
        // Top-Left
        canvas.drawLine(l, t + cornerLength, l, t, cornerPaint)
        canvas.drawLine(l, t, l + cornerLength, t, cornerPaint)

        // Top-Right
        canvas.drawLine(r - cornerLength, t, r, t, cornerPaint)
        canvas.drawLine(r, t, r, t + cornerLength, cornerPaint)

        // Bottom-Left
        canvas.drawLine(l, b - cornerLength, l, b, cornerPaint)
        canvas.drawLine(l, b, l + cornerLength, b, cornerPaint)

        // Bottom-Right
        canvas.drawLine(r - cornerLength, b, r, b, cornerPaint)
        canvas.drawLine(r, b, r, b - cornerLength, cornerPaint)

        // 4. Draw laser sweep beam
        val frameHeight = b - t
        if (laserY < t || laserY > b) {
            laserY = t
            laserGoingDown = true
        } else {
            if (laserGoingDown) {
                laserY += laserSpeed
                if (laserY >= b) {
                    laserY = b
                    laserGoingDown = false
                }
            } else {
                laserY -= laserSpeed
                if (laserY <= t) {
                    laserY = t
                    laserGoingDown = true
                }
            }
        }

        // Draw sweeping laser gradient trail
        val trailHeight = 24f * density
        val trailTop = if (laserGoingDown) (laserY - trailHeight).coerceAtLeast(t) else laserY
        val trailBottom = if (laserGoingDown) laserY else (laserY + trailHeight).coerceAtMost(b)

        val shaderColors = if (laserGoingDown) {
            intArrayOf(Color.TRANSPARENT, Color.argb(40, 0, 229, 255), Color.argb(180, 0, 229, 255))
        } else {
            intArrayOf(Color.argb(180, 0, 229, 255), Color.argb(40, 0, 229, 255), Color.TRANSPARENT)
        }

        laserPaint.shader = LinearGradient(
            0f, trailTop, 0f, trailBottom,
            shaderColors, null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(l + 4f, trailTop, r - 4f, trailBottom, laserPaint)

        // Draw main laser line
        canvas.drawLine(l + 6f, laserY, r - 6f, laserY, laserLinePaint)

        // 5. Draw result points if detected
        val currentPossible = possibleResultPoints
        val currentLast = lastPossibleResultPoints
        if (currentPossible.isEmpty()) {
            lastPossibleResultPoints = null
        } else {
            possibleResultPoints = ArrayList(5)
            lastPossibleResultPoints = currentPossible
            cornerPaint.alpha = 200
            for (point in currentPossible) {
                canvas.drawCircle(point.x, point.y, 6f * density, cornerPaint)
            }
        }
        if (currentLast != null) {
            cornerPaint.alpha = 100
            for (point in currentLast) {
                canvas.drawCircle(point.x, point.y, 3f * density, cornerPaint)
            }
        }

        // Request next animation frame
        postInvalidateDelayed(16L, frame.left, frame.top, frame.right, frame.bottom)
    }
}
