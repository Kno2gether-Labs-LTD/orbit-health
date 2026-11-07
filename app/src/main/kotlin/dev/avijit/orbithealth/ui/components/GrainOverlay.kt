package dev.avijit.orbithealth.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.R

/**
 * Film grain (tokens §8): one overlay above everything, 3% opacity, so flat midnight surfaces feel tactile.
 *
 * The tile (`res/drawable-nodpi/grain_tile.png`, 256 px, grey + alpha) is the mockup's own `--grain` texture —
 * `feTurbulence fractalNoise baseFrequency .85, 2 octaves, seed 7, stitchTiles`, desaturated — rendered once by
 * headless Chrome at 1.6× of its 160 px CSS size, so it tiles seamlessly. It is decoded once per process and drawn as a
 * repeating [BitmapShader] scaled to 160dp; drawing is one textured rect, and the overlay never takes touches.
 */
object Grain {
    const val ALPHA = 0.03f
    val TileSize = 160.dp

    @Volatile private var tile: Bitmap? = null

    fun tile(context: Context): Bitmap = tile ?: synchronized(this) {
        tile ?: BitmapFactory.decodeResource(context.resources, R.drawable.grain_tile, BitmapFactory.Options().apply { inScaled = false })
            .also { tile = it }
    }
}

@Composable
fun GrainOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember { Grain.tile(context.applicationContext) }
    Spacer(
        modifier.drawWithCache {
            val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                val s = Grain.TileSize.toPx() / bitmap.width
                setLocalMatrix(Matrix().apply { setScale(s, s) })
            }
            val brush = ShaderBrush(shader)
            onDrawBehind { drawRect(brush, alpha = Grain.ALPHA) }
        },
    )
}
