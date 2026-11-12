package io.github.xrisium.makudon.data

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import android.util.LruCache
import io.github.xrisium.makudon.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File

object AndroidImages {
    fun verify(bytes: ByteArray) {
        val info = ImageRules.inspect(bytes)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth != info.width || options.outHeight != info.height || options.outMimeType != info.format.mime) throw WorkspaceException(Failure.InvalidImage)
        options.inJustDecodeBounds = false
        options.inSampleSize = sample(options.outWidth, options.outHeight, 128)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw WorkspaceException(Failure.InvalidImage)
        bitmap.recycle()
    }
    private fun sample(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > target && sample < 1 shl 28) sample *= 2
        return sample
    }
    fun decode(file: File, target: Int, orientation: Int = 1): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1f, target.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.setOnPartialImageListener { false }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw WorkspaceException(Failure.InvalidImage)
        options.inSampleSize = sample(options.outWidth, options.outHeight, target)
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw WorkspaceException(Failure.InvalidImage)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
}

class ImageLoader(private val application: Application) : ComponentCallbacks2 {
    private val memory = object : LruCache<String, Bitmap>(minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val disk = ThumbnailDiskCache(File(application.cacheDir, "image-thumbnails"))
    private val decodeLock = Mutex()
    init { application.registerComponentCallbacks(this) }
    suspend fun load(info: ImageInfo, targetPixels: Int, original: suspend () -> File): Bitmap = withContext(Dispatchers.IO) {
        val size = when { targetPixels <= 128 -> 128; targetPixels <= 512 -> 512; else -> 1024 }
        val key = "${info.sha256}-$size-v1.png"
        memory.get(key)?.let { return@withContext it }
        val generation = disk.generation
        decodeLock.withLock {
            memory.get(key)?.let { return@withLock it }
            val cached = disk.read(key)?.let { data ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, options)
                if (options.outWidth !in 1..1024 || options.outHeight !in 1..1024) null
                else BitmapFactory.decodeByteArray(data, 0, data.size)
            }
            val bitmap = cached ?: original().let { file ->
                if (file.length() > ImageRules.MAX_BYTES || ImageRules.digest(AtomicDisk().read(file, ImageRules.MAX_BYTES)) != info.sha256) throw WorkspaceException(Failure.InvalidImage)
                AndroidImages.decode(file, size, info.orientation)
            }
            if (cached == null) {
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                disk.put(key, bytes, generation)
            }
            disk.current(generation) { memory.put(key, bitmap) }
            bitmap
        }
    }
    suspend fun full(expected: ImageInfo, original: suspend () -> File): Bitmap = withContext(Dispatchers.IO) { decodeLock.withLock {
        val file = original()
        val info = ImageRules.inspect(AtomicDisk().read(file, ImageRules.MAX_BYTES), expected.key)
        if (info != expected) throw WorkspaceException(Failure.InvalidImage)
        val pixels = kotlin.math.sqrt((Runtime.getRuntime().maxMemory() / 8 / 4).toDouble()).toInt().coerceIn(512, 4096)
        AndroidImages.decode(file, pixels, info.orientation)
    } }
    suspend fun bytes(): Long = withContext(Dispatchers.IO) { disk.bytes() }
    suspend fun clear(): Boolean = withContext(Dispatchers.IO) { disk.clear { memory.evictAll() } }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) { memory.evictAll() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() { memory.evictAll() }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    fun close() { memory.evictAll(); application.unregisterComponentCallbacks(this) }
}
