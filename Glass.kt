package pw.chroma.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight

/** "Liquid glass": translucent frosted fill, bright top-edge highlight, soft rim. Sits over the blurred artwork backdrop. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(26.dp), strength: Float = 1f): Modifier =
    this.clip(shape)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f * strength), Color.White.copy(alpha = .07f * strength))))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .60f), Color.White.copy(alpha = .06f), Color.White.copy(alpha = .22f))), shape)

@Composable
fun GlassPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.glass(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White) }
}
