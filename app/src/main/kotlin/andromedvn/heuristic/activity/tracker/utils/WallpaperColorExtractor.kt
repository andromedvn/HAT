package andromedvn.heuristic.activity.tracker.utils

import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WallpaperColorExtractor {
    suspend fun getWallpaperDominantColor(context: Context): Long? {
        return withContext(Dispatchers.IO) {
            try {
                if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.O_MR1) {
                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        return@withContext null
                    }
                }

                val wallpaperManager = WallpaperManager.getInstance(context)
                val drawable = wallpaperManager.drawable ?: return@withContext null
                
                // Safe Downsampling via Canvas Matrix
                val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, 50, 50)
                drawable.draw(canvas)

                try {
                    return@withContext calculateDominantColor(bitmap)
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun calculateDominantColor(bitmap: Bitmap): Long {
        var redSum = 0L; var greenSum = 0L; var blueSum = 0L; var pixelCount = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixel in pixels) {
            if (Color.alpha(pixel) < 128) continue
            redSum += Color.red(pixel); greenSum += Color.green(pixel); blueSum += Color.blue(pixel)
            pixelCount++
        }
        if (pixelCount == 0) return 0xFF808080

        val hsv = FloatArray(3)
        Color.RGBToHSV((redSum / pixelCount).toInt(), (greenSum / pixelCount).toInt(), (blueSum / pixelCount).toInt(), hsv)
        hsv[1] = (hsv[1] * 1.2f).coerceAtMost(1.0f) 
        return Color.HSVToColor(hsv).toLong()
    }
}
