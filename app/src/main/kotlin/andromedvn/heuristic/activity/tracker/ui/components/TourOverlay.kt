package andromedvn.heuristic.activity.tracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import andromedvn.heuristic.activity.tracker.domain.TourStep

/**
 * The spotlight tour (2026-11-01): the cutout spotlight the owner picked, with
 * the step card rebuilt in the HeuristicTipCard anatomy - 1dp primary border,
 * 24dp radius, elevation 8, a 15%-tint header band with the Lightbulb icon and
 * a mono kicker, then the copy and the app's real button pair. The owner asked
 * for exactly this: "make it look like how the heuristic tip looks like".
 *
 * Touch model: a tap inside the cutout advances; taps and drags anywhere else
 * are deliberately NOT consumed, so the screen below stays scrollable.
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

/**
 * One tour step, shown only when [currentStep] is non-null (the host decides
 * from AppTourState). [globalIndex] and [globalCount] drive the TOUR 0N / 0M
 * kicker so the count reads continuously across screens.
 */
@Composable
fun TourStepOverlay(
    registry: TourRegistry,
    currentStep: TourStep?,
    globalIndex: Int,
    globalCount: Int,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit
) {
    if (currentStep == null) return
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val cutoutInset = with(density) { 12.dp.toPx() }
    val cutoutRadius = with(density) { 16.dp.toPx() }
    val targetRect = currentStep.targetKey?.let { registry.bounds[it] }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind { drawScrimWithCutout(targetRect, cutoutInset, cutoutRadius) }
            .pointerInput(currentStep.id, targetRect) {
                // Tap inside the cutout = advance. Everything else passes
                // through untouched so the screen below keeps scrolling.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val inCutout = targetRect?.inflate(cutoutInset)?.contains(down.position) == true
                    if (inCutout) {
                        val up = waitForUpOrCancellation()
                        if (up != null) onNext()
                    }
                }
            }
    ) {
        // The step card: HeuristicTipCard anatomy, docked at the bottom.
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .border(1.dp, primary, RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Box(modifier = Modifier.background(primary.copy(alpha = 0.15f))) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lightbulb, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "TOUR 0${globalIndex + 1} / 0$globalCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = primary
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "Skip tour",
                            style = MaterialTheme.typography.labelSmall,
                            color = onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.clickableNoRipple(onSkip)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        currentStep.copy,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (globalIndex > 0) {
                            PromptOutlineButton("Back") { onBack() }
                        }
                        DialogHighlightButton(
                            text = if (globalIndex == globalCount - 1) "Done" else "Next",
                            color = primary,
                            modifier = Modifier.weight(1f)
                        ) { onNext() }
                    }
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

/** Quiet text exit without the ripple (links only; actions use the real pills). */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}
