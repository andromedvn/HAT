package andromedvn.heuristic.activity.tracker.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object AppIconLoader {
    private val cache = LruCache<String, ImageBitmap>(150)
    private val semaphore = Semaphore(4) 

    suspend fun loadIcon(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        
        return semaphore.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val drawable = pm.getApplicationIcon(packageName)
                    val bmp = downsampleDrawable(drawable)
                    val img = bmp.asImageBitmap()
                    cache.put(packageName, img)
                    img
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun downsampleDrawable(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val b = drawable.bitmap
            if (b.width <= 96 && b.height <= 96) return b
        }
        val targetSize = 96
        val bitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, targetSize, targetSize)
        drawable.draw(canvas)
        return bitmap
    }
}
