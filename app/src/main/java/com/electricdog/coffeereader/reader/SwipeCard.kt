@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.electricdog.coffeereader.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.electricdog.coffeereader.R
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.sign
internal enum class SwipeAction(val label: Int, val color: Color, val strength: Float) {
    Bookmark(R.string.cr_adding_bookmark, Color(0xFF45B86B), 0.48f),
    Tag(R.string.cr_adding_tags, Color(0xFFFFA342), 0.24f),
    Settings(R.string.cr_limits_tags, Color(0xFFFFA342), 0.24f),
    DeleteSource(R.string.cr_delete_source, Color(0xFFEF5350), 0.48f),
    DeleteBookmark(R.string.cr_remove_bookmark, Color(0xFFEF5350), 0.48f),
}

// The owner keeps a single hint above the screen, never underneath a moving card.
internal data class SwipeHint(val owner: String, val action: SwipeAction)

@Composable
internal fun SwipeCard(
    key: String,
    rightAction: SwipeAction?,
    leftAction: SwipeAction?,
    onHint: (String, SwipeAction?) -> Unit,
    onRight: () -> Unit,
    onLeft: () -> Unit,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var drag by remember(key) { mutableFloatStateOf(0f) }
    val right by rememberUpdatedState(onRight)
    val left by rememberUpdatedState(onLeft)
    val hint by rememberUpdatedState(onHint)
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val activationThreshold = with(LocalDensity.current) { 24.dp.toPx() } // Sensitivity of drag start
    val horizontalDominance = 1.5f
    val visualThreshold = threshold - activationThreshold
    val action = if (drag > 0f) rightAction else if (drag < 0f) leftAction else null
    val color = action?.let {
        lerp(MaterialTheme.colorScheme.surface, it.color, (abs(drag) / visualThreshold).coerceIn(0f, 1f) * it.strength)
    } ?: MaterialTheme.colorScheme.surface
    val rightLabel = rightAction?.let { stringResource(it.label) }
    val leftLabel = leftAction?.let { stringResource(it.label) }
    DisposableEffect(key) { onDispose { hint(key, null) } }

    Surface(shape = RoundedCornerShape(14.dp), color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().offset { IntOffset(drag.roundToInt(), 0) }
            .pointerInput(key, threshold, activationThreshold, rightAction, leftAction) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalX = 0f
                    var totalY = 0f
                    var swiping = false
                    var releasedX: Float? = null

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.id != down.id && it.pressed }) break

                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (change.isConsumed) break

                            val movement = change.positionChange()
                            totalX += movement.x
                            totalY += movement.y

                            if (!change.pressed) {
                                if (swiping) {
                                    change.consume()
                                    releasedX = totalX
                                }
                                break
                            }

                            if (!swiping) {
                                // Once vertical scrolling wins, wait for the next gesture.
                                if (abs(totalY) >= viewConfiguration.touchSlop &&
                                    abs(totalY) >= abs(totalX)) break

                                if (abs(totalX) >= activationThreshold &&
                                    abs(totalX) >= abs(totalY) * horizontalDominance) {

                                    if ((totalX > 0f && rightAction == null) ||
                                        (totalX < 0f && leftAction == null)) break

                                    swiping = true
                                } else {
                                    // Let the parent list handle scrolling first.
                                    val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                                    if (finalEvent.changes.any {
                                            it.id == down.id && it.isConsumed
                                        }) break
                                    continue
                                }
                            }

                            change.consume()

                            // Avoid a jump when the activation threshold is crossed.
                            drag = (
                                    sign(totalX) *
                                            (abs(totalX) - activationThreshold).coerceAtLeast(0f)
                                    ).coerceIn(
                                    if (leftAction == null) 0f else -visualThreshold * 1.6f,
                                    if (rightAction == null) 0f else visualThreshold * 1.6f,
                                )

                            hint(
                                key,
                                if (drag > 0f) rightAction
                                else if (drag < 0f) leftAction
                                else null,
                            )
                        }
                    } finally {
                        drag = 0f
                        hint(key, null)
                    }

                    releasedX?.let { distance ->
                        if (distance >= threshold && rightAction != null) right()
                        else if (distance <= -threshold && leftAction != null) left()
                    }
                }
            }.combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .semantics {
                customActions = listOfNotNull(
                    rightLabel?.let { CustomAccessibilityAction(it) { right(); true } },
                    leftLabel?.let { CustomAccessibilityAction(it) { left(); true } },
                )
            }, content = content)
}

@Composable
internal fun SwipeLabel(hint: SwipeHint?, modifier: Modifier = Modifier) {
    if (hint != null) Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Surface(color = hint.action.color, contentColor = Color(0xFF201A13),
            shape = RoundedCornerShape(10.dp), shadowElevation = 4.dp) {
            Text(stringResource(hint.action.label), Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelLarge)
        }
    }
}
