package com.example.tut.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import com.example.tut.data.AppSettings
import com.example.tut.parse.formatDuration
import kotlin.math.sqrt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.tut.R
import com.example.tut.TutApp
import com.example.tut.data.CompletedSet
import com.example.tut.data.SetKind
import com.example.tut.record.CollectionController
import com.example.tut.record.ModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NewDataScreen(app: TutApp, sessionId: Long, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember { CollectionController(app, sessionId, scope) }
    val settings by app.settings.settings.collectAsState()
    val view = LocalView.current

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        controller.begin()
        onDispose {
            view.keepScreenOn = false
            controller.close()
        }
    }

    var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sessionId) {
        withContext(Dispatchers.IO) { app.repo.session(sessionId) }?.let { startedAt = it.startedAt }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }

    val listening by controller.listening.collectAsState()
    val recording by controller.recording.collectAsState()
    val unsaved by controller.unsaved.collectAsState()
    val bubbles by controller.bubbles.collectAsState()
    val transcript by controller.transcript.collectAsState()
    val partial by controller.engine.partial.collectAsState()
    val status by controller.modelStatus.collectAsState()
    val target by controller.target.collectAsState()
    val exerciseName by controller.exerciseName.collectAsState()
    val noteRecording by controller.noteRecording.collectAsState()
    var renaming by remember { mutableStateOf(false) }

    var levels by remember { mutableStateOf(listOf<Float>()) }
    LaunchedEffect(Unit) {
        while (true) {
            levels = (levels + controller.engine.level.value).takeLast(64)
            delay(50)
        }
    }

    val savePrompt by controller.savePrompt.collectAsState()
    var savedPopup by remember { mutableStateOf(false) }
    var backBlocked by remember { mutableStateOf(false) }
    // Back needs Save first (or holding the back arrow), even with nothing recorded,
    // unless the hold is turned off in settings.
    var hasSaved by remember { mutableStateOf(false) }
    val holdMs = settings.backHoldMs.toLong()
    var editing by remember { mutableStateOf<CompletedSet?>(null) }

    LaunchedEffect(Unit) { controller.saved.collect { hasSaved = true; savedPopup = true } }
    LaunchedEffect(savedPopup) { if (savedPopup) { delay(1800); savedPopup = false } }

    fun exit() {
        controller.close()
        app.appScope.launch { app.repo.deleteSessionIfEmpty(sessionId) }
        onExit()
    }

    fun tryBack() {
        if (holdMs > 0 && (!hasSaved || unsaved)) backBlocked = true else exit()
    }
    BackHandler { tryBack() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                HoldBackArrow(
                    Modifier.align(Alignment.CenterStart),
                    onTap = { tryBack() },
                    onHeld = { exit() },
                    holdMs = holdMs,
                )
                Text("New data", modifier = Modifier.align(Alignment.Center), fontSize = 14.sp, color = TutColors.Muted, fontWeight = FontWeight.Medium)
            }
            Text(
                Fmt.dateTime(startedAt), color = TutColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            Text(
                exerciseName?.let { "Exercise: $it  ✎" } ?: "Say \"exercise …\" to name the exercise",
                color = if (exerciseName != null) TutColors.Green else TutColors.Muted,
                fontSize = if (exerciseName != null) 16.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { renaming = true },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                ToggleButton("Start", active = listening && recording, activeColor = TutColors.Green) {
                    controller.setListening(true)
                }
                Spacer(Modifier.width(14.dp))
                ToggleButton("Stop", active = !listening, activeColor = TutColors.Red) {
                    controller.setListening(false)
                }
            }
            ModelStatusLine(status)

            // Warm-up bubbles sit left of the microphone and working-set bubbles right of it,
            // each in a fixed area, so they never push anything.
            BoxWithConstraints(Modifier.fillMaxWidth().height(170.dp).padding(top = 6.dp)) {
                val micSize = 64.dp
                val bubbleWidth = (maxWidth - micSize) / 2 - 8.dp
                val (warmups, working) = bubbles.partition { it.set.kind == SetKind.WARMUP }
                BubbleArea(
                    warmups, settings, Color(settings.warmupColor.argb),
                    Modifier.align(Alignment.CenterStart).width(bubbleWidth).height(maxHeight),
                ) { editing = it }
                BubbleArea(
                    working, settings, Color(settings.workingColor.argb),
                    Modifier.align(Alignment.CenterEnd).width(bubbleWidth).height(maxHeight),
                    alignEnd = true,
                ) { editing = it }
                Icon(
                    painterResource(R.drawable.ic_mic), contentDescription = null,
                    tint = if (recording && listening) TutColors.Green else TutColors.Muted,
                    modifier = Modifier.align(Alignment.Center).size(micSize),
                )
            }

            val threshold by controller.engine.threshold.collectAsState()
            LiveWaveform(
                levels, stopped = !listening || !recording, modifier = Modifier.fillMaxWidth().height(64.dp),
                threshold = threshold.takeIf { it > 0f },
            )

            Text(
                target?.let { "→ $it  (say \"done\" when finished)" } ?: "Say \"working set 1\" or \"warm up set 1\"",
                color = TutColors.Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                textAlign = TextAlign.Center,
            )

            val visible = transcript.filter { now - it.at < settings.transcriptSeconds * 1000L }.joinToString(" ") { it.text }
            val full = listOf(visible, partial).filter { it.isNotBlank() }.joinToString(" ")
            TwoLineTail(full, Modifier.fillMaxWidth().padding(top = 8.dp))

            if (noteRecording) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp)).background(TutColors.Red.copy(alpha = 0.15f))
                        .border(1.dp, TutColors.Red, RoundedCornerShape(12.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(TutColors.Red))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Recording voice note — say \"done recording\" to stop",
                        color = TutColors.Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Fmt.elapsed(controller.elapsedMs(now) ?: 0L), color = TutColors.Green, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (recording && settings.saveHoldMs > 0) {
                    HoldSaveButton(settings.saveHoldMs.toLong()) { controller.confirmSave() }
                } else {
                    Button(
                        onClick = { if (recording) controller.confirmSave() else controller.resume() },
                        colors = ButtonDefaults.buttonColors(containerColor = TutColors.Green, contentColor = Color.Black),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(56.dp),
                    ) { Text(if (recording) "Save" else "Continue", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        AnimatedVisibility(
            savedPopup, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) { SavedPopup() }
    }

    if (savePrompt) {
        ConfirmDialog(
            title = "Are you sure you want to save data to the database?\n\nSay \"yes\" or \"no\"",
            onYes = { controller.answerSavePrompt(true) },
            onNo = { controller.answerSavePrompt(false) },
        )
    }
    if (backBlocked) {
        BackBlockedDialog(holdMs, onDismiss = { backBlocked = false }, onGoBackAnyway = { backBlocked = false; exit() })
    }
    if (renaming) {
        RenameDialog(exerciseName ?: "", onDismiss = { renaming = false }) {
            controller.renameExercise(it)
            renaming = false
        }
    }
    editing?.let { item ->
        SetEditDialog(
            set = item.set, title = "${item.exerciseName} · ${item.set.label}", settings = settings,
            onDismiss = { editing = null },
            onSave = { controller.editSet(it); editing = null },
        )
    }
}

@Composable
internal fun ToggleButton(text: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (active) activeColor else TutColors.Surface)
            .border(1.dp, if (active) activeColor else TutColors.Outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (active) Color.Black else TutColors.Muted, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
internal fun ModelStatusLine(status: ModelStatus) {
    val (text, color) = when (status) {
        ModelStatus.Loading -> "Loading speech model…" to TutColors.Muted
        ModelStatus.Missing -> "No speech model installed: add one in Settings" to TutColors.Orange
        is ModelStatus.Failed -> "Speech model problem: ${status.message}" to TutColors.Red
        is ModelStatus.Ready -> status.name to TutColors.Outline
    }
    Text(text, color = color, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center)
}

/**
 * One bubble per finished set (said "done"), showing its values. They shrink as more
 * accumulate so they always fit their area and never push the rest of the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BubbleArea(
    items: List<CompletedSet>, settings: AppSettings, color: Color, modifier: Modifier,
    alignEnd: Boolean = false, onClick: (CompletedSet) -> Unit,
) {
    val n = items.size
    val scale = if (n <= 2) 1f else sqrt(2f / n).coerceIn(0.4f, 1f)
    Box(modifier.clip(RoundedCornerShape(8.dp))) {
        FlowRow(
            // Right-hand bubbles line up against the right edge, mirroring the left side.
            modifier = Modifier.align(if (alignEnd) Alignment.TopEnd else Alignment.TopStart),
            horizontalArrangement = Arrangement.spacedBy((4 * scale).dp, if (alignEnd) Alignment.End else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy((4 * scale).dp),
        ) {
            items.forEach { item ->
                val set = item.set
                val line1 = listOfNotNull(
                    set.restSec?.let { "${formatDuration(it)} rest" },
                    set.weight?.let { "${Fmt.number(it)} ${(set.unit ?: settings.weightUnit).short}" },
                ).joinToString(" · ")
                val line2 = listOfNotNull(
                    set.reps?.let { "$it reps" },
                    set.tutSec?.let { "TUT ${formatDuration(it)}" },
                    if (settings.rirEnabled) rirText(set, settings) else null,
                ).joinToString(" · ")
                val rirColor = if (settings.rirEnabled && set.rirLevel != null) set.rirLevel.toColor() else TutColors.Text
                Column(
                    Modifier.clip(RoundedCornerShape((14 * scale).dp))
                        .background(color.copy(alpha = 0.35f))
                        .border(1.dp, color, RoundedCornerShape((14 * scale).dp))
                        .clickable { onClick(item) }
                        .padding(horizontal = (8 * scale).dp, vertical = (4 * scale).dp),
                ) {
                    Text(set.label, color = TutColors.Text, fontWeight = FontWeight.Bold, fontSize = (12 * scale).sp, maxLines = 1, lineHeight = (14 * scale).sp)
                    if (line1.isNotEmpty()) Text(line1, color = TutColors.Text, fontSize = (9 * scale).sp, maxLines = 1, lineHeight = (11 * scale).sp)
                    if (line2.isNotEmpty()) Text(line2, color = rirColor, fontSize = (9 * scale).sp, maxLines = 1, lineHeight = (11 * scale).sp)
                }
            }
        }
    }
}

/**
 * Back arrow: a tap asks for Save first; holding it for [holdMs] leaves without saving.
 * With [holdMs] 0 there is no hold, and the tap goes straight to [onTap].
 */
@Composable
private fun HoldBackArrow(modifier: Modifier, onTap: () -> Unit, onHeld: () -> Unit, holdMs: Long) {
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val held by rememberUpdatedState(onHeld)
    Box(
        modifier.size(48.dp).pointerInput(holdMs) {
            detectTapGestures(onPress = {
                if (holdMs <= 0) {
                    if (tryAwaitRelease()) tap()
                    return@detectTapGestures
                }
                var done = false
                val job = scope.launch {
                    val start = withFrameMillis { it }
                    while (true) {
                        progress = ((withFrameMillis { it } - start) / holdMs.toFloat()).coerceAtMost(1f)
                        if (progress >= 1f) { done = true; held(); break }
                    }
                }
                val released = tryAwaitRelease()
                job.cancel()
                progress = 0f
                if (released && !done) tap()
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        if (progress > 0.03f) {
            CircularProgressIndicator(
                progress = { progress }, color = TutColors.Red, strokeWidth = 3.dp,
                trackColor = TutColors.Red.copy(alpha = 0.2f), modifier = Modifier.size(44.dp),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = if (holdMs > 0) "Back (hold ${Fmt.seconds(holdMs)} to leave without saving)" else "Back",
            tint = TutColors.Text,
        )
    }
}

/** Save button that saves after being held for [holdMs]; a dark bar fills it while held. */
@Composable
private fun HoldSaveButton(holdMs: Long, onSave: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    var hint by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val save by rememberUpdatedState(onSave)
    Box(
        Modifier.height(56.dp).clip(RoundedCornerShape(14.dp)).background(TutColors.Green)
            .pointerInput(holdMs) {
                detectTapGestures(onPress = {
                    var done = false
                    val job = scope.launch {
                        val start = withFrameMillis { it }
                        while (true) {
                            progress = ((withFrameMillis { it } - start) / holdMs.toFloat()).coerceAtMost(1f)
                            if (progress >= 1f) { done = true; save(); break }
                        }
                    }
                    val released = tryAwaitRelease()
                    job.cancel()
                    progress = 0f
                    hint = released && !done
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().drawBehind {
            drawRect(Color.Black.copy(alpha = 0.3f), size = size.copy(width = size.width * progress))
        })
        Text(
            if (hint) "Hold ${Fmt.seconds(holdMs)}" else "Save",
            color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
    // A plain tap shows how long to hold, then goes back to "Save".
    LaunchedEffect(hint) { if (hint) { delay(1_500); hint = false } }
}

@Composable
internal fun RenameDialog(
    current: String, onDismiss: () -> Unit, title: String = "Exercise name", color: Color = TutColors.Green,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = TutColors.Surface) {
            Column(Modifier.padding(20.dp)) {
                Text(title, color = TutColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = TutColors.Muted) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(text.trim()) }, enabled = text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black),
                    ) { Text("Save", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/** Shows only as many trailing words as fit on two lines, so new speech pushes old speech out. */
@Composable
internal fun TwoLineTail(text: String, modifier: Modifier, color: Color = TutColors.Green) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = color, fontSize = 13.sp, lineHeight = 17.sp)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.height(40.dp)) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val shown = remember(text, widthPx) {
            val words = text.split(' ').filter { it.isNotEmpty() }
            var lo = 0
            var hi = words.size
            // smallest number of leading words to drop so the rest fits in two lines
            while (lo < hi) {
                val mid = (lo + hi) / 2
                val r = measurer.measure(words.drop(mid).joinToString(" "), style, constraints = Constraints(maxWidth = widthPx))
                if (r.lineCount <= 2) hi = mid else lo = mid + 1
            }
            words.drop(lo).joinToString(" ")
        }
        Text(shown, style = style, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SavedPopup() {
    Surface(shape = RoundedCornerShape(24.dp), color = TutColors.SurfaceHigh, shadowElevation = 12.dp) {
        Column(Modifier.padding(horizontal = 40.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(TutColors.Green), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(60.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("Saved in database", color = TutColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ConfirmDialog(title: String, onYes: () -> Unit, onNo: () -> Unit, yes: String = "Yes", no: String = "No") {
    Dialog(onDismissRequest = onNo) {
        Surface(shape = RoundedCornerShape(20.dp), color = TutColors.Surface) {
            Column(Modifier.padding(20.dp)) {
                Text(title, color = TutColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onNo) { Text(no, color = TutColors.Muted, fontSize = 16.sp) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onYes,
                        colors = ButtonDefaults.buttonColors(containerColor = TutColors.Green, contentColor = Color.Black),
                    ) { Text(yes, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                }
            }
        }
    }
}

@Composable
private fun BackBlockedDialog(holdMs: Long, onDismiss: () -> Unit, onGoBackAnyway: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = TutColors.Surface) {
            Column(Modifier.padding(20.dp)) {
                Text("You did not save your data", color = TutColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Make sure data is correct before saving. Press Save, then go back, or hold the back arrow for ${Fmt.seconds(holdMs)} to leave without saving.",
                    color = TutColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = TutColors.Green, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text("OK", fontWeight = FontWeight.Bold) }
                HoldToConfirmButton(
                    "Hold ${Fmt.seconds(holdMs)} to go back anyways",
                    holdMs = holdMs,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    onConfirmed = onGoBackAnyway,
                )
            }
        }
    }
}
