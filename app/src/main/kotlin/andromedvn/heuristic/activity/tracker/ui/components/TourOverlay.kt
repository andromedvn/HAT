package andromedvn.heuristic.activity.tracker.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import andromedvn.heuristic.activity.tracker.domain.TourStep
import andromedvn.heuristic.activity.tracker.domain.DashboardTourSteps

/**
 * The spotlight tour (2026-10-31, ratified against the three-prototype arena):
 * a dim scrim with a transparent rounded cutout around one real dashboard
 * element, and a step card docked at the bottom. The classic coach-mark
 * pattern from the research (TutorialView / VSpot school): the cutout is
 * punched with BlendMode.Clear inside an Offscreen graphics layer so the real
 * UI shows through the hole; taps inside the cutout advance (and the step
 * card's own controls always work); taps elsewhere do nothing.
 *
 * Targets register their root bounds with [tutorialTarget]; if a step's
 * target never registered (an empty list, a collapsed card) the scrim covers
 * the whole screen and the step card still shows - the user can Next past it,
 * exactly as TutorialView handles missing targets.
 */
@Stable
class TourRegistry {
    var bounds by mutableStateOf<Map<String, Rect>>(emptyMap())
        private set

    fun register(key: String, rect: Rect) {
        if (bounds[key] != rect) bounds = bounds + (key to rect)
    }

    fun unregister(key: String) {
        bounds = bounds - key
    }
}

/** Registers a composable's root bounds under [key] while it is on screen. */
fun Modifier.tutorialTarget(key: String, registry: TourRegistry): Modifier = composed {
    onGloballyPositioned { coords ->
        val rect = coords.boundsInRoot()
        if (!rect.isEmpty) registry.register(key, rect)
    }
}

/** The full-screen overlay for step [stepIndex] of [steps]. */
@Composable
fun DashboardTourOverlay(
    registry: TourRegistry,
    stepIndex: Int,
    steps: List<TourStep> = DashboardTourSteps.steps,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit
) {
    if (stepIndex !in steps.indices) return
    val step = steps[stepIndex]
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val cutoutInset = with(density) { 12.dp.toPx() }
    val cutoutRadius = with(density) { 16.dp.toPx() }
    val targetRect = registry.bounds[step.id]
    val cardAlpha by animateFloatAsState(targetValue = 1f, animationSpec = tween(200), label = "tourCard")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind { drawScrimWithCutout(targetRect, cutoutInset, cutoutRadius) }
            .pointerInput(stepIndex) {
                detectTapGestures { offset ->
                    val hit = targetRect?.inflate(cutoutInset)?.contains(offset) == true
                    if (hit) onNext()
                    // Taps on the dimmed area intentionally do nothing: the
                    // tour is DismissType.none per the research best practice,
                    // so an accidental tap cannot throw the user out of it.
                }
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .graphicsLayer { alpha = cardAlpha }
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, primary, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "TOUR 0${stepIndex + 1} / 0${steps.size}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = onSurface.copy(alpha = 0.55f)
                )
                Text(
                    "Skip tour",
                    fontSize = 14.sp,
                    color = onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSkip
                    )
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(step.copy, fontSize = 16.sp, lineHeight = 22.sp, color = onSurface.copy(alpha = 0.85f))
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (stepIndex > 0) {
                    Text(
                        "Back",
                        fontSize = 16.sp,
                        color = onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBack
                        )
                    )
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(primary.copy(alpha = 0.15f))
                        .border(1.dp, primary, RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onNext
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        when {
                            step.completesTour && step.opensLabelGaps -> "Open it"
                            stepIndex == steps.lastIndex -> "Done"
                            else -> "Next"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primary
                    )
                }
            }
        }
    }
}

/** Scrim with a transparent rounded cutout over the registered target bounds. */
private fun DrawScope.drawScrimWithCutout(targetRect: Rect?, insetPx: Float, radiusPx: Float) {
    drawRect(Color.Black.copy(alpha = 0.55f))
    if (targetRect == null || targetRect.isEmpty) return
    val topLeft = Offset(targetRect.left - insetPx, targetRect.top - insetPx)
    val size = Size(targetRect.width + insetPx * 2, targetRect.height + insetPx * 2)
    drawRoundRect(
        color = Color.Transparent,
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(radiusPx),
        blendMode = androidx.compose.ui.graphics.BlendMode.Clear
    )
}
