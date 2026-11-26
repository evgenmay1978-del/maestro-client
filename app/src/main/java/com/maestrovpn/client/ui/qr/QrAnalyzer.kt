package com.maestrovpn.client.ui.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

// Анализатор кадров CameraX: ищет QR-код в Y-плоскости и отдаёт текст.
// После первого успешного декода останавливается (pause), чтобы не звонить
// обработчику на каждом кадре; resume() — когда пользователь просит снова.
class QrAnalyzer : ImageAnalysis.Analyzer {
    /** Куда отдать найденный текст. Вызывается из потока анализа. */
    var onDecoded: ((String) -> Unit)? = null

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf<DecodeHintType, Any>(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    @Volatile
    private var paused = false

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (paused) return
            val text = decode(image) ?: return
            paused = true
            onDecoded?.invoke(text)
        } catch (_: Throwable) {
            // Кадр не разобрался — это норма, ждём следующий.
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val width = image.width
        val height = image.height
        tryDecode(data, width, height)?.let { return it }
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return null
        val rotated = rotate(data, width, height, rotation)
        return tryDecode(rotated.data, rotated.width, rotated.height)
    }

    private fun tryDecode(data: ByteArray, width: Int, height: Int): String? = runCatching {
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()

    private class Frame(val data: ByteArray, val width: Int, val height: Int)

    private fun rotate(data: ByteArray, width: Int, height: Int, degrees: Int): Frame {
        val deg = ((degrees % 360) + 360) % 360
        if (deg == 0) return Frame(data, width, height)
        val swap = deg == 90 || deg == 270
        val outWidth = if (swap) height else width
        val outHeight = if (swap) width else height
        val out = ByteArray(data.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = data[y * width + x]
                val nx: Int
                val ny: Int
                when (deg) {
                    90 -> {
                        nx = height - 1 - y
                        ny = x
                    }
                    180 -> {
                        nx = width - 1 - x
                        ny = height - 1 - y
                    }
                    else -> {
                        nx = y
                        ny = width - 1 - x
                    }
                }
                out[ny * outWidth + nx] = value
            }
        }
        return Frame(out, outWidth, outHeight)
    }
}
