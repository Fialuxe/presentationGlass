package com.presentationglass.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

/**
 * Draws the whole glasses screen as one 600 x 600 image, so that the app, not the system UI
 * toolkit, decides where every pixel goes.
 *
 * The layout follows what is known about the Meta Ray-Ban Display:
 * - The screen covers about 14 x 14 degrees and sits below and to the right of the wearer's
 *   line of sight, in the right eye only. Content near the top-left corner is the shortest
 *   glance away, so the current slide is anchored there.
 * - The waveguide is additive: black pixels emit no light and look transparent, white pixels
 *   glow. A slide with a white background becomes a bright patch over the audience, and
 *   reviewers report eye strain from looking at the monocular image for long. Light slides are
 *   therefore shown with their lightness inverted (hue kept), so that the background
 *   disappears and only the content glows.
 * - The next slide is a secondary cue. It is drawn smaller and dimmer below the current one,
 *   so that it is readable on purpose but does not compete for attention.
 *
 * All numbers are in [Layout] so that they can be tuned in user studies.
 */
object SlideComposer {

  data class Layout(
      val size: Int = 600,
      /** Gap between the current and the next slide, in pixels. */
      val gap: Int = 14,
      /** Brightness of the next slide relative to the current one (0..1). */
      val nextBrightness: Float = 0.9f,
      /** Slides whose average lightness is above this are inverted (0..1). */
      val invertThreshold: Float = 0.5f,
      /** Overall brightness of the current slide (0..1). */
      val currentBrightness: Float = 1.0f,
  )

  val DEFAULT_LAYOUT = Layout()

  fun compose(
      current: Bitmap?,
      next: Bitmap?,
      showNext: Boolean,
      warning: String?,
      playing: Boolean = false,
      layout: Layout = DEFAULT_LAYOUT,
  ): Bitmap {
    val out = createBitmap(layout.size, layout.size)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK) // Black is transparent on the glasses.

    var top = 0f
    if (current != null) {
      val height = layout.size * current.height.toFloat() / current.width
      draw(canvas, current, RectF(0f, 0f, layout.size.toFloat(), height), layout.currentBrightness, layout)
      top = height + layout.gap
    }

    if (showNext && next != null) {
      val height = layout.size - top
      val width = height * next.width.toFloat() / next.height
      if (height > 0 && width > 0) {
        val w = minOf(width, layout.size.toFloat())
        val h = w * next.height / next.width
        draw(canvas, next, RectF(0f, top, w, top + h), layout.nextBrightness, layout)
      }
    }

    if (playing) drawPlaying(canvas, layout)
    if (warning != null) drawWarning(canvas, warning, layout)
    return out
  }

  /**
   * Pattern 2: the current slide as large as the screen allows, and below it only the next
   * slide's title in small grey text.
   *
   * The empty margins of the slide (the background colour around the content) are cut off before
   * scaling, so that the text on the slide becomes larger. Because of this, the scale can differ
   * from slide to slide.
   */
  fun composeFocus(
      current: Bitmap?,
      nextTitle: String?,
      warning: String?,
      playing: Boolean = false,
      layout: Layout = DEFAULT_LAYOUT,
      focus: FocusLayout = DEFAULT_FOCUS_LAYOUT,
  ): Bitmap {
    val out = createBitmap(layout.size, layout.size)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)

    // The title band is always reserved, so that the slide does not change size when the title
    // arrives with the final image.
    val warningBand = if (warning != null) focus.warningBand else 0
    val areaWidth = layout.size.toFloat()
    val areaHeight = (layout.size - focus.titleBand - warningBand).toFloat()

    if (current != null) {
      val src = contentBounds(current, focus)
      val scale = minOf(areaWidth / src.width(), areaHeight / src.height())
      val dst = RectF(0f, 0f, src.width() * scale, src.height() * scale)
      draw(canvas, current, dst, layout.currentBrightness, layout, src)
    }

    if (nextTitle != null) {
      val paint =
          TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = focus.titleColor
            textSize = focus.titleTextSize
          }
      val text = TextUtils.ellipsize(nextTitle, paint, areaWidth - 16f, TextUtils.TruncateAt.END)
      canvas.drawText(text, 0, text.length, 8f, areaHeight + focus.titleBaseline, paint)
    }

    if (playing) drawPlaying(canvas, layout)
    if (warning != null) drawWarning(canvas, warning, layout)
    return out
  }

  data class FocusLayout(
      /** Height kept below the slide for the next slide's title, in pixels. */
      val titleBand: Int = 48,
      /** Extra height kept when a warning is shown at the bottom. */
      val warningBand: Int = 40,
      val titleTextSize: Float = 30f,
      val titleBaseline: Float = 36f,
      /** Grey: readable, but dimmer than the slide. */
      val titleColor: Int = 0xFF8C8C8C.toInt(),
      /** A pixel differing from the background by more than this (0..255) counts as content. */
      val contentTolerance: Int = 40,
      /** Space kept around the content, as a fraction of the slide width. */
      val contentPadding: Float = 0.02f,
  )

  val DEFAULT_FOCUS_LAYOUT = FocusLayout()

  /**
   * The part of the slide that holds content: the slide without the margins of background colour.
   * The background colour is taken from the four corners. Returns the whole slide when nothing
   * stands out.
   */
  private fun contentBounds(slide: Bitmap, focus: FocusLayout): Rect {
    val full = Rect(0, 0, slide.width, slide.height)
    val sampleWidth = minOf(320, slide.width)
    val sampleHeight = (slide.height * sampleWidth / slide.width).coerceAtLeast(1)
    val sample = slide.scale(sampleWidth, sampleHeight)
    val pixels = IntArray(sampleWidth * sampleHeight)
    sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
    if (sample !== slide) sample.recycle()

    val corners =
        listOf(0, sampleWidth - 1, (sampleHeight - 1) * sampleWidth, pixels.size - 1).map { pixels[it] }
    val background = corners.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key

    var left = sampleWidth
    var top = sampleHeight
    var right = -1
    var bottom = -1
    for (y in 0 until sampleHeight) {
      for (x in 0 until sampleWidth) {
        if (differs(pixels[y * sampleWidth + x], background, focus.contentTolerance)) {
          if (x < left) left = x
          if (x > right) right = x
          if (y < top) top = y
          if (y > bottom) bottom = y
        }
      }
    }
    if (right < 0) return full

    val ratio = slide.width.toFloat() / sampleWidth
    val pad = slide.width * focus.contentPadding
    val bounds =
        Rect(
            (left * ratio - pad).toInt().coerceAtLeast(0),
            (top * ratio - pad).toInt().coerceAtLeast(0),
            ((right + 1) * ratio + pad).toInt().coerceAtMost(slide.width),
            ((bottom + 1) * ratio + pad).toInt().coerceAtMost(slide.height),
        )
    return if (bounds.width() > 0 && bounds.height() > 0) bounds else full
  }

  private fun differs(a: Int, b: Int, tolerance: Int): Boolean {
    val dr = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
    val dg = kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
    val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
    return maxOf(dr, dg, db) > tolerance
  }

  private fun draw(
      canvas: Canvas,
      slide: Bitmap,
      dst: RectF,
      brightness: Float,
      layout: Layout,
      src: Rect = Rect(0, 0, slide.width, slide.height),
  ) {
    val invert = averageLightness(slide) > layout.invertThreshold
    val paint =
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
          colorFilter = ColorMatrixColorFilter(colorMatrix(invert, brightness))
        }
    canvas.drawBitmap(slide, src, dst, paint)
  }

  /**
   * Lightness inversion that keeps hue: Y' = 1 - Y with the chroma unchanged. In RGB this is
   * C' = C + (1 - 2Y) for each channel, where Y = 0.299 R + 0.587 G + 0.114 B. Then the result
   * is scaled by [brightness].
   */
  private fun colorMatrix(invert: Boolean, brightness: Float): ColorMatrix {
    val matrix =
        if (invert) {
          val r = 2 * 0.299f
          val g = 2 * 0.587f
          val b = 2 * 0.114f
          ColorMatrix(
              floatArrayOf(
                  1 - r, -g, -b, 0f, 255f,
                  -r, 1 - g, -b, 0f, 255f,
                  -r, -g, 1 - b, 0f, 255f,
                  0f, 0f, 0f, 1f, 0f,
              )
          )
        } else {
          ColorMatrix()
        }
    matrix.postConcat(ColorMatrix().apply { setScale(brightness, brightness, brightness, 1f) })
    return matrix
  }

  /** Average lightness (0..1) from a small sample of pixels. */
  private fun averageLightness(bitmap: Bitmap): Float {
    val sample = bitmap.scale(32, 18)
    val pixels = IntArray(sample.width * sample.height)
    sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
    if (sample !== bitmap) sample.recycle()
    val total =
        pixels.sumOf { p ->
          val r = (p shr 16) and 0xFF
          val g = (p shr 8) and 0xFF
          val b = p and 0xFF
          (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        }
    return (total / pixels.size).toFloat()
  }

  /** A small "play" triangle at the top right: a video on this slide is playing on the PC. */
  private fun drawPlaying(canvas: Canvas, layout: Layout) {
    val size = 28f
    val right = layout.size - 8f
    val path =
        android.graphics.Path().apply {
          moveTo(right - size, 8f)
          lineTo(right, 8f + size / 2)
          lineTo(right - size, 8f + size)
          close()
        }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF40E080.toInt() }
    canvas.drawPath(path, paint)
  }

  private fun drawWarning(canvas: Canvas, warning: String, layout: Layout) {
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = 0xFFFFB020.toInt() // Amber: visible, and distinct from slide content.
          textSize = 30f
        }
    canvas.drawText(warning, 8f, layout.size - 12f, paint)
  }
}
