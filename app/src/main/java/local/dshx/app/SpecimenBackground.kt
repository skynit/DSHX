package local.dshx.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.AtomicFile
import java.io.File
import java.io.InputStream

/** 保存应用自己的图片副本，不依赖相册文件或临时读取授权。 */
internal class SpecimenBackground(private val root: File) {
    private val image get() = AtomicFile(File(root, "background.png"))

    fun load(): Bitmap? = if (image.baseFile.exists() || File(root, "background.png.bak").exists()) {
        image.openRead().use { BitmapFactory.decodeStream(it) ?: error("背景图已损坏，请重新选择或恢复默认。") }
    } else null

    fun save(input: InputStream): Bitmap {
        check(root.isDirectory || root.mkdirs()) { "无法保存背景图。" }
        val source = File.createTempFile("import-", ".image", root)
        try {
            source.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 20L * 1024 * 1024) { "请选择不超过 20 MB 的图片。" }
                    output.write(buffer, 0, count)
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片，请选择其他图片。" }
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight).toLong() > 2048L * inSampleSize) inSampleSize *= 2
            }
            val decoded = BitmapFactory.decodeFile(source.path, options)
                ?: error("无法读取这张图片，请选择其他图片。")
            val orientation = ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (bitmap !== decoded) decoded.recycle()
            val atomic = image
            val output = atomic.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存背景图。" }
                atomic.finishWrite(output)
            } catch (cause: Throwable) {
                atomic.failWrite(output)
                bitmap.recycle()
                throw cause
            }
            return bitmap
        } finally { source.delete() }
    }

    fun clear() {
        image.delete()
        check(!image.baseFile.exists() && !File(root, "background.png.bak").exists()) { "无法恢复默认背景图，请重试。" }
    }
}
