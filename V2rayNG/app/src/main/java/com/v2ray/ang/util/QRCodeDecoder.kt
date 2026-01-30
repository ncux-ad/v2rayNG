package com.v2ray.ang.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import java.io.File
import java.io.FileOutputStream
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.util.EnumMap

/**
 * QR code decoder utility.
 */
object QRCodeDecoder {
    val HINTS: MutableMap<DecodeHintType, Any?> = EnumMap(DecodeHintType::class.java)

    /**
     * Creates a QR code bitmap from the given text.
     *
     * @param text The text to encode in the QR code.
     * @param size The size of the QR code bitmap.
     * @return The generated QR code bitmap, or null if an error occurs.
     */
    fun createQRCode(text: String, size: Int = 800): Bitmap? {
        return runCatching {
            val hints = mapOf(EncodeHintType.CHARACTER_SET to Charsets.UTF_8)
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size) { i ->
                if (bitMatrix.get(i % size, i / size)) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, size, 0, 0, size, size)
            }
        }.getOrNull()
    }

    /**
     * Decodes a QR code from a content URI (e.g. from file picker / Cx File Explorer on TV).
     * Copies the URI to a temp file and uses scaled decoding for reliability on TV and large images.
     * This method is time-consuming and should be called in a background thread.
     *
     * @param context Context for cache dir and content resolver.
     * @param uri Content URI of the image (from ACTION_GET_CONTENT).
     * @return The content of the QR code, or null if decoding fails.
     */
    fun syncDecodeQRCode(context: Context, uri: Uri): String? {
        // file:// from file managers (e.g. Cx File Explorer on TV) — decode by path
        if (uri.scheme == "file") {
            uri.path?.let { path -> return syncDecodeQRCode(path) }
            return null
        }
        val tempFile = File.createTempFile("qr_", ".img", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            syncDecodeQRCode(tempFile.absolutePath)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Decodes a QR code from a local image file. Tries multiple scales and binarizers for difficult images (e.g. JPEG artifacts).
     * This method is time-consuming and should be called in a background thread.
     *
     * @param picturePath The local path of the image file to decode.
     * @return The content of the QR code, or null if decoding fails.
     */
    fun syncDecodeQRCode(picturePath: String): String? {
        // Try default scale (height/400), then smaller scales to reduce JPEG noise
        for (forceSample in listOf(null, 2, 4)) {
            val bitmap = getDecodeAbleBitmap(picturePath, forceSample) ?: continue
            val text = syncDecodeQRCodeFromBitmap(bitmap)
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    /**
     * Decodes a QR code from a bitmap. This method is time-consuming and should be called in a background thread.
     *
     * @param bitmap The bitmap to decode.
     * @return The content of the QR code, or null if decoding fails.
     */
    fun syncDecodeQRCode(bitmap: Bitmap?): String? {
        return bitmap?.let { syncDecodeQRCodeFromBitmap(it) }
    }

    /**
     * Tries GlobalHistogramBinarizer then HybridBinarizer (and inverted) for difficult images.
     */
    private fun syncDecodeQRCodeFromBitmap(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height).also { array ->
            bitmap.getPixels(array, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val qrReader = QRCodeReader()

        for (inverted in listOf(false, true)) {
            val src = if (inverted) source.invert() else source
            val text = runCatching {
                try {
                    qrReader.decode(BinaryBitmap(GlobalHistogramBinarizer(src)), HINTS).text
                } catch (e: NotFoundException) {
                    qrReader.decode(BinaryBitmap(HybridBinarizer(src)), HINTS).text
                }
            }.getOrNull()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    /**
     * Converts a local image file to a bitmap for QR decoding.
     *
     * @param picturePath The local path of the image file.
     * @param forceSampleSize If set, use this inSampleSize; otherwise use height/400.
     * @return The decoded bitmap, or null if an error occurs.
     */
    private fun getDecodeAbleBitmap(picturePath: String, forceSampleSize: Int? = null): Bitmap? {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(picturePath, options)
            val sampleSize = forceSampleSize ?: run {
                var s = options.outHeight / 400
                if (s <= 0) s = 1
                s
            }
            options.inSampleSize = sampleSize
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(picturePath, options)
        } catch (e: Exception) {
            null
        }
    }

    init {
        // Keep decoding hints focused on QR codes and enable TRY_HARDER + UTF-8 charset for better success rate.
        HINTS[DecodeHintType.TRY_HARDER] = true
        HINTS[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        HINTS[DecodeHintType.CHARACTER_SET] = Charsets.UTF_8.name()
    }
}
