package andromedvn.heuristic.activity.tracker.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HatSplashScreen(themeColor: Color, onSplashComplete: () -> Unit) {
    val brimScaleX = remember { Animatable(0f) }
    val brimAlpha = remember { Animatable(0f) }
    val bar1ScaleY = remember { Animatable(0f) }
    val bar2ScaleY = remember { Animatable(0f) }
    val bar3ScaleY = remember { Animatable(0f) }
    val bar4ScaleY = remember { Animatable(0f) }
    val bar5ScaleY = remember { Animatable(0f) }
    
    // Original 2.6f translation downward perfectly closes the 2.6f arc gap
    val bar5UpperTranslateY = remember { Animatable(2.6f) } 
    var isSplit by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { brimScaleX.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
        launch { brimAlpha.animateTo(1f, tween(100, easing = LinearEasing)) }
        delay(200)
        launch { bar1ScaleY.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
        delay(70)
        launch { bar2ScaleY.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
        delay(70)
        launch { bar3ScaleY.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
        delay(70)
        launch { bar4ScaleY.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
        delay(70)
        launch { bar5ScaleY.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
        delay(340) 
        isSplit = true
        launch { bar5UpperTranslateY.animateTo(0f, tween(350, easing = FastOutSlowInEasing)) }
        delay(400) 
        onSplashComplete()
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(124.dp).scale(1.6f)) {
            val strokeW = 1.8.dp.toPx()
            val stroke = Stroke(width = strokeW)
            val rad = CornerRadius(2.2.dp.toPx(), 2.2.dp.toPx())
            val fill = themeColor.copy(alpha = 0.2f)

            val brimWidth = 55.2.dp.toPx() * brimScaleX.value
            val brimHeight = 4.2.dp.toPx()
            
            val transX = 60.4.dp.toPx() - brimWidth / 2
            val transY = 81.dp.toPx()
            
            if (brimAlpha.value > 0f && brimWidth > 0f) {
                withTransform({
                    translate(left = transX, top = transY)
                }) {
                    drawRoundRect(color = fill.copy(alpha = brimAlpha.value * 0.2f), size = Size(brimWidth, brimHeight), cornerRadius = CornerRadius(2.1.dp.toPx()))
                    drawRoundRect(color = themeColor.copy(alpha = brimAlpha.value), size = Size(brimWidth, brimHeight), cornerRadius = CornerRadius(2.1.dp.toPx()), style = stroke)
                }
            }

            fun DrawScope.drawBar(x: Float, y: Float, w: Float, h: Float, scaleY: Float, ty: Float = 0f) {
                if (scaleY > 0f) {
                    val currentH = h.dp.toPx() * scaleY
                    // Draw upwards from the bottom edge
                    val currentY = y.dp.toPx() + (h.dp.toPx() - currentH) + ty.dp.toPx()
                    drawRoundRect(color = fill, topLeft = Offset(x.dp.toPx(), currentY), size = Size(w.dp.toPx(), currentH), cornerRadius = rad)
                    drawRoundRect(color = themeColor, topLeft = Offset(x.dp.toPx(), currentY), size = Size(w.dp.toPx(), currentH), cornerRadius = rad, style = stroke)
                }
            }

            // FIXED: Y=38.8, H=38.8 perfectly accounts for the 2.2dp SVG arc radius bulge at the top and bottom.
            drawBar(39f, 38.8f, 4.4f, 38.8f, bar1ScaleY.value)
            drawBar(48.6f, 38.8f, 4.4f, 38.8f, bar2ScaleY.value)
            drawBar(58.2f, 38.8f, 4.4f, 38.8f, bar3ScaleY.value)
            drawBar(67.8f, 38.8f, 4.4f, 38.8f, bar4ScaleY.value)

            if (!isSplit) {
                // Unified bar: Starts shifted down to seamlessly close the 2.6 gap
                drawBar(77.4f, 41.4f, 4.4f, 36.2f, bar5ScaleY.value)
            } else {
                // Upper fragment: top arc at 38.8, bottom arc at 52.2 -> Height = 13.4
                drawBar(77.4f, 38.8f, 4.4f, 13.4f, 1f, bar5UpperTranslateY.value)
                // Lower fragment: top arc at 54.8, bottom arc at 77.6 -> Height = 22.8
                drawBar(77.4f, 54.8f, 4.4f, 22.8f, 1f)
            }
        }
    }
}
