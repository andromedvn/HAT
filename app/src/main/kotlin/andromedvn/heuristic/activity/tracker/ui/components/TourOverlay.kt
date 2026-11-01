package andromedvn.heuristic.activity.tracker.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import andromedvn.heuristic.activity.tracker.domain.TourStep

/**
 * The spotlight tour (2026-11-01, smoothness pass): the cutout spotlight the
 * owner picked, the step card in the HeuristicTipCard anatomy, and every
 * motion animated - the cutout glides between targets, the card slides up
 * with each step, and the action buttons are quiet mono text in the corners
 * ("like the date font"), not boxed buttons ("so off putting").
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

/** The one registry every screen registers tour targets into. */
val AppTourRegistry = TourRegistry()

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

    // The cutout glides between targets instead of teleporting: animate the
    // hole's edges (or its collapse when a step has no registered target).
    val hasTarget = targetRect != null && !targetRect.isEmpty
    val animLeft by animateFloatAsState(
        targetValue = if (hasTarget) targetRect.left - cutoutInset else -1f,
        animationSpec = tween(320, easing = FastOutSlowInEasing), label = "cutL"
    )
    val animTop by animateFloatAsState(
        targetValue = if (hasTarget) targetRect.top - cutoutInset else -1f,
        animationSpec = tween(320, easing = FastOutSlowInEasing), label = "cutT"
    )
    val animRight by animateFloatAsState(
        targetValue = if (hasTarget) targetRect.right + cutoutInset else -1f,
        animationSpec = tween(320, easing = FastOutSlowInEasing), label = "cutR"
    )
    val animBottom by animateFloatAsState(
        targetValue = if (hasTarget) targetRect.bottom + cutoutInset else -1f,
        animationSpec = tween(320, easing = FastOutSlowInEasing), label = "cutB"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                drawScrimWithCutout(
                    hasCutout = hasTarget && animLeft >= 0f,
                    left = animLeft, top = animTop, right = animRight, bottom = animBottom,
                    radiusPx = cutoutRadius
                )
            }
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
        // The step card: HeuristicTipCard anatomy. No per-step motion: the
        // text swaps in place (owner: "no extra animations").
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
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
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
                                style = MaterialTheme.typography.labelMedium,
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
                        Spacer(modifier = Modifier.height(14.dp))
                        // Quiet mono text in the corners, like the date line:
                        // no boxes, no borders. The tour is a voice, not a form.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (globalIndex > 0) "Back" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = onSurface.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .clickableNoRipple { if (globalIndex > 0) onBack() }
                                    .padding(vertical = 8.dp)
                            )
                            Text(
                                if (globalIndex == globalCount - 1) "Done" else "Next",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = primary,
                                modifier = Modifier
                                    .clickableNoRipple { onNext() }
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
        }
    }
}

/** Scrim with a transparent rounded cutout over the (animated) target bounds. */
private fun DrawScope.drawScrimWithCutout(
    hasCutout: Boolean,
    left: Float, top: Float, right: Float, bottom: Float,
    radiusPx: Float
) {
    drawRect(Color.Black.copy(alpha = 0.55f))
    if (!hasCutout) return
    drawRoundRect(
        color = Color.Transparent,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
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
