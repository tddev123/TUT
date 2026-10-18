package com.example.tut.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tut.TutApp
import com.example.tut.data.Adaptation
import com.example.tut.data.AppSettings
import com.example.tut.data.BlockColor
import com.example.tut.data.Bulletin
import com.example.tut.data.Bulletins
import com.example.tut.data.CurveUnit
import com.example.tut.data.Exercise
import com.example.tut.data.Outcome
import com.example.tut.data.Pattern
import com.example.tut.data.Progress
import com.example.tut.data.StreakBulletin
import com.example.tut.data.Streaks
import com.example.tut.parse.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** One exercise compared with the last time it was done: what the curve is drawn from. */
data class CurveItem(
    val name: String, val gapHours: Double, val color: BlockColor,
    val bulletin: Bulletin? = null, val streak: StreakBulletin? = null,
) {
    val outcome: Outcome get() = Outcome.of(color)!!
}

/** Builds the curve for [ex], or null with no earlier same exercise / no working sets to compare. Blocking. */
fun curveFor(app: TutApp, ex: Exercise): CurveItem? {
    val color = ex.color ?: return null
    val prev = app.repo.previousSameExercise(ex) ?: return null
    val bulletin = Bulletins.forExercise(app.repo, ex, app.settings.current)
    val streak = Streaks.forExercise(app.repo, ex, app.settings.current)
    val gap = (app.repo.workoutAt(ex) - app.repo.workoutAt(prev)) / 3_600_000.0
    return CurveItem(ex.displayName, gap, color, bulletin, streak)
}

/** The "Adaptation curve" button; greyed out (with why) when there is nothing to compare. */
@Composable
fun AdaptationCurveButton(available: Boolean, whyNot: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Text(
            "Adaptation curve", color = if (available) TutColors.Text else TutColors.Muted,
            fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(TutColors.SurfaceHigh)
                .border(1.dp, if (available) TutColors.Green else TutColors.Outline, RoundedCornerShape(12.dp))
                .clickable(enabled = available, onClick = onClick)
                .padding(vertical = 12.dp),
        )
        if (!available) Text(whyNot, color = TutColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Nearly full screen: the curve(s) scroll, Close stays at the bottom. */
@Composable
fun AdaptationCurveDialog(items: List<CurveItem>, settings: AppSettings, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp), color = TutColors.Surface,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 24.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Adaptation curve", color = TutColors.Muted, fontSize = 13.sp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    items.forEachIndexed { i, item ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(TutColors.Outline))
                        CurveSection(item, settings)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close", color = TutColors.Green, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

private fun gapText(hours: Double, unit: CurveUnit): String = when (unit) {
    CurveUnit.HOURS -> "${hours.roundToInt()} hours"
    CurveUnit.DAYS -> Fmt.number(hours / 24).let { if (it == "1") "1 day" else "$it days" }
}

@Composable
private fun CurveSection(item: CurveItem, settings: AppSettings) {
    val unit = settings.curveUnit
    val name = item.name
    val gap = gapText(item.gapHours, unit)
    val color = item.color.inkColor()
    Text(name, color = TutColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Pill(
        when (item.outcome) {
            Outcome.STRONGER -> if (item.color.isGold) item.color.label else "Stronger than last time"
            Outcome.SAME -> "About the same as last time"
            Outcome.WEAKER -> if (item.color != BlockColor.RED) item.color.label else "Weaker than last time"
        },
        color, Modifier.padding(top = 4.dp),
    )
    CurveGraph(item, unit, color, Modifier.fillMaxWidth().height(360.dp).padding(top = 12.dp))
    Text(
        when (item.outcome) {
            Outcome.STRONGER ->
                "You came back $gap after the last $name, right at the top of the supercompensation: " +
                    "recovered and above baseline, so you were stronger."
            Outcome.SAME ->
                "You came back $gap after the last $name: recovered back to baseline, but before the " +
                    "supercompensation peak, so you were about the same. A little more rest may catch the peak."
            Outcome.WEAKER ->
                "You came back $gap after the last $name, while still in the recovery dip below baseline, " +
                    "so you were weaker. More rest may let you reach the peak."
        },
        color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
    )
    item.bulletin?.let { BulletinCard(it, item, settings) }
    item.streak?.let { StreakCard(it, item, settings) }
}

/** One compared value in the streak card: all workouts' values and how close counts as "about the same". */
private class StreakRow(val label: String, val values: List<Double?>, val tolerance: Double, val fmt: (Double) -> String) {
    /** Judged on the stronger workouts only: what they had in common. */
    val pattern: Pattern? = Streaks.pattern(values.drop(1), tolerance)
    val similar: Boolean get() = pattern == Pattern.SAME || pattern == Pattern.STEADY

    /** "4 - 5 days", or just "2" when they are all the same. */
    fun range(): String {
        val v = values.drop(1).filterNotNull()
        val lo = fmt(v.min())
        val hi = fmt(v.max())
        return if (lo == hi) lo else "$lo \u2013 $hi"
    }
}

private val STREAK_LABEL_WIDTH = 112.dp
private val STREAK_COL_WIDTH = 74.dp
private val STREAK_HEADER_HEIGHT = 52.dp
/** Each row: the label with its note tucked right under it, and space before the next row. */
private val STREAK_ROW_HEIGHT = 58.dp
private val STREAK_ROW_TOP = 12.dp

/**
 * Stronger several times in a row: every workout of the streak side by side (the one it grew
 * from first, then each stronger one in its streak colour), with what the stronger ones had in
 * common highlighted in gold. Scrolls sideways when the streak is long.
 */
@Composable
private fun StreakCard(st: StreakBulletin, item: CurveItem, s: AppSettings) {
    val date = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val unit = s.weightUnit.short
    fun tenths(v: Double) = Fmt.number(Math.round(v * 10) / 10.0)
    fun days(v: Double) = tenths(v).let { d -> if (d == "1") "1 day" else "$d days" }
    val w = st.workouts.map { it.summary }

    val rows = buildList {
        add(StreakRow("Rest days", st.workouts.map { it.restBeforeHours?.div(24) }, 1.0, ::days))
        add(StreakRow("Top weight", w.map { it.topWeight }, 0.0) { "${Fmt.number(it)} $unit" })
        add(StreakRow("Working sets", w.map { it.workingSets.toDouble() }, 0.0) { Fmt.number(it) })
        if (w.any { it.avgReps != null }) add(StreakRow("Avg reps", w.map { it.avgReps }, 1.0, ::tenths))
        if (w.any { it.avgTutSec != null }) add(StreakRow("Avg TUT", w.map { it.avgTutSec }, 5.0) { formatDuration(it.roundToInt()) })
        if (s.rirEnabled) add(StreakRow("Avg RIR", w.map { it.avgRir }, 1.0, ::tenths))
    }
    // Column colours follow the streak: start, green, dark gold, medium gold, gold...
    val colors = buildList {
        add(TutColors.Muted)
        var c = BlockColor.GREEN
        repeat(st.length) { add(c.toColor()); c = c.nextInStreak() }
    }
    val gold = item.color.toColor()

    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp))
            .background(gold.copy(alpha = 0.10f))
            .border(1.dp, gold, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text("Streak: stronger ${st.length} times in a row", color = gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        val common = rows.filter { it.similar }
        Text(
            if (common.isEmpty()) "No one thing stayed the same across these ${st.length} stronger workouts. Here they are side by side:"
            else "What these ${st.length} stronger ${item.name} workouts had in common:",
            color = TutColors.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
        )
        common.forEach { r ->
            Text(
                "\u2022 ${r.label}: ${r.range()}", color = gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp),
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            // Fixed labels on the left; the workouts scroll sideways together on the right.
            Column(Modifier.width(STREAK_LABEL_WIDTH)) {
                Spacer(Modifier.height(STREAK_HEADER_HEIGHT))
                rows.forEach { r ->
                    Column(Modifier.height(STREAK_ROW_HEIGHT).padding(top = STREAK_ROW_TOP)) {
                        Text(
                            r.label, color = if (r.similar) gold else TutColors.Muted, fontSize = 13.sp,
                            fontWeight = if (r.similar) FontWeight.Bold else FontWeight.Normal, lineHeight = 15.sp,
                        )
                        r.pattern?.let { p ->
                            Text(
                                (if (r.similar) "\u2713 " else "") + p.note,
                                color = if (r.similar) gold else TutColors.Muted, fontSize = 10.sp, lineHeight = 11.sp,
                            )
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Row(Modifier.height(STREAK_HEADER_HEIGHT)) {
                    st.workouts.forEachIndexed { i, x ->
                        BulletinHeader(
                            date.format(Date(x.summary.at)), if (i == 0) "start" else "+$i",
                            if (i == 0) "Before" else "Stronger", colors[i], Modifier.width(STREAK_COL_WIDTH),
                        )
                    }
                }
                rows.forEach { r ->
                    Row(Modifier.height(STREAK_ROW_HEIGHT).padding(top = STREAK_ROW_TOP), verticalAlignment = Alignment.Top) {
                        r.values.forEachIndexed { i, v ->
                            val hi = r.similar && i > 0
                            Box(Modifier.width(STREAK_COL_WIDTH), contentAlignment = Alignment.CenterEnd) {
                                Text(
                                    v?.let(r.fmt) ?: "\u2014", color = if (hi) gold else colors[i], fontSize = 13.sp,
                                    fontWeight = if (hi) FontWeight.Black else FontWeight.SemiBold, maxLines = 1, lineHeight = 15.sp,
                                    modifier = if (hi) Modifier.padding(start = 4.dp).clip(RoundedCornerShape(6.dp))
                                        .background(gold.copy(alpha = 0.18f)).padding(horizontal = 4.dp, vertical = 1.dp)
                                    else Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Last time this exercise got stronger, this time it didn't: all three workouts side by side
 * (the one before, the stronger one in green, this one in red / gray). A red arrow from the first
 * to the second workout marks where the second one was made harder (more weight or working sets,
 * lower RIR or time under tension), with that value in red.
 */
@Composable
private fun BulletinCard(b: Bulletin, item: CurveItem, s: AppSettings) {
    val date = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val unit = s.weightUnit.short
    // Counted back from this workout, not from today, so an old workout reads the same as a new one.
    fun ago(at: Long) = ((b.current.at - at) / 86_400_000L).let {
        if (at == b.current.at) "this one" else if (it == 0L) "same day" else if (it == 1L) "1 day before" else "$it days before"
    }
    val outcome = if (item.outcome == Outcome.WEAKER) "weaker" else "about the same"

    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp))
            .background(TutColors.Orange.copy(alpha = 0.10f))
            .border(1.dp, TutColors.Orange, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text("Bulletin", color = TutColors.Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(
            if (Progress.improved(b.stalledColor)) {
                "The ${item.name} on ${date.format(Date(b.progressed.at))} was followed by progress, but the one on " +
                    "${date.format(Date(b.stalled.at))} was followed by $outcome. Here is what you did differently:"
            } else {
                "The two ${item.name} workouts right before this one, and this one. Here is what changed between them:"
            },
            color = TutColors.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        // Each column is coloured and labelled by how that workout actually did.
        val cols = listOf(b.progressedColor, b.stalledColor, item.color)
        val colors = cols.map { c -> if (c == null) TutColors.Text else c.inkColor() }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.1f))
            BulletinHeader(date.format(Date(b.progressed.at)), ago(b.progressed.at), resultLabel(cols[0]), colors[0], Modifier.weight(1f))
            Spacer(Modifier.width(ARROW_WIDTH))
            BulletinHeader(date.format(Date(b.stalled.at)), ago(b.stalled.at), resultLabel(cols[1]), colors[1], Modifier.weight(1f))
            BulletinHeader(date.format(Date(b.current.at)), ago(b.current.at), resultLabel(cols[2]), colors[2], Modifier.weight(1f))
        }
        val w = listOf(b.progressed, b.stalled, b.current)
        fun tenths(v: Double) = Fmt.number(Math.round(v * 10) / 10.0)
        BulletinRow("Top weight", w.map { it.topWeight }, colors, harder = { a, c -> c > a }) { "${Fmt.number(it)} $unit" }
        BulletinRow("Working sets", w.map { it.workingSets.toDouble() }, colors, harder = { a, c -> c > a }) { Fmt.number(it) }
        if (w.any { it.avgReps != null }) BulletinRow("Avg reps", w.map { it.avgReps }, colors) { tenths(it) }
        if (w.any { it.avgTutSec != null }) {
            BulletinRow("Avg TUT", w.map { it.avgTutSec }, colors, harder = { a, c -> c < a }) { formatDuration(it.roundToInt()) }
        }
        // Lower RIR = fewer reps left in reserve = a harder workout.
        if (s.rirEnabled) BulletinRow("Avg RIR", w.map { it.avgRir }, colors, harder = { a, c -> c < a }) { tenths(it) }
        BulletinRow("Rest days", listOf(b.restBeforeProgressed, b.restBeforeStalled, b.restBeforeCurrent).map { it?.div(24) }, colors) {
            tenths(it).let { d -> if (d == "1") "1 day" else "$d days" }
        }
    }
}

private val ARROW_WIDTH = 22.dp

@Composable
private fun BulletinHeader(date: String, ago: String, result: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(date, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(ago, color = TutColors.Muted, fontSize = 10.sp)
        Text(result, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * One value across the three workouts, each in its column's colour. The label is highlighted
 * when the workouts differ on it. When [harder] says the second workout was made harder than
 * the first, a red arrow points from the first to the second and the second value turns red.
 */
@Composable
private fun BulletinRow(
    label: String, values: List<Double?>, colors: List<Color>,
    harder: ((first: Double, second: Double) -> Boolean)? = null,
    fmt: (Double) -> String,
) {
    val shown = values.map { v -> v?.let(fmt) }
    val differs = shown.filterNotNull().distinct().size > 1
    val a = values[0]
    val c = values[1]
    // Compare what is shown, so 185.001 vs 185 does not count as an increase.
    val arrow = harder != null && a != null && c != null && fmt(a) != fmt(c) && harder(a, c)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, color = if (differs) TutColors.Orange else TutColors.Muted, fontSize = 13.sp,
            fontWeight = if (differs) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1.1f),
        )
        shown.forEachIndexed { i, v ->
            if (i == 1) {
                Text(
                    if (arrow) "\u2192" else "", color = TutColors.Red, fontSize = 18.sp, fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center, modifier = Modifier.width(ARROW_WIDTH),
                )
            }
            val red = arrow && i == 1
            Text(
                v ?: "\u2014", color = if (red) TutColors.Red else colors[i], fontSize = 13.sp,
                fontWeight = if (red) FontWeight.Black else FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).then(
                    if (red) Modifier.padding(start = 4.dp).clip(RoundedCornerShape(6.dp))
                        .background(TutColors.Red.copy(alpha = 0.18f)).padding(horizontal = 4.dp, vertical = 1.dp)
                    else Modifier
                ),
            )
        }
    }
}

/** Picks a tick step so the axis has at most about 7 labels. */
private fun tickStepHours(totalHours: Double): Double {
    val steps = listOf(6.0, 12.0, 24.0, 48.0, 72.0, 168.0, 336.0, 720.0)
    return steps.firstOrNull { totalHours / it <= 7 } ?: steps.last()
}

private fun tickLabel(hours: Double, unit: CurveUnit): String = when (unit) {
    CurveUnit.HOURS -> "${hours.roundToInt()}h"
    CurveUnit.DAYS -> "Day ${Fmt.number(hours / 24)}"
}

@Composable
private fun CurveGraph(item: CurveItem, unit: CurveUnit, color: Color, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val scale = Adaptation.scaleFor(item.gapHours, item.outcome)
    val gap = item.gapHours.coerceAtLeast(1.0)
    val total = maxOf(1.6 * scale, gap) * 1.12
    val step = tickStepHours(total)
    val small = TextStyle(color = TutColors.Muted, fontSize = 10.sp)

    Row(modifier) {
        // y axis
        Column(Modifier.width(56.dp).fillMaxHeight().padding(bottom = 34.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("Above\nbaseline", style = small)
            Text("Baseline", style = small.copy(color = TutColors.Text))
            Text("Below\nbaseline", style = small)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val axisH = 34.dp.toPx()
            val h = size.height - axisH
            val w = size.width
            val pad = 10f
            fun x(t: Double) = (t / total * w).toFloat()
            fun y(v: Double) = (h / 2 - v * (h / 2 - pad)).toFloat()
            val base = y(0.0)

            // baseline and axes
            drawLine(TutColors.Outline, Offset(0f, 0f), Offset(0f, h), 2f)
            drawLine(TutColors.Muted, Offset(0f, base), Offset(w, base), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            drawLine(TutColors.Outline, Offset(0f, h), Offset(w, h), 2f)

            // the curve, shaded red below baseline and green above
            val n = 240
            val pts = (0..n).map { i -> val t = total * i / n; Offset(x(t), y(Adaptation.level(t, scale))) }
            val line = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
            val fill = Path().apply {
                moveTo(0f, base); pts.forEach { lineTo(it.x, it.y) }; lineTo(w, base); close()
            }
            drawPath(fill, TutColors.Red.copy(alpha = 0.18f))
            clipRect(bottom = base) { drawPath(fill, TutColors.Green.copy(alpha = 0.22f)) }
            drawPath(line, TutColors.Text, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // x ticks
            var t = step
            while (t < total) {
                drawLine(TutColors.Outline, Offset(x(t), h), Offset(x(t), h + 8f), 2f)
                val label = measurer.measure(tickLabel(t, unit), small)
                drawText(label, topLeft = Offset(x(t) - label.size.width / 2f, h + 10f))
                t += step
            }
            val zero = measurer.measure("Last\n${item.name}", small.copy(color = TutColors.Text))
            drawCircle(TutColors.Text, 7f, Offset(0f, base))

            // this workout
            val gx = x(gap)
            val gy = y(Adaptation.level(gap, scale))
            drawLine(color, Offset(gx, 0f), Offset(gx, h), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            drawCircle(color, 12f, Offset(gx, gy))
            drawCircle(TutColors.Surface, 5f, Offset(gx, gy))
            val thisLabel = measurer.measure("This ${item.name}\n${gapText(item.gapHours, unit)}", small.copy(color = color, fontWeight = FontWeight.Bold))
            // Labels go on the side of the graph away from the curve at that point.
            val lx = (gx - thisLabel.size.width / 2f).coerceIn(0f, (w - thisLabel.size.width).coerceAtLeast(0f))
            val thisOnTop = gy > base
            drawText(thisLabel, topLeft = Offset(lx, if (thisOnTop) 4f else h - thisLabel.size.height - 4f))
            // "Last …" sits top-left (the curve dips downward from there), unless that spot is taken.
            val clash = thisOnTop && lx < zero.size.width + 12f
            drawText(zero, topLeft = Offset(6f, if (clash) h - zero.size.height - 4f else 4f))
        }
    }
}

/** A bulletin column's result against the workout before it. */
private fun resultLabel(c: BlockColor?): String = when (Outcome.of(c)) {
    Outcome.STRONGER -> "Stronger"
    Outcome.SAME -> "Same"
    Outcome.WEAKER -> "Weaker"
    null -> "First"
}
