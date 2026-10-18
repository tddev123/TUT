package com.example.tut.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.platform.LocalContext
import com.example.tut.data.WordAliases
import com.example.tut.data.Fatigue
import com.example.tut.data.Predictions
import java.util.Calendar
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.tut.data.SessionNote
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tut.TutApp
import com.example.tut.data.AppSettings
import com.example.tut.data.BlockColor
import com.example.tut.data.EffortMode
import com.example.tut.data.Exercise
import com.example.tut.data.ExerciseWithSets
import com.example.tut.data.Progress
import com.example.tut.data.RecoveryFeeling
import com.example.tut.data.SessionWithExercises
import com.example.tut.data.SetKind
import com.example.tut.data.WorkSet
import com.example.tut.parse.fmtNumber
import com.example.tut.parse.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The data page: a list of big data blocks (sessions), like a voice-recordings list.
 * Tapping one expands it; tapping a small block (exercise) inside expands that.
 */
@Composable
fun DataScreen(
    app: TutApp, onBack: () -> Unit, onSettings: () -> Unit, onResume: (sessionId: Long) -> Unit,
    onPredict: () -> Unit,
    onModel: () -> Unit = {},
) {
    val version by app.repo.version.collectAsState()
    val settings by app.settings.settings.collectAsState()
    var sessions by remember { mutableStateOf<List<SessionWithExercises>?>(null) }
    var selectedSession by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedExercise by rememberSaveable { mutableStateOf<Long?>(null) }
    var trashed by remember { mutableStateOf<List<SessionWithExercises>>(emptyList()) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val audio = remember { NoteAudio() }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { audio.release() } }

    LaunchedEffect(version) {
        sessions = withContext(Dispatchers.IO) { app.repo.allSessions() }
        trashed = withContext(Dispatchers.IO) { app.repo.trashedSessions() }
    }

    BackHandler {
        when {
            selectedExercise != null -> selectedExercise = null
            selectedSession != null -> selectedSession = null
            showTrash -> showTrash = false
            else -> onBack()
        }
    }

    val session = sessions?.firstOrNull { it.session.id == selectedSession }
    val exercise = session?.exercises?.firstOrNull { it.exercise.id == selectedExercise }
    val view = when {
        exercise != null -> "exercise"
        session != null -> "session"
        showTrash -> "trash"
        else -> "list"
    }

    AnimatedContent(
        targetState = view,
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
        label = "data-page",
    ) { v ->
        when (v) {
            "exercise" -> if (session != null && exercise != null) {
                ExerciseDetail(app, session, exercise, settings, audio) { selectedExercise = null }
            }
            "session" -> if (session != null) {
                SessionDetail(
                    app, session, audio, onBack = { selectedSession = null },
                    onResume = { audio.release(); onResume(session.session.id) },
                ) { selectedExercise = it }
            }
            "trash" -> TrashList(
                trashed, settings, onBack = { showTrash = false },
                onRestore = { id -> scope.launch(Dispatchers.IO) { app.ops.restoreSession(id) } },
                onDeleteForever = { ids -> scope.launch(Dispatchers.IO) { ids.forEach { app.repo.deleteSession(it) } } },
            )
            else -> SessionList(
                sessions, settings, onBack, onSettings, onPredict, onModel,
                trashCount = trashed.size, onTrash = { showTrash = true },
                search = search, onSearch = { search = it },
                // Deleting only moves blocks to the trash; they're gone for good once deleted from there.
                onDelete = { ids -> scope.launch(Dispatchers.IO) { ids.forEach { app.ops.trashSession(it) } } },
                onAdd = { at ->
                    scope.launch {
                        selectedSession = withContext(Dispatchers.IO) {
                            app.repo.createSession(at).also { app.repo.setSessionSaved(it, true) }
                        }
                    }
                },
            ) { selectedSession = it }
        }
    }
}

// ------------------------------------------------------------------ list of big blocks

@Composable
private fun SessionList(
    sessions: List<SessionWithExercises>?,
    settings: AppSettings,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onPredict: () -> Unit,
    onModel: () -> Unit,
    trashCount: Int,
    onTrash: () -> Unit,
    search: String,
    onSearch: (String) -> Unit,
    onDelete: (Set<Long>) -> Unit,
    onAdd: (at: Long) -> Unit,
    onOpen: (Long) -> Unit,
) {
    // Delete mode: every block turns white, tapped ones turn red. Block colours are only
    // overridden while drawing, so leaving the mode shows the original colours again.
    var deleting by remember { mutableStateOf(false) }
    var marked by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The one block held down to delete it, waiting for yes / no.
    var holdDelete by remember { mutableStateOf<Long?>(null) }
    fun exitDeleting() { deleting = false; marked = emptySet() }

    BackHandler(enabled = deleting) { exitDeleting() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Database", onBack) {
                TrashCanButton(trashCount, onTrash)
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Data settings", tint = TutColors.Muted) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Prediction curves", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(14.dp)).background(TutColors.Green)
                        .clickable(onClick = onPredict).padding(vertical = 16.dp),
                )
                Text(
                    "Prediction model", color = TutColors.Green, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(14.dp)).background(TutColors.Surface)
                        .border(2.dp, TutColors.Green, RoundedCornerShape(14.dp))
                        .clickable(onClick = onModel).padding(vertical = 16.dp),
                )
            }
            if (!sessions.isNullOrEmpty()) SearchBar(search, onSearch)
            val shown = sessions?.let { filterByName(it, search) }
            when {
                sessions == null || shown == null -> Unit
                shown.isEmpty() && sessions.isNotEmpty() -> Text(
                    "No data blocks with an exercise matching \"${search.trim()}\".",
                    color = TutColors.Muted, modifier = Modifier.padding(24.dp),
                )
                sessions.isEmpty() -> Column {
                    Text(
                        "No data yet. Record some with New data on the main page.",
                        color = TutColors.Muted, modifier = Modifier.padding(24.dp),
                    )
                    if (settings.customData) AddBlockButton { onAdd(System.currentTimeMillis()) }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    // contentPadding (not padding) so a marked block's red glow isn't clipped.
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(shown, key = { _, it -> it.session.id }) { i, s ->
                        val id = s.session.id
                        Column {
                            // Holding a block (time from settings) asks whether to delete just that block.
                            HoldToAct(
                                if (deleting) 0 else settings.deleteBlockHoldMs, TutColors.Red, onHeld = { holdDelete = id },
                                showFill = false,
                            ) {
                                SessionCard(s, settings, deleting = deleting, marked = id in marked) {
                                    if (deleting) marked = if (id in marked) marked - id else marked + id
                                    else onOpen(id)
                                }
                            }
                            // Custom data: a + under every block adds an older one right below it
                            // (not while searching: the block shown below may not be the next one).
                            if (settings.customData && !deleting && search.isBlank()) {
                                AddBlockButton { onAdd(timeBelow(s.session.startedAt, sessions.getOrNull(i + 1)?.session?.startedAt)) }
                            }
                        }
                    }
                    // Room for the delete button / bar so it never covers the last block.
                    item { Spacer(Modifier.height(90.dp)) }
                }
            }
        }

        if (deleting) {
            DeleteBar(
                count = marked.size,
                modifier = Modifier.align(Alignment.BottomCenter),
                onDelete = { if (marked.isNotEmpty()) confirmDelete = true },
                onCancel = { exitDeleting() },
            )
        } else if (!sessions.isNullOrEmpty()) {
            DeleteHoldButton(
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp),
                onUnlocked = { deleting = true },
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Move this data to the trash?",
            onYes = {
                onDelete(marked)
                confirmDelete = false
                exitDeleting()
            },
            onNo = {
                confirmDelete = false
                exitDeleting()
            },
        )
    }

    holdDelete?.let { id ->
        val block = sessions?.firstOrNull { it.session.id == id }?.session
        ConfirmDialog(
            title = "Delete this data block" +
                (block?.let { " from ${Fmt.date(it.startedAt)}, ${Fmt.timeShort(it.startedAt)}" } ?: "") +
                "? It goes to the trash can.",
            onYes = { onDelete(setOf(id)); holdDelete = null },
            onNo = { holdDelete = null },
            yes = "Delete", no = "Cancel",
        )
    }
}

/** Search box above the big data blocks: filters them by exercise name. */
@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text("Search by exercise name", color = TutColors.Outline) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = TutColors.Muted) },
        trailingIcon = if (query.isEmpty()) null else {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear search", tint = TutColors.Muted) } }
        },
        textStyle = TextStyle(color = TutColors.Text),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp),
    )
}

/**
 * Blocks with an exercise whose name contains [query], ignoring case, spaces and dashes
 * ("pullup" finds "pull-ups"). Every word typed has to match, in any exercise of the block.
 */
private fun filterByName(sessions: List<SessionWithExercises>, query: String): List<SessionWithExercises> {
    fun squash(t: String) = t.lowercase().filter { it.isLetterOrDigit() }
    val words = query.trim().split(Regex("\\s+")).map(::squash).filter { it.isNotEmpty() }
    if (words.isEmpty()) return sessions
    return sessions.filter { s ->
        val names = s.exercises.map { squash(it.exercise.displayName) }
        words.all { w -> names.any { it.contains(w) } }
    }
}

/** Trash can in the Database top bar, with how many blocks are in it. */
@Composable
private fun TrashCanButton(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box {
            Icon(Icons.Default.Delete, "Trash ($count)", tint = TutColors.Muted)
            if (count > 0) {
                Text(
                    if (count > 99) "99+" else count.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp)
                        .clip(RoundedCornerShape(50)).background(TutColors.Red).padding(horizontal = 4.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ trash

/**
 * Deleted big data blocks. They stay here, left out of everything else, until restored or
 * deleted for good (which asks first).
 */
@Composable
private fun TrashList(
    trashed: List<SessionWithExercises>,
    settings: AppSettings,
    onBack: () -> Unit,
    onRestore: (Long) -> Unit,
    onDeleteForever: (Set<Long>) -> Unit,
) {
    // Blocks waiting for "delete forever?" to be answered.
    var confirm by remember { mutableStateOf<Set<Long>?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Trash", onBack)
        Text(
            "Deleted data blocks stay here until you delete them from the trash. They don't count anywhere " +
                "else (predictions, progress colours) while they're here. Restore puts a block back.",
            color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
        if (trashed.isEmpty()) {
            Text("The trash is empty.", color = TutColors.Muted, modifier = Modifier.padding(24.dp))
            return@Column
        }
        TextButton(
            onClick = { confirm = trashed.map { it.session.id }.toSet() },
            modifier = Modifier.align(Alignment.End).padding(end = 8.dp),
        ) { Text("Empty trash (${trashed.size})", color = TutColors.Red, fontWeight = FontWeight.SemiBold) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(trashed, key = { it.session.id }) { s ->
                val id = s.session.id
                Column {
                    SessionCard(s, settings, deleting = false, marked = false) {}
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.session.trashedAt?.let { "Deleted ${Fmt.date(it)}, ${Fmt.timeShort(it)}" } ?: "",
                            color = TutColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRestore(id) }) {
                            Text("Restore", color = TutColors.Green, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = { confirm = setOf(id) }) {
                            Text("Delete forever", color = TutColors.Red, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
    confirm?.let { ids ->
        ConfirmDialog(
            title = if (ids.size == 1) "Permanently delete this data block? This can't be undone."
            else "Permanently delete all ${ids.size} data blocks in the trash? This can't be undone.",
            onYes = { onDeleteForever(ids); confirm = null },
            onNo = { confirm = null },
            yes = "Delete forever", no = "Cancel",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(s: SessionWithExercises, settings: AppSettings, deleting: Boolean, marked: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val color = when {
        marked -> TutColors.Red
        deleting -> Color.White
        else -> s.session.color.toColor()
    }
    val black = !marked && !deleting && s.session.color == BlockColor.BLACK
    val border = if (black) TutColors.Warning else if (deleting || s.session.color != null) color else TutColors.Outline
    Box(
        Modifier.fillMaxWidth()
            .then(if (marked) Modifier.shadow(14.dp, shape, ambientColor = TutColors.Red, spotColor = TutColors.Red) else Modifier)
            .clip(shape)
            .background(
                if (marked) TutColors.Red.copy(alpha = 0.14f).compositeOver(TutColors.Surface)
                else if (black) TutColors.Black else TutColors.Surface
            )
            .border(if (marked || black) 2.dp else 1.dp, border, shape)
            .clickable(onClick = onClick)
    ) {
        Row {
            Box(Modifier.width(6.dp).height(86.dp).background(color))
            Column(Modifier.padding(12.dp).weight(1f)) {
                Text(Fmt.date(s.session.startedAt), color = TutColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(Fmt.timeShort(s.session.startedAt), color = TutColors.Muted, fontSize = 13.sp)
                s.session.sleepHours?.let { Text(sleepText(it), color = TutColors.Muted, fontSize = 12.sp) }
                s.session.recovery?.let { Text(recoveryText(it), color = TutColors.Muted, fontSize = 12.sp) }
                if (s.session.excluded) {
                    Text("Excluded from predictions", color = TutColors.Orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                if (s.exercises.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        s.exercises.forEach { e ->
                            val pill = when {
                                marked -> TutColors.Red
                                deleting -> Color.White
                                e.exercise.color != null -> e.exercise.color.inkColor()
                                else -> TutColors.Muted
                            }
                            val warn = !marked && !deleting && e.exercise.color == BlockColor.BLACK
                            val top = topSetText(e.sets, settings)
                            Pill(
                                (if (warn) "\u26A0 " else "") + e.exercise.displayName + (top?.let { " \u00B7 $it" } ?: ""),
                                pill,
                            )
                        }
                    }
                }
            }
        }
        if (black) OvertrainingBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
        if (marked) {
            Text(
                "Delete", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .clip(RoundedCornerShape(50)).background(TutColors.Red)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

private fun sleepText(hours: Double) = "${fmtNumber(hours)} ${if (hours == 1.0) "hour" else "hours"} of sleep"

private fun recoveryText(r: RecoveryFeeling) = "Recovery: ${r.label.lowercase()}"

/**
 * When a block added under one starting at [above] goes: a day earlier, or halfway to the next
 * older block ([below]) when that is less than a day away, so it lands right under it.
 */
private fun timeBelow(above: Long, below: Long?): Long {
    val dayBefore = above - 24 * 3_600_000L
    return if (below == null || dayBefore > below) dayBefore else below + (above - below) / 2
}

/** The + that adds a big data block in custom data mode. */
@Composable
private fun AddBlockButton(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(TutColors.SurfaceHigh)
                .border(1.dp, TutColors.Green, CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Add, "Add a data block below", tint = TutColors.Green, modifier = Modifier.size(20.dp)) }
    }
}

/** Small trash button that must be held for 3 seconds to enter delete mode. */
@Composable
internal fun DeleteHoldButton(modifier: Modifier, onUnlocked: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val unlocked by rememberUpdatedState(onUnlocked)
    Box(
        modifier.size(52.dp).clip(CircleShape).background(TutColors.SurfaceHigh)
            .border(1.dp, TutColors.Red.copy(alpha = 0.6f), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        val start = withFrameMillis { it }
                        while (true) {
                            progress = ((withFrameMillis { it } - start) / 3000f).coerceAtMost(1f)
                            if (progress >= 1f) { unlocked(); break }
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                    progress = 0f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Delete, "Hold 3 seconds to delete data", tint = TutColors.Red, modifier = Modifier.size(22.dp))
        if (progress > 0f) {
            Canvas(Modifier.size(52.dp)) {
                drawArc(
                    TutColors.Red, -90f, 360f * progress, useCenter = false,
                    topLeft = Offset(3f, 3f), size = Size(size.width - 6f, size.height - 6f), style = Stroke(6f),
                )
            }
        }
    }
}

/** Bottom bar shown in delete mode: how many blocks are marked, plus Delete and Cancel. */
@Composable
internal fun DeleteBar(count: Int, modifier: Modifier, onDelete: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(16.dp)).background(TutColors.SurfaceHigh)
            .border(1.dp, TutColors.Outline, RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Leave delete mode", tint = TutColors.Muted) }
        Text(
            "$count selected", color = TutColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Delete", color = if (count > 0) Color.White else TutColors.Muted,
            fontWeight = FontWeight.Bold, fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                .background(if (count > 0) TutColors.Red else TutColors.Outline)
                .clickable(enabled = count > 0, onClick = onDelete)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

// ------------------------------------------------------------------ expanded big block

@Composable
private fun SessionDetail(
    app: TutApp,
    s: SessionWithExercises,
    audio: NoteAudio,
    onBack: () -> Unit,
    onResume: () -> Unit,
    onOpenExercise: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pickingColor by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deletingNote by remember { mutableStateOf<Pair<Int, SessionNote>?>(null) }
    var curves by remember { mutableStateOf<List<CurveItem>>(emptyList()) }
    var showCurves by remember { mutableStateOf(false) }
    val settings by app.settings.settings.collectAsState()
    val context = LocalContext.current
    var addingExercise by remember { mutableStateOf(false) }
    var fatigue by remember { mutableStateOf<Map<Long, Fatigue.Level>>(emptyMap()) }
    LaunchedEffect(s, settings) {
        fatigue = withContext(Dispatchers.IO) { Fatigue.all(app.repo.allWorkouts(), settings) }
    }
    LaunchedEffect(s) {
        curves = withContext(Dispatchers.IO) { s.exercises.mapNotNull { curveFor(app, it.exercise) } }
    }

    /** Custom data: pick a new date, then a new time, for this block. */
    fun pickDateTime() {
        val cal = Calendar.getInstance().apply { timeInMillis = s.session.startedAt }
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, hour, minute ->
                cal.set(y, m, d, hour, minute, 0)
                cal.set(Calendar.MILLISECOND, 0)
                scope.launch(Dispatchers.IO) {
                    app.repo.setSessionStartedAt(s.session.id, cal.timeInMillis)
                    // Moving a workout changes which one comes before which: recolour everything.
                    app.ops.recolorAll()
                }
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(null, onBack) {
            ColorWheelButton(onUnlocked = { pickingColor = true })
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(14.dp).clip(CircleShape).background(s.session.color.toColor())
                        .then(if (s.session.color == BlockColor.BLACK) Modifier.border(1.dp, TutColors.Warning, CircleShape) else Modifier)
                )
                Spacer(Modifier.width(8.dp))
                Column(if (settings.customData) Modifier.clip(RoundedCornerShape(8.dp)).clickable { pickDateTime() } else Modifier) {
                    Text(Fmt.date(s.session.startedAt), color = TutColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        Fmt.time(s.session.startedAt) + if (s.session.colorManual) " · colour set by hand" else "",
                        color = TutColors.Muted, fontSize = 13.sp,
                    )
                    s.session.sleepHours?.let { Text(sleepText(it), color = TutColors.Muted, fontSize = 13.sp) }
                    s.session.recovery?.let { Text(recoveryText(it), color = TutColors.Muted, fontSize = 13.sp) }
                    if (settings.customData) {
                        Text("Tap to change the date and time", color = TutColors.Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (pickingColor) {
                ColorPicker(
                    current = s.session.color,
                    onPick = { color ->
                        pickingColor = false
                        scope.launch(Dispatchers.IO) {
                            if (color == null) {
                                app.repo.setSessionColor(s.session.id, s.session.color, manual = false)
                                app.ops.refreshSessionColor(s.session.id)
                            } else {
                                app.repo.setSessionColor(s.session.id, color, manual = true)
                            }
                        }
                    },
                    onCancel = { pickingColor = false },
                )
            }

            Text("Recordings", color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            s.notes.forEachIndexed { i, note ->
                key(note.id) {
                    // Can't delete the recording that is recording right now.
                    val hold = if (audio.recordingPath == note.path) 0 else settings.deleteRecordingHoldMs
                    HoldToAct(hold, TutColors.Red, onHeld = { deletingNote = i to note }) {
                        Column {
                            Text(
                                "Recording ${i + 1} \u00B7 ${Fmt.timeShort(note.createdAt)}",
                                color = TutColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                            )
                            // Recording again on an existing note adds to the end of it.
                            VoiceNoteBar(audio, note.path, newFile = { File(note.path) }, onCreated = {})
                        }
                    }
                }
            }
            Text(
                "New recording", color = TutColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            VoiceNoteBar(
                audio, null,
                newFile = { File(app.notesDir, "session_${s.session.id}_${System.currentTimeMillis()}.wav") },
                onCreated = { path ->
                    scope.launch(Dispatchers.IO) { app.repo.addSessionNote(s.session.id, path, System.currentTimeMillis()) }
                },
            )

            Text("Exercises", color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            if (s.exercises.isEmpty()) Text("No exercises recorded.", color = TutColors.Muted, modifier = Modifier.padding(bottom = 10.dp))
            s.exercises.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { e -> SmallBlock(e, fatigue[e.exercise.id], Modifier.weight(1f)) { onOpenExercise(e.exercise.id) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (settings.customData) {
                Text(
                    "+ Add exercise", color = TutColors.Green, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, TutColors.Green, RoundedCornerShape(12.dp))
                        .clickable { addingExercise = true }.padding(vertical = 12.dp),
                )
            }

            if (s.exercises.isNotEmpty()) {
                AdaptationCurveButton(
                    curves.isNotEmpty(),
                    "Needs an earlier workout of the same exercise, both with working sets, to compare.",
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                ) { showCurves = true }
            }
            // Held for a second, the block is left out of (or put back into) the prediction model.
            HoldToConfirmButton(
                if (s.session.excluded) "Excluded from predictions · hold 1 second to include it again"
                else "Hold 1 second to exclude from predictions",
                holdMs = 1_000, color = TutColors.Orange,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                onConfirmed = { scope.launch(Dispatchers.IO) { app.repo.setSessionExcluded(s.session.id, !s.session.excluded) } },
            )
            Spacer(Modifier.height(16.dp))
        }
        // Pinned to the bottom of the screen, below the scrolling content.
        // Any block can be picked up again on the recording page.
        ResumeRecordingButton(
            settings.resumeHoldMs.toLong(),
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
            onResume,
        )
        HoldToConfirmButton(
            "Hold 3 seconds to delete this data block",
            holdMs = 3_000,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            onConfirmed = { confirmDelete = true },
        )
    }

    deletingNote?.let { (i, note) ->
        ConfirmDialog(
            title = "Delete recording ${i + 1}? This can't be undone.",
            onYes = {
                if (audio.player.file?.absolutePath == note.path) audio.player.pause()
                scope.launch(Dispatchers.IO) { app.repo.deleteSessionNote(note) }
                deletingNote = null
            },
            onNo = { deletingNote = null },
            yes = "Delete", no = "Keep",
        )
    }
    if (showCurves) AdaptationCurveDialog(curves, settings) { showCurves = false }
    if (addingExercise) {
        RenameDialog("", onDismiss = { addingExercise = false }, title = "New exercise") { raw ->
            addingExercise = false
            val name = WordAliases.nameFor(raw, settings.wordAliases) ?: raw
            scope.launch {
                // Created at the block's own time, so it sorts with the block.
                val ex = withContext(Dispatchers.IO) { app.repo.createExercise(s.session.id, name, s.session.startedAt) }
                onOpenExercise(ex.id)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Are you sure you want to delete data?",
            onYes = {
                confirmDelete = false
                audio.release()
                scope.launch(Dispatchers.IO) { app.repo.deleteSession(s.session.id) }
                onBack()
            },
            onNo = { confirmDelete = false },
        )
    }
}

/** "Resume recording" on an unsaved big data block: held for [holdMs], or tapped when it's 0. */
@Composable
private fun ResumeRecordingButton(holdMs: Long, modifier: Modifier, onResume: () -> Unit) {
    if (holdMs > 0) {
        HoldToConfirmButton(
            "Hold ${Fmt.seconds(holdMs)} to resume recording", modifier, holdMs = holdMs, color = TutColors.Green,
            onConfirmed = onResume,
        )
    } else {
        Text(
            "Resume recording", color = TutColors.Green, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, TutColors.Green, RoundedCornerShape(12.dp))
                .clickable(onClick = onResume).padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SmallBlock(e: ExerciseWithSets, fatigue: Fatigue.Level?, modifier: Modifier, onClick: () -> Unit) {
    val color = e.exercise.color
    val black = color == BlockColor.BLACK
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (black) TutColors.Black
                else if (color != null) color.toColor().copy(alpha = 0.15f) else TutColors.Surface
            )
            .border(2.dp, color.inkColor(), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(e.exercise.displayName, color = TutColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        val done = e.sets.count { it.hasData }
        Text(if (done == 1) "1 set" else "$done sets", color = TutColors.Muted, fontSize = 12.sp)
        if (fatigue != null && fatigue.level > 0) {
            Text("Fatigue ${fatigue.level}", color = fatigueColor(fatigue.level), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (black) OvertrainingBadge(Modifier.padding(top = 6.dp))
    }
}

/** Fatigue levels: none is muted, then orange, then red from level 3. */
private fun fatigueColor(level: Int): Color = when {
    level <= 0 -> TutColors.Muted
    level <= 2 -> TutColors.Orange
    else -> TutColors.Red
}

/**
 * This workout's fatigue level: counted automatically from the workouts of this exercise before
 * it, or set by hand (0 to 5). "Auto" goes back to counting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FatigueChooser(app: TutApp, ex: Exercise, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var level by remember { mutableStateOf<Fatigue.Level?>(null) }
    LaunchedEffect(ex, settings) {
        level = withContext(Dispatchers.IO) {
            val same = app.repo.allWorkouts().filter { Predictions.key(it.name) == Predictions.key(ex.name) }
            if (same.isEmpty()) null
            else Fatigue.levels(same, Predictions.scaleFor(ex.name, same, settings), settings)[ex.id]
        }
    }
    fun set(v: Int?) = scope.launch(Dispatchers.IO) { app.repo.setExerciseFatigue(ex.id, v) }
    Column(Modifier.padding(top = 8.dp)) {
        val l = level
        Text(
            if (l == null) "Fatigue level" else "Fatigue level ${l.level}" + if (l.manual) " (set by hand)" else " (automatic)",
            color = fatigueColor(l?.level ?: 0).takeIf { (l?.level ?: 0) > 0 } ?: TutColors.Text,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val manual = ex.fatigueManual
            FatigueChip("Auto", manual == null) { set(null) }
            (0..5).forEach { v -> FatigueChip(v.toString(), manual == v) { set(v) } }
        }
    }
}

@Composable
private fun FatigueChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.Black else TutColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) TutColors.Orange else TutColors.SurfaceHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Tap to mark this workout as about the most progress possible from one workout to the next.
 * The prediction model won't predict more progress than marked workouts made.
 */
@Composable
private fun LimitMark(marked: Boolean, onToggle: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            if (marked) "★ Near the limit of progress" else "☆ Mark as near the limit of progress",
            color = if (marked) Color.Black else TutColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (marked) TutColors.Gold else Color.Transparent)
                .border(1.dp, TutColors.Gold, RoundedCornerShape(50)).clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(
            if (marked) "Predictions won't go above the progress made here."
            else "Tap if this was about the most progress one workout can make over the last one.",
            color = TutColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Small "warning: overtraining" sign on black blocks (weaker 3+ times in a row). */
@Composable
private fun OvertrainingBadge(modifier: Modifier = Modifier) {
    Text(
        "\u26A0 Overtraining", color = TutColors.Black, fontSize = 11.sp, fontWeight = FontWeight.Black,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(TutColors.Warning)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Full warning in an exercise that turned black: what it means and what to do. */
@Composable
private fun OvertrainingWarning(name: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TutColors.Black)
            .border(2.dp, TutColors.Warning, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("\u26A0", color = TutColors.Warning, fontSize = 30.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Warning: you may be overtraining", color = TutColors.Warning, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "$name got weaker 3 or more times in a row. Your body isn't recovering between workouts: " +
                    "take more rest days, or lighten the load, before the next one.",
                color = TutColors.Text, fontSize = 13.sp,
            )
        }
    }
}

/** A colour wheel that must be held for 3 seconds before it unlocks. */
@Composable
private fun ColorWheelButton(onUnlocked: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val unlocked by rememberUpdatedState(onUnlocked)
    Box(
        Modifier.size(44.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch {
                    val start = withFrameMillis { it }
                    while (true) {
                        progress = ((withFrameMillis { it } - start) / 3000f).coerceAtMost(1f)
                        if (progress >= 1f) { unlocked(); break }
                    }
                }
                tryAwaitRelease()
                job.cancel()
                progress = 0f
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp)) {
            val wheel = Brush.sweepGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
            )
            drawCircle(wheel, radius = size.minDimension / 2 - 6f)
            if (progress > 0f) {
                drawArc(
                    TutColors.Text, -90f, 360f * progress, useCenter = false,
                    topLeft = Offset(2f, 2f), size = Size(size.width - 4f, size.height - 4f), style = Stroke(4f),
                )
            }
        }
    }
}

@Composable
private fun ColorPicker(current: BlockColor?, onPick: (BlockColor?) -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(TutColors.SurfaceHigh).padding(12.dp)
    ) {
        Text("Choose block colour", color = TutColors.Text, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BlockColor.entries.forEach { c ->
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(c.toColor())
                        .border(
                            if (c == current) 3.dp else 1.dp,
                            if (c == current) TutColors.Text else if (c == BlockColor.BLACK) TutColors.Warning else Color.Transparent,
                            CircleShape,
                        )
                        .clickable { onPick(c) }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onPick(null) }) { Text("Automatic", color = TutColors.Muted) }
            TextButton(onClick = onCancel) { Text("Cancel", color = TutColors.Muted) }
        }
    }
}

// ------------------------------------------------------------------ expanded small block

@Composable
private fun ExerciseDetail(
    app: TutApp,
    s: SessionWithExercises,
    e: ExerciseWithSets,
    settings: AppSettings,
    audio: NoteAudio,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var previous by remember { mutableStateOf<Exercise?>(null) }
    var editing by remember { mutableStateOf<WorkSet?>(null) }
    var curve by remember { mutableStateOf<CurveItem?>(null) }
    var showCurve by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(e.exercise.id, e.exercise.name, e.exercise.color) {
        previous = withContext(Dispatchers.IO) { app.repo.previousSameExercise(e.exercise) }
        curve = withContext(Dispatchers.IO) { curveFor(app, e.exercise) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(null, onBack) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 14.dp)) {
                Text("Total time", color = TutColors.Muted, fontSize = 10.sp)
                Text(Fmt.minutesSeconds(s.session.recordedMs ?: e.exercise.totalTimeMs), color = TutColors.Green, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            var renameProgress by remember { mutableFloatStateOf(0f) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The hold fills a green bar over the name itself.
                Text(
                    e.exercise.displayName, color = TutColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false).drawWithContent {
                        drawContent()
                        if (renameProgress > 0f) {
                            drawRect(TutColors.Green.copy(alpha = 0.3f), size = size.copy(width = size.width * renameProgress))
                        }
                    },
                )
                Spacer(Modifier.width(10.dp))
                RenameHoldButton(settings.renameExerciseHoldMs, onProgress = { renameProgress = it }) { renaming = true }
            }
            val prev = previous
            Text(
                if (prev == null) "First time recorded"
                else {
                    val h = Progress.restHoursRounded(prev.createdAt, e.exercise.createdAt)
                    if (h == 0L) "Same day as the last ${e.exercise.displayName}"
                    else "$h hours rest (${h / 24} ${if (h == 24L) "day" else "days"})"
                },
                color = TutColors.Muted, fontSize = 14.sp,
            )
            FatigueChooser(app, e.exercise, settings)
            LimitMark(e.exercise.limitMarked) {
                scope.launch(Dispatchers.IO) { app.repo.setExerciseLimitMarked(e.exercise.id, !e.exercise.limitMarked) }
            }
            e.exercise.color?.let { c ->
                Pill(c.label, c.inkColor(), Modifier.padding(top = 6.dp))
                if (c == BlockColor.BLACK) OvertrainingWarning(e.exercise.displayName, Modifier.padding(top = 10.dp))
            }

            AdaptationCurveButton(
                curve != null,
                if (prev == null) "Needs an earlier ${e.exercise.displayName} to compare with."
                else "Needs working sets in both this and the last ${e.exercise.displayName}.",
                Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { showCurve = true }

            Spacer(Modifier.height(12.dp))
            VoiceNoteBar(
                audio, e.exercise.notePath,
                newFile = { File(app.notesDir, "exercise_${e.exercise.id}.wav") },
                onCreated = { path -> scope.launch(Dispatchers.IO) { app.repo.setExerciseNote(e.exercise.id, path) } },
            )

            Spacer(Modifier.height(8.dp))
            for (kind in SetKind.entries) {
                val configured = if (kind == SetKind.WARMUP) settings.warmupSets else settings.workingSets
                val existing = e.sets.filter { it.kind == kind }
                val count = maxOf(configured, existing.maxOfOrNull { it.number } ?: 0)
                for (n in 1..count) {
                    val set = existing.firstOrNull { it.number == n }
                        ?: WorkSet(id = 0, exerciseId = e.exercise.id, kind = kind, number = n)
                    SetCard(set, settings) { editing = set }
                }
            }
            Text("Tap a set to edit it.", color = TutColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 16.dp))
        }
    }

    if (renaming) {
        RenameDialog(e.exercise.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            if (name.isNotBlank() && name != e.exercise.name) newName = name
        }
    }
    newName?.let { name ->
        ConfirmDialog(
            title = "Rename \u201C${e.exercise.displayName}\u201D to \u201C$name\u201D? Its progress colours will be " +
                "compared with your other \u201C$name\u201D workouts.",
            onYes = {
                scope.launch(Dispatchers.IO) { app.ops.renameExercise(e.exercise.id, name) }
                newName = null
            },
            onNo = { newName = null },
            yes = "Save", no = "Cancel",
        )
    }
    curve?.let { c -> if (showCurve) AdaptationCurveDialog(listOf(c), settings) { showCurve = false } }
    editing?.let { set ->
        SetEditDialog(
            set = set, title = "${e.exercise.displayName} · ${set.label}", settings = settings,
            onDismiss = { editing = null },
            onSave = { edited ->
                editing = null
                scope.launch(Dispatchers.IO) {
                    val real = if (edited.id == 0L) {
                        val created = app.repo.getOrCreateSet(edited.exerciseId, edited.kind, edited.number)
                        edited.copy(id = created.id)
                    } else edited
                    // Sets edited on the data page are stamped with their original session time.
                    app.ops.saveSet(real, now = real.completedAt ?: s.session.startedAt, touch = false)
                }
            },
        )
    }
}

@Composable
private fun SetCard(set: WorkSet, s: AppSettings, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(TutColors.Surface)
            .clickable(onClick = onClick).padding(12.dp)
    ) {
        Text("(${set.label})", color = TutColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        LabeledRow("Rest time", set.restSec?.let { formatDuration(it) } ?: "—")
        LabeledRow("Weight", set.weight?.let { "${Fmt.number(it)} ${(set.unit ?: s.weightUnit).short}" } ?: "—")
        LabeledRow("Reps", set.reps?.toString() ?: "—")
        LabeledRow("TUT", set.tutSec?.let { formatDuration(it) } ?: "—")
        if (s.rirEnabled) {
            val text = rirText(set, s)?.removePrefix("RIR - ") ?: "—"
            LabeledRow("RIR", text, valueColor = if (set.rirLevel != null) set.rirLevel.toColor() else TutColors.Text)
        }
    }
}

/**
 * Holding anywhere on [content] for [holdMs] calls [onHeld], with a red fill showing how far
 * along the hold is ([showFill] off: no fill, the hold still works). Taps still reach the buttons inside; a finished hold swallows the release
 * so it doesn't also press one. Moving the finger (scrolling) cancels. 0 = off.
 */
@Composable
private fun HoldToAct(
    holdMs: Int, color: Color, onHeld: () -> Unit, modifier: Modifier = Modifier, showFill: Boolean = true,
    content: @Composable () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val held by rememberUpdatedState(onHeld)
    Box(
        modifier
            .pointerInput(holdMs) {
                if (holdMs <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val start = SystemClock.uptimeMillis()
                    var fired = false
                    try {
                        while (true) {
                            val ev = withTimeoutOrNull(16) { awaitPointerEvent(PointerEventPass.Initial) }
                            val change = ev?.changes?.firstOrNull { it.id == down.id }
                            if (ev != null && change == null) break
                            if (change != null) {
                                if (fired) change.consume()
                                if (!change.pressed) break
                                if (!fired && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                            }
                            if (!fired) {
                                progress = ((SystemClock.uptimeMillis() - start) / holdMs.toFloat()).coerceAtMost(1f)
                                if (progress >= 1f) {
                                    fired = true
                                    progress = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    held()
                                }
                            }
                        }
                    } finally {
                        progress = 0f
                    }
                }
            }
            .drawWithContent {
                drawContent()
                if (showFill && progress > 0f) drawRect(color.copy(alpha = 0.25f), size = size.copy(width = size.width * progress))
            }
    ) { content() }
}

/** Small "Hold to rename" box; reports how far along the hold is so the name can show it. 0 = a tap. */
@Composable
private fun RenameHoldButton(holdMs: Int, onProgress: (Float) -> Unit, onHeld: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val held by rememberUpdatedState(onHeld)
    val progress by rememberUpdatedState(onProgress)
    Text(
        if (holdMs > 0) "Hold to rename" else "Rename", color = TutColors.Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(TutColors.SurfaceHigh)
            .border(1.dp, TutColors.Green, RoundedCornerShape(8.dp))
            .pointerInput(holdMs) {
                detectTapGestures(onPress = {
                    if (holdMs <= 0) {
                        if (tryAwaitRelease()) held()
                        return@detectTapGestures
                    }
                    val job = scope.launch {
                        val start = withFrameMillis { it }
                        while (true) {
                            val p = ((withFrameMillis { it } - start) / holdMs.toFloat()).coerceAtMost(1f)
                            progress(p)
                            if (p >= 1f) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                progress(0f)
                                held()
                                break
                            }
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                    progress(0f)
                })
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** The top working set in short: "185 lb × 8" (reps) or "185 lb · 0:40" (time under tension). */
private fun topSetText(sets: List<WorkSet>, s: AppSettings): String? {
    val top = Progress.topWorkingSet(sets, s.weightUnit) ?: return null
    val weight = "${Fmt.number(top.weight ?: return null)} ${(top.unit ?: s.weightUnit).short}"
    return when {
        top.reps != null && (s.effortMode == EffortMode.REPS || top.tutSec == null) -> "$weight \u00D7 ${top.reps}"
        top.tutSec != null -> "$weight \u00B7 ${formatDuration(top.tutSec)}"
        else -> weight
    }
}
