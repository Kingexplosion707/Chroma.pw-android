package com.metrolist.music.chroma

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Chroma "liquid glass": translucent body, specular sheen along the top, a bright rim fading down
 * the sides. [tint] lets the glass pick up the album colour.
 */
fun Modifier.chromaGlass(shape: Shape, tint: Color? = null): Modifier =
    this
        .clip(shape)
        .background(Color(0x8C121212))
        .background(Brush.verticalGradient(listOf((tint ?: Color.White).copy(alpha = .24f), (tint ?: Color.White).copy(alpha = .08f))))
        .drawBehind {
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .16f), Color.Transparent), endY = size.height * .5f))
            drawRect(
                Brush.radialGradient(listOf(Color.White.copy(alpha = .10f), Color.Transparent), center = Offset(size.width * .2f, 0f), radius = size.width * .6f),
            )
        }
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .55f), Color.White.copy(alpha = .08f), Color.White.copy(alpha = .22f))), shape)
