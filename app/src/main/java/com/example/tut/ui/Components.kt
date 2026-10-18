package com.example.tut.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Asks for microphone permission (if needed) and then runs the callback. */
val LocalMicPermission = compositionLocalOf<(onGranted: () -> Unit) -> Unit> { { it() } }

@Composable
fun TopBar(
    title: String?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TutColors.Text)
            }
        }
        if (title != null) {
            Text(
                title, modifier = Modifier.align(Alignment.Center),
                fontSize = 14.sp, color = TutColors.Muted, fontWeight = FontWeight.Medium,
            )
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * A button that only fires after being held down for [holdMs]. A bar fills while it is
 * held; letting go early resets it.
 */
@Composable
fun HoldToConfirmButton(
    text: String,
    modifier: Modifier = Modifier,
    holdMs: Long = 3000,
    color: Color = TutColors.Red,
    onConfirmed: () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val confirm by rememberUpdatedState(onConfirmed)
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, color, RoundedCornerShape(12.dp))
            .pointerInput(holdMs) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        val start = withFrameMillis { it }
                        while (true) {
                            val t = withFrameMillis { it } - start
                            progress = (t.toFloat() / holdMs).coerceAtMost(1f)
                            if (progress >= 1f) {
                                confirm()
                                break
                            }
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                    progress = 0f
                })
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
            drawStopIndicator = {},
        )
    }
}

/**
 * Scrolling bar waveform of recent microphone levels. Flatlines with an X when [stopped].
 * With [threshold], bars below it are dimmed and two lines mark where speech starts counting.
 */
@Composable
fun LiveWaveform(
    levels: List<Float>, stopped: Boolean, modifier: Modifier = Modifier, color: Color = TutColors.Green,
    threshold: Float? = null,
) {
    Canvas(modifier) {
        val midY = size.height / 2
        if (stopped) {
            drawLine(TutColors.Red.copy(alpha = 0.7f), Offset(0f, midY), Offset(size.width, midY), 3f)
            val s = size.height * 0.35f
            val cx = size.width / 2
            drawLine(TutColors.Red, Offset(cx - s, midY - s), Offset(cx + s, midY + s), 6f, StrokeCap.Round)
            drawLine(TutColors.Red, Offset(cx - s, midY + s), Offset(cx + s, midY - s), 6f, StrokeCap.Round)
            return@Canvas
        }
        val bars = 64
        val step = size.width / bars
        val recent = levels.takeLast(bars)
        val offset = bars - recent.size
        for (i in 0 until bars) {
            val v = if (i < offset) 0f else recent[i - offset]
            val h = (v.coerceIn(0f, 1f) * size.height * 0.95f).coerceAtLeast(2f)
            val x = i * step + step / 2
            val c = if (threshold != null && v < threshold) color.copy(alpha = 0.3f) else color
            drawLine(c, Offset(x, midY - h / 2), Offset(x, midY + h / 2), step * 0.55f, StrokeCap.Round)
        }
        if (threshold != null) {
            val h = threshold.coerceIn(0f, 1f) * size.height * 0.95f / 2
            val line = Color.White.copy(alpha = 0.6f)
            drawLine(line, Offset(0f, midY - h), Offset(size.width, midY - h), 2f)
            drawLine(line, Offset(0f, midY + h), Offset(size.width, midY + h), 2f)
        }
    }
}

/** Static waveform of a recorded note. [progress] (0..1) colours the played part. */
@Composable
fun NoteWaveform(peaks: FloatArray, progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (peaks.isEmpty()) {
            drawLine(TutColors.Outline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
            return@Canvas
        }
        val bars = (size.width / 6f).toInt().coerceAtLeast(1)
        val per = peaks.size.toFloat() / bars
        val mid = size.height / 2
        for (i in 0 until bars) {
            val from = (i * per).toInt()
            val to = ((i + 1) * per).toInt().coerceAtMost(peaks.size).coerceAtLeast(from + 1)
            var v = 0f
            for (j in from until to.coerceAtMost(peaks.size)) v = maxOf(v, peaks[j])
            val h = (v * size.height).coerceAtLeast(2f)
            val x = i * 6f + 3f
            val c = if (i.toFloat() / bars <= progress) TutColors.Green else TutColors.Muted
            drawLine(c, Offset(x, mid - h / 2), Offset(x, mid + h / 2), 3f, StrokeCap.Round)
        }
    }
}

@Composable
fun BigBlock(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(TutColors.Surface)
            .border(1.dp, TutColors.Outline, RoundedCornerShape(24.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, fontSize: Int = 12) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(text, color = color, fontSize = fontSize.sp, maxLines = 1) }
}

@Composable
fun LabeledRow(label: String, value: String, valueColor: Color = TutColors.Text, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) } else Modifier)
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TutColors.Muted, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

object Fmt {
    private fun pattern(p: String) = SimpleDateFormat(p, Locale.getDefault())
    fun date(ms: Long): String = pattern("EEE, MMM d, yyyy").format(Date(ms))
    fun time(ms: Long): String = pattern("h:mm:ss a").format(Date(ms))
    fun timeShort(ms: Long): String = pattern("h:mm a").format(Date(ms))
    fun dateTime(ms: Long): String = "${date(ms)} · ${timeShort(ms)}"

    fun elapsed(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    fun minutesSeconds(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** A duration in words: "0.5 seconds", "1 second", "2 seconds". */
    fun seconds(ms: Long): String {
        val s = number(ms / 1000.0)
        return if (s == "1") "1 second" else "$s seconds"
    }

    /** Up to two decimal places, without trailing zeros: 7, 7.5, 7.25. */
    fun number(v: Double): String =
        java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
