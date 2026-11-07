package dev.avijit.orbithealth.ui.components

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox

/** List row text (tokens §3 body: Manrope 500 15/20, ink). */
val RowStyle = OrbitTypography.Body

/** List cards (tokens §2): 2dp vertical and 18dp horizontal padding around 52dp rows. */
val ListCardPadding = PaddingValues(horizontal = 18.dp, vertical = 2.dp)

/**
 * A tokens §5 list row: at least 52dp tall, a 1dp hairline above every row but the first, content spread to both
 * ends. [onClick] makes the whole row tappable (a settings row with a chevron).
 */
@Composable
fun CardRow(
    first: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind { if (!first) drawLine(OrbitPalette.Hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .then(
                if (onClick != null) {
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
                } else Modifier,
            )
            .heightIn(min = 52.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}

/** A neutral list card (tokens §5: ink 5% tint, 24dp radius, warm hairline, 2/18 padding). */
@Composable
fun ListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    TintedCard(
        OrbitPalette.Ink, modifier.fillMaxWidth(), amount = TintedCardDefaults.NeutralAmount,
        contentPadding = ListCardPadding, content = content,
    )
}

/** A list group's label (tokens §3 eyebrow), 22dp above and 8dp above its card, on the [gutter]. */
@Composable
fun ListGroupLabel(text: String, modifier: Modifier = Modifier, gutter: androidx.compose.ui.unit.Dp = 24.dp) {
    Text(
        text, style = OrbitTypography.Eyebrow,
        modifier = modifier.padding(start = gutter, end = gutter, top = 22.dp, bottom = 8.dp).lineBox(OrbitTypography.Eyebrow),
    )
}

/** "SpO₂" with the mockup's small subscript 2 rather than the font's full-size ₂. */
fun subscriptTwoText(label: String) = buildAnnotatedString {
    label.forEach { c ->
        if (c == '₂') withStyle(SpanStyle(fontSize = 0.62.em, baselineShift = BaselineShift(-0.18f))) { append('2') }
        else append(c)
    }
}

/**
 * A settings row: [label] (with an optional [caption] beneath and a [leading] glyph), then the [value] in ink2 and a
 * 16dp chevron when [chevron], or [trailing] (a toggle) instead.
 */
@Composable
fun ListRow(
    label: String,
    first: Boolean,
    modifier: Modifier = Modifier,
    value: String? = null,
    chevron: Boolean = false,
    caption: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    CardRow(first = first, modifier = modifier.semantics(mergeDescendants = true) {}, onClick = onClick) {
        LabelValueLayout(
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leading != null) {
                        leading()
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        Text(subscriptTwoText(label), style = RowStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (caption != null) {
                            Text(caption, style = OrbitTypography.Meta.copy(lineHeight = OrbitTypography.Caption.lineHeight * 1.2f), modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                        }
                    }
                }
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (value != null) {
                        // Ellipsized in the width LabelValueLayout leaves it, so a long value (a server's host name)
                        // never squeezes the label.
                        Text(
                            value, style = RowStyle.copy(fontFeatureSettings = "tnum"), color = OrbitPalette.Ink2, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
                        )
                    }
                    if (chevron) Symbol(OrbitSymbol.Chev, size = 16.dp, strokeWidth = 1.8f, tint = OrbitPalette.Ink2)
                    trailing?.invoke()
                }
            },
        )
    }
}

/** A label never gives up more than this share of its row to the value beside it. */
const val LabelMinShare = 0.45f

/**
 * Label left, trailing right, vertically centred. The label is promised its natural width up to [LabelMinShare] of
 * the row; the trailing part takes what it needs of the rest (a long value ellipsizes), and the label then gets
 * everything the trailing part didn't use (so a toggle row's caption runs nearly the full width).
 */
@Composable
internal fun LabelValueLayout(label: @Composable () -> Unit, trailing: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(contents = listOf(label, trailing), modifier = modifier.fillMaxWidth()) { (l, t), c ->
        val total = c.maxWidth
        val lm = l.first()
        val tm = t.first()
        val labelWant = minOf(lm.maxIntrinsicWidth(Constraints.Infinity), (total * LabelMinShare).toInt())
        val tw = minOf(tm.maxIntrinsicWidth(Constraints.Infinity), (total - labelWant).coerceAtLeast(0))
        val tp = tm.measure(Constraints(maxWidth = tw))
        val lp = lm.measure(Constraints(maxWidth = (total - tp.width).coerceAtLeast(0)))
        val h = maxOf(lp.height, tp.height)
        layout(total, h) {
            lp.place(0, (h - lp.height) / 2)
            tp.place(total - tp.width, (h - tp.height) / 2)
        }
    }
}

/** Room for a three-digit numeral and the longest unit ("years") with clear space before the + button. */
private val ValueCellWidth = 128.dp

/**
 * Label on the left; − / + icon buttons (tokens §5, ink 8% fill and 14% border) around a typed numeral in the value
 * role on the right; an inline message below in Attention. With [readOnly] the value is shown, not typed (e.g.
 * "7h 30m", "23:15"), and only the buttons change it.
 */
@Composable
fun StepperRow(
    label: String,
    unit: String,
    value: String,
    error: String?,
    first: Boolean,
    onValue: (String) -> Unit,
    onStep: (Int) -> Unit,
    last: Boolean = false,
    onDone: () -> Unit = {},
    readOnly: Boolean = false,
) {
    Column {
        CardRow(first = first) {
            Text(label, style = RowStyle)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("−", "Decrease $label") { onStep(-1) }
                Row(
                    Modifier.width(ValueCellWidth).padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    val valueStyle = OrbitTypography.Value.copy(
                        color = if (error != null) OrbitPalette.Attention else OrbitPalette.Ink,
                        textAlign = if (readOnly) TextAlign.Center else TextAlign.End,
                    )
                    if (readOnly) {
                        Text(value, style = valueStyle, maxLines = 1, modifier = Modifier.semantics { contentDescription = "$label $value" })
                    } else {
                        BasicTextField(
                            value = value,
                            onValueChange = onValue,
                            singleLine = true,
                            textStyle = valueStyle,
                            cursorBrush = SolidColor(OrbitPalette.SleepB),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = if (last) ImeAction.Done else ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(onDone = { onDone() }),
                            modifier = Modifier.width(56.dp).semantics { contentDescription = "$label in $unit" },
                        )
                    }
                    if (unit.isNotEmpty()) {
                        Text(unit, style = OrbitTypography.Unit, modifier = Modifier.padding(start = 4.dp, bottom = 5.dp))
                    }
                }
                StepButton("+", "Increase $label") { onStep(+1) }
            }
        }
        AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                error.orEmpty(),
                style = OrbitTypography.Meta,
                color = OrbitPalette.Attention,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, onClick: () -> Unit) {
    // 36Ø visual (tokens icon button) inside a 44dp touch target.
    Box(
        Modifier
            .size(44.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(OrbitPalette.Ink.copy(alpha = 0.08f))
                .border(1.dp, OrbitPalette.Ink.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, style = OrbitTypography.BodyStrong.copy(fontSize = OrbitTypography.Value.fontSize * 0.68f), color = Color.Unspecified)
        }
    }
}
