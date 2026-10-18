package com.example.tut.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tut.R
import com.example.tut.audio.VoiceNotePlayer
import com.example.tut.audio.VoiceNoteRecorder
import com.example.tut.audio.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** One player and one recorder shared by every voice note on the data page. */
class NoteAudio {
    val player = VoiceNotePlayer()
    val recorder = VoiceNoteRecorder()
    var recordingPath by mutableStateOf<String?>(null)
    /** Bumped when a recording finishes so waveforms reload. */
    var version by mutableIntStateOf(0)

    fun stopRecording() {
        recorder.stop()
        recordingPath = null
        version++
    }

    fun release() {
        if (recordingPath != null) stopRecording()
        player.release()
    }
}

/**
 * Record / Listen buttons plus a small waveform for a voice note. Recording again
 * appends to the existing note. Tapping the waveform opens a scrollable full view.
 */
@Composable
fun VoiceNoteBar(audio: NoteAudio, notePath: String?, newFile: () -> File, onCreated: (String) -> Unit) {
    val requestMic = LocalMicPermission.current
    val file = notePath?.let { File(it) }
    val playing by audio.player.playing.collectAsState()
    val isPlayingThis = playing && audio.player.file?.absolutePath == file?.absolutePath
    var peaks by remember { mutableStateOf(FloatArray(0)) }
    var durationMs by remember { mutableStateOf(0L) }
    var showViewer by remember { mutableStateOf(false) }
    var pendingPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(notePath, audio.version) {
        val f = file ?: return@LaunchedEffect
        peaks = withContext(Dispatchers.IO) { Wav.peaks(f) }
        durationMs = Wav.durationMs(f)
    }

    val recordingHere = audio.recordingPath != null && (audio.recordingPath == notePath || audio.recordingPath == pendingPath)

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(TutColors.SurfaceHigh)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = file != null && file.exists() && !recordingHere,
                onClick = {
                    if (isPlayingThis) audio.player.pause() else file?.let { audio.player.play(it) }
                },
            ) {
                if (isPlayingThis) Icon(painterResource(R.drawable.ic_pause), null, tint = TutColors.Green, modifier = Modifier.size(18.dp))
                else Icon(Icons.Default.PlayArrow, null, tint = TutColors.Green, modifier = Modifier.size(18.dp))
                Text(if (isPlayingThis) " Pause" else " Listen", color = TutColors.Green)
            }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    recordingHere -> "Recording…"
                    file == null -> "No recording"
                    else -> Fmt.minutesSeconds(durationMs)
                },
                color = if (recordingHere) TutColors.Red else TutColors.Muted, fontSize = 12.sp,
            )
            Spacer(Modifier.width(10.dp))
            // Record / stop recording (appends to an existing note)
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .border(2.dp, TutColors.Red, CircleShape)
                    .clickable {
                        if (recordingHere) {
                            audio.stopRecording()
                            val p = pendingPath
                            if (notePath == null && p != null) onCreated(p)
                            pendingPath = null
                        } else if (audio.recordingPath == null) {
                            requestMic {
                                audio.player.pause()
                                val target = file ?: newFile()
                                if (audio.recorder.start(target)) {
                                    audio.recordingPath = target.absolutePath
                                    pendingPath = target.absolutePath
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (recordingHere) Box(Modifier.size(14.dp).background(TutColors.Red, RoundedCornerShape(3.dp)))
                else Box(Modifier.size(18.dp).background(TutColors.Red, CircleShape))
            }
        }
        if (recordingHere) {
            var history by remember { mutableStateOf(listOf<Float>()) }
            LaunchedEffect(Unit) {
                while (true) {
                    history = (history + audio.recorder.level.value * 3f).takeLast(64)
                    delay(60)
                }
            }
            LiveWaveform(history, stopped = false, modifier = Modifier.fillMaxWidth().height(36.dp), color = TutColors.Red)
        } else if (file != null && peaks.isNotEmpty()) {
            var progress by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(isPlayingThis) {
                while (isPlayingThis) {
                    progress = if (durationMs > 0) audio.player.positionMs / durationMs.toFloat() else 0f
                    delay(80)
                }
            }
            NoteWaveform(
                peaks, if (isPlayingThis) progress else 0f,
                Modifier.fillMaxWidth().height(36.dp).clickable { showViewer = true },
            )
            Text("Tap waveform to open", color = TutColors.Muted, fontSize = 10.sp)
        }
    }

    if (showViewer && file != null) {
        WaveformViewer(audio.player, file, peaks, durationMs) { showViewer = false }
    }
}

/** Full-width, horizontally scrollable waveform with play/pause and tap-to-seek. */
@Composable
private fun WaveformViewer(player: VoiceNotePlayer, file: File, peaks: FloatArray, durationMs: Long, onDismiss: () -> Unit) {
    val playing by player.playing.collectAsState()
    val isThis = player.file?.absolutePath == file.absolutePath
    var positionMs by remember { mutableIntStateOf(if (isThis) player.positionMs else 0) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val barPx = with(density) { 4.dp.toPx() }
    val widthDp = with(density) { (peaks.size * barPx).toDp() }.coerceAtLeast(300.dp)

    LaunchedEffect(playing) {
        while (playing && player.file?.absolutePath == file.absolutePath) {
            positionMs = player.positionMs
            // keep the playhead in view
            val x = (positionMs / 50f * barPx).toInt()
            if (x < scroll.value || x > scroll.value + scroll.viewportSize - 40) scroll.animateScrollTo((x - 40).coerceAtLeast(0))
            delay(40)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), color = TutColors.Surface, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Recording", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TutColors.Text)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(120.dp).horizontalScroll(scroll)) {
                    Canvas(
                        Modifier.width(widthDp).height(120.dp).pointerInput(peaks) {
                            detectTapGestures { offset ->
                                val ms = (offset.x / barPx * 50).toInt().coerceIn(0, durationMs.toInt())
                                positionMs = ms
                                if (player.file?.absolutePath == file.absolutePath) player.seekTo(ms)
                                else { player.play(file, ms); player.pause() }
                            }
                        }
                    ) {
                        val mid = size.height / 2
                        val playedBars = positionMs / 50
                        peaks.forEachIndexed { i, v ->
                            val h = (v * size.height).coerceAtLeast(2f)
                            val x = i * barPx + barPx / 2
                            drawLine(
                                if (i <= playedBars) TutColors.Green else TutColors.Muted,
                                Offset(x, mid - h / 2), Offset(x, mid + h / 2), barPx * 0.6f, StrokeCap.Round,
                            )
                        }
                        val px = playedBars * barPx
                        drawLine(TutColors.Text, Offset(px, 0f), Offset(px, size.height), 2f)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${Fmt.minutesSeconds(positionMs.toLong())} / ${Fmt.minutesSeconds(durationMs)}", color = TutColors.Muted)
                    IconButton(onClick = {
                        if (playing && isThis) player.pause()
                        else player.play(file, if (positionMs >= durationMs - 100) 0 else positionMs)
                    }) {
                        if (playing && player.file?.absolutePath == file.absolutePath)
                            Icon(painterResource(R.drawable.ic_pause), "Pause", tint = TutColors.Green)
                        else Icon(Icons.Default.PlayArrow, "Play", tint = TutColors.Green)
                    }
                    TextButton(onClick = onDismiss) { Text("Close", color = TutColors.Muted) }
                }
            }
        }
    }
}
