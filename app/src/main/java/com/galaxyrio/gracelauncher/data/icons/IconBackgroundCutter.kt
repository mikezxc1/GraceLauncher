package com.galaxyrio.gracelauncher.data.icons

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.sqrt

/**
 * 把图标图片的底板抠掉，只留下中间那个图形。
 *
 * 为什么需要：自定义图片和图标包里的图，往往画在一块实心方/圆底上。
 * 桌面按钮背景已经是全透明的，可图片自己带底色，看起来还是实心一块。
 *
 * 三步：
 * 1. 在图里统计「出现次数最多的不透明颜色」，那个就是底板色。
 *    （不能用四角取色：这类图四周常有一圈透明留白，四角取到的是透明，抠不动。）
 * 2. 逐像素算它和底板色的距离：够近压成全透明，边缘一段做渐变免得锯齿。
 * 3. 只保留面积最大的连通图形，去掉零散小色点（比如图标角落的装饰点）。
 *
 * 保守起见：如果底板色占的比例太低（图里没有明显的大色块），说明这张图本来
 * 就不是「图形+纯色底」的结构，直接原样返回，不乱抠。
 */
internal object IconBackgroundCutter {

    /** 已经透明的像素跳过处理。 */
    private const val ALPHA_MIN = 8

    /** 判断「这个像素算图形」的透明度门槛。 */
    private const val SOLID_ALPHA = 32

    /** 底板色至少要占不透明像素的这个比例，否则认为没有底板。 */
    private const val MIN_BACKGROUND_SHARE = 0.25f

    /**
     * @param source 原始图片
     * @param tolerance 底板容差。0.16 表示色距在 16% 以内一律当底板。
     * @param keepLargestOnly 只留最大的连通图形，去掉小色点
     */
    fun cut(
        source: ImageBitmap,
        tolerance: Float = 0.16f,
        keepLargestOnly: Boolean = true,
    ): ImageBitmap = runCatching { cutUnsafe(source, tolerance, keepLargestOnly) }
        .getOrElse { source }

    private fun cutUnsafe(
        source: ImageBitmap,
        tolerance: Float,
        keepLargestOnly: Boolean,
    ): ImageBitmap {
        val src = source.asAndroidBitmap()
        val width = src.width
        val height = src.height
        if (width < 4 || height < 4) return source

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // ---- 第一步：统计出现最多的不透明颜色，当作底板色 ----
        // 用 5 位精度的直方图，避免把噪声像素算成不同颜色。
        val histogram = HashMap<Int, Int>()
        var opaqueCount = 0
        var index = 0
        while (index < pixels.size) {
            val color = pixels[index]
            val alpha = (color ushr 24) and 0xFF
            if (alpha > 200) {
                val key = (color shr 3) and 0x1FFC   // 丢掉低 3 位，聚色
                histogram[key] = (histogram[key] ?: 0) + 1
                opaqueCount++
            }
            index++
        }
        if (opaqueCount == 0) return source

        val entry = histogram.maxByOrNull { it.value } ?: return source
        val share = entry.value.toFloat() / opaqueCount
        if (share < MIN_BACKGROUND_SHARE) return source

        val bgR = ((entry.key shr 11) and 0x1F) shl 3
        val bgG = ((entry.key shr 6) and 0x1F) shl 3
        val bgB = (entry.key and 0x1F) shl 3

        // ---- 第二步：按色距压透明度 ----
        val near = tolerance * 255f
        val far = near * 1.9f
        val span = (far - near).coerceAtLeast(1f)

        for (i in pixels.indices) {
            val color = pixels[i]
            val alpha = (color ushr 24) and 0xFF
            if (alpha <= ALPHA_MIN) continue
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            val dr = (r - bgR).toFloat()
            val dg = (g - bgG).toFloat()
            val db = (b - bgB).toFloat()
            val distance = sqrt(dr * dr + dg * dg + db * db)

            val factor = when {
                distance <= near -> 0f
                distance >= far -> 1f
                else -> (distance - near) / span
            }
            val newAlpha = (alpha * factor).toInt().coerceIn(0, 255)
            pixels[i] = (newAlpha shl 24) or (r shl 16) or (g shl 8) or b
        }

        // ---- 第三步：只留最大连通块 ----
        if (keepLargestOnly) keepLargestComponent(pixels, width, height)

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out.asImageBitmap()
    }

    /**
     * 只保留面积最大的那块连成一片的图形，其余清成透明。
     *
     * 不删的话，图上零散的小色点（装饰点、水印残迹）会留下来，
     * 看起来像干净的图标上沾了脏东西。
     */
    private fun keepLargestComponent(pixels: IntArray, width: Int, height: Int) {
        val total = width * height
        // -1 未访问，-2 背景，>=0 连通块编号
        val label = IntArray(total) { -1 }
        val queue = IntArray(total)
        var component = 0
        var bestLabel = -1
        var bestSize = 0

        for (start in 0 until total) {
            if (label[start] != -1) continue
            if (((pixels[start] ushr 24) and 0xFF) <= SOLID_ALPHA) {
                label[start] = -2
                continue
            }

            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = component

            while (head < tail) {
                val current = queue[head++]
                val x = current % width
                val y = current / width
                for (direction in 0 until 4) {
                    val neighbour = when (direction) {
                        0 -> if (x > 0) current - 1 else -1
                        1 -> if (x < width - 1) current + 1 else -1
                        2 -> if (y > 0) current - width else -1
                        else -> if (y < height - 1) current + width else -1
                    }
                    if (neighbour < 0) continue
                    if (label[neighbour] != -1) continue
                    if (((pixels[neighbour] ushr 24) and 0xFF) <= SOLID_ALPHA) continue
                    label[neighbour] = component
                    queue[tail++] = neighbour
                }
            }

            if (tail > bestSize) {
                bestSize = tail
                bestLabel = component
            }
            component++
        }

        if (bestLabel < 0) return

        for (i in 0 until total) {
            val owner = label[i]
            if (owner >= 0 && owner != bestLabel) {
                // 只清透明度，颜色位留着
                pixels[i] = pixels[i] and 0x00FFFFFF
            }
        }
    }
}