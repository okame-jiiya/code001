package com.okamejiiya.petcare.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

/** 写真の読み込み・切り抜き・圧縮 */
object Images {
    /** 選んだ写真を、長い辺が maxSide くらいになるよう縮めて読み込む（向きは撮ったときのまま直す） */
    fun decode(context: Context, uri: Uri, maxSide: Int = 2400): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()

    fun rotate(src: Bitmap, degrees: Int): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    /**
     * 画像の (left, top, width, height) の範囲を切り抜き、長い辺を maxSide 以下にする。
     */
    fun crop(src: Bitmap, left: Float, top: Float, width: Float, height: Float, maxSide: Int): Bitmap {
        val l = left.toInt().coerceIn(0, src.width - 1)
        val t = top.toInt().coerceIn(0, src.height - 1)
        val w = width.toInt().coerceIn(1, src.width - l)
        val h = height.toInt().coerceIn(1, src.height - t)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        val m = Matrix().apply { postScale(scale, scale) }
        return Bitmap.createBitmap(src, l, t, w, h, m, true)
    }

    /**
     * JPEG にする。maxBytes を超えるときは画質を下げ、それでも大きければ縮める。
     * （家族共有のクラウドは 1 件 1MB までなので、余裕をもって小さくする）
     */
    fun jpeg(bitmap: Bitmap, quality: Int, maxBytes: Int): ByteArray {
        var bmp = bitmap
        var q = quality
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            val bytes = out.toByteArray()
            if (bytes.size <= maxBytes || (q <= 30 && maxOf(bmp.width, bmp.height) <= 320)) return bytes
            if (q > 35) {
                q -= 10
            } else {
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.8f).toInt().coerceAtLeast(1), (bmp.height * 0.8f).toInt().coerceAtLeast(1), true)
            }
        }
    }
}
