package com.electricdog.coffeereader.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SwipeLegend(
    modifier: Modifier = Modifier,
    leftLabel: String? = null,
    leftColor: Color = Color(0xFFFFA342),
    rightLabel: String? = null,
    rightColor: Color = Color(0xFF45B86B),
    compact: Boolean = false,
) {
    if (compact) {
        // Stack the hints in the narrow space between the view button and menu.
        Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            leftLabel?.let { SwipeLegendItem(it, leftColor, false, true) }
            rightLabel?.let { SwipeLegendItem(it, rightColor, true, true) }
        }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            leftLabel?.let {
                SwipeLegendItem(it, leftColor, false, false, Modifier.weight(1f))
            }
            rightLabel?.let {
                SwipeLegendItem(it, rightColor, true, false, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SwipeLegendItem(label: String, arrowColor: Color, pointsRight: Boolean,
                            compact: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (!pointsRight) Text("◀", color = arrowColor, fontSize = 18.sp)
        Text(label, Modifier.weight(1f, fill = false), fontSize = if (compact) 10.sp else 12.sp,
            lineHeight = if (compact) 12.sp else 15.sp)
        if (pointsRight) Text("▶", color = arrowColor, fontSize = 18.sp)
    }
}
