package dev.avijit.orbithealth.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import dev.avijit.orbithealth.ui.nav.Routes
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** One main tab of the Living Skies bar. */
data class BarTab(val route: String, val symbol: OrbitSymbol, val label: String)

val BarTabs = listOf(
    BarTab(Routes.TODAY, OrbitSymbol.Today, "Today"),
    BarTab(Routes.SLEEP, OrbitSymbol.Sleep, "Sleep"),
    BarTab(Routes.BODY, OrbitSymbol.Body, "Body"),
    BarTab(Routes.ASK, OrbitSymbol.Ask, "Ask"),
    BarTab(Routes.ME, OrbitSymbol.Me, "Me"),
)

object OrbitBottomBarDefaults {
    val Height = 64.dp
    val Inset = 16.dp
    val Bottom = 22.dp
    val FadeHeight = 150.dp
    val ItemWidth = 60.dp
    val Shape = RoundedCornerShape(32.dp)
    val Blur = 24.dp

    /** Space a scrolling tab leaves under its last card so it clears the bar. */
    val Clearance = Height + Bottom + 16.dp

    internal val HazeStyle = HazeStyle(
        backgroundColor = OrbitPalette.Base1,
        tints = listOf(HazeTint(OrbitPalette.BarFill)),
        blurRadius = Blur,
        noiseFactor = 0f,
    )
}

/**
 * Living Skies bottom bar (tokens §5): a floating glass pill 16dp in from the sides and 22dp up (or above the system
 * navigation bar, whichever is higher), over a 150dp fade into the base. The one glass surface in the app: [hazeState]'s
 * backdrop blurred 24dp and tinted #141A3A @ 72% (API 32+; Haze leaves RenderEffect blur off on 31), else a solid
 * 94% fill. Five 60dp items (24dp glyph, 3dp, label); the active one is ink with a planet dot 5dp under its label that
 * springs between items; tints crossfade over 200 ms. The saturate(140%) of the CSS backdrop is not reproduced.
 */
@Composable
fun OrbitBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = LocalHazeState.current,
) {
    val haptics = rememberHaptics()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottom = max(OrbitBottomBarDefaults.Bottom, navInset)
    val selected = BarTabs.indexOfFirst { it.route == currentRoute }
    val dotIndex by animateFloatAsState(
        selected.coerceAtLeast(0).toFloat(), spring(dampingRatio = 0.7f, stiffness = 500f), label = "navDot",
    )
    val blur = hazeState != null && Build.VERSION.SDK_INT >= 32 && !LocalInspectionMode.current
    Box(modifier.fillMaxWidth().height(OrbitBottomBarDefaults.FadeHeight + bottom - OrbitBottomBarDefaults.Bottom)) {
        Spacer(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to OrbitPalette.Base1.copy(alpha = 0f), 0.5f to OrbitPalette.Base1.copy(alpha = 0.88f), 0.74f to OrbitPalette.Base1,
                ),
            ),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = OrbitBottomBarDefaults.Inset, end = OrbitBottomBarDefaults.Inset, bottom = bottom)
                .fillMaxWidth()
                .height(OrbitBottomBarDefaults.Height)
                .softShadow(OrbitPalette.BarShadow, offsetY = 14.dp, blur = 32.dp, spread = (-8).dp, cornerRadius = 32.dp)
                .clip(OrbitBottomBarDefaults.Shape)
                .then(
                    if (blur) Modifier.hazeEffect(hazeState!!, OrbitBottomBarDefaults.HazeStyle)
                    else Modifier.background(OrbitPalette.BarFillOpaque),
                )
                .border(1.dp, OrbitPalette.BarBorder, OrbitBottomBarDefaults.Shape)
                .drawWithContent {
                    drawContent()
                    if (selected < 0) return@drawWithContent
                    // SpaceAround over the 6dp-padded row: item centres from the animated index.
                    val pad = 6.dp.toPx()
                    val item = OrbitBottomBarDefaults.ItemWidth.toPx()
                    val n = BarTabs.size
                    val gap = (size.width - 2 * pad - n * item) / n
                    val x = pad + gap / 2f + dotIndex * (item + gap) + item / 2f
                    // Column of glyph 24 + 3 + label 14 + 4 bottom padding, centred: the dot sits 5dp under the label.
                    val top = (size.height - 45.dp.toPx()) / 2f
                    val y = top + (24 + 3 + 14 + 5 + 2).dp.toPx()
                    drawPlanet(
                        Offset(x, y), 2.dp.toPx(), OrbitPalette.PlanetA, OrbitPalette.PlanetB, OrbitPalette.PlanetGlow,
                        glowBlur = 8.dp.toPx(), glowSpread = 2.dp.toPx(),
                    )
                }
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarTabs.forEach { tab ->
                BarItem(tab, tab.route == currentRoute) {
                    if (tab.route != currentRoute) {
                        haptics.perform(HapticEvent.TabChange)
                        onSelect(tab.route)
                    }
                }
            }
        }
    }
}

@Composable
private fun BarItem(tab: BarTab, on: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(if (on) OrbitPalette.Ink else OrbitPalette.NavInactive, tween(200), label = "tab")
    Column(
        Modifier
            .width(OrbitBottomBarDefaults.ItemWidth)
            .fillMaxHeight()
            .semantics { selected = on }
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick)
            .padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Symbol(tab.symbol, size = 24.dp, strokeWidth = 1.6f, tint = color)
        Text(tab.label, style = OrbitTypography.NavLabel, color = color, maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.NavLabel))
    }
}

/** Bottom padding a scrolling tab needs so its last card scrolls clear of the floating bar (and the system bar). */
@Composable
fun orbitBottomClearance(): Dp {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return OrbitBottomBarDefaults.Clearance + max(0.dp, navInset - OrbitBottomBarDefaults.Bottom) + 24.dp
}
