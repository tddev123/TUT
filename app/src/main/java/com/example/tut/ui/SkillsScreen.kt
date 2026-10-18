package com.example.tut.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tut.TutApp
import com.example.tut.data.Skill
import com.example.tut.data.SkillLevel
import com.example.tut.data.SkillLog
import com.example.tut.parse.SkillParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class SkillData(val skill: Skill, val logs: List<SkillLog>)

/**
 * The Skills database page: one static block per skill, each a line graph of skill level
 * by day. The + under the last block (held) adds a skill.
 */
@Composable
fun SkillsScreen(app: TutApp, onBack: () -> Unit, onSettings: () -> Unit) {
    val version by app.repo.version.collectAsState()
    val settings by app.settings.settings.collectAsState()
    var skills by remember { mutableStateOf<List<SkillData>?>(null) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(version) {
        skills = withContext(Dispatchers.IO) { app.repo.skills().map { SkillData(it, app.repo.skillLogs(it.id)) } }
    }
    val defaultCapacity = settings.skillCapacityHours
    val open = skills?.firstOrNull { it.skill.id == selected }

    fun nameTaken(name: String, except: Long? = null) =
        skills.orEmpty().any { it.skill.id != except && it.skill.name.trim().equals(name.trim(), ignoreCase = true) }

    if (open != null) {
        SkillDetail(
            open, open.skill.capacityOr(defaultCapacity), onBack = { selected = null },
            nameTaken = { nameTaken(it, open.skill.id) },
            onRename = { name -> scope.launch(Dispatchers.IO) { app.repo.renameSkill(open.skill.id, name) } },
            onDeleteLog = { id -> scope.launch(Dispatchers.IO) { app.repo.deleteSkillLog(id) } },
            onSetCapacity = { c -> scope.launch(Dispatchers.IO) { app.repo.setSkillCapacity(open.skill.id, c) } },
        )
        return
    }

    var deleting by remember { mutableStateOf(false) }
    var marked by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    fun exitDeleting() { deleting = false; marked = emptySet() }
    BackHandler { if (deleting) exitDeleting() else onBack() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Skills database", onBack) {
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Data settings", tint = TutColors.Muted) }
            }
            val list = skills ?: return@Column
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.skill.id }) { s ->
                    val id = s.skill.id
                    SkillCard(s, s.skill.capacityOr(defaultCapacity), deleting, id in marked) {
                        if (deleting) marked = if (id in marked) marked - id else marked + id
                        else selected = id
                    }
                }
                if (!deleting) {
                    item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (list.isEmpty()) {
                                Text(
                                    "No skills yet. " + if (settings.addSkillHoldMs > 0) "Hold + for ${Fmt.seconds(settings.addSkillHoldMs.toLong())} to add one."
                                    else "Tap + to add one.",
                                    color = TutColors.Muted, modifier = Modifier.padding(16.dp),
                                )
                            }
                            AddSkillButton(settings.addSkillHoldMs.toLong()) { adding = true }
                        }
                    }
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }

        if (deleting) {
            DeleteBar(
                count = marked.size,
                modifier = Modifier.align(Alignment.BottomCenter),
                onDelete = { if (marked.isNotEmpty()) confirmDelete = true },
                onCancel = { exitDeleting() },
            )
        } else if (!skills.isNullOrEmpty()) {
            DeleteHoldButton(
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp),
                onUnlocked = { deleting = true },
            )
        }
    }

    if (adding) {
        SkillNameDialog("New skill", "", nameTaken = { nameTaken(it) }, onDismiss = { adding = false }) { name ->
            adding = false
            scope.launch(Dispatchers.IO) { app.repo.createSkill(name, defaultCapacity.toDouble(), System.currentTimeMillis()) }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${if (marked.size == 1) "this skill" else "these ${marked.size} skills"} and all their hours?",
            onYes = {
                val ids = marked
                scope.launch(Dispatchers.IO) { ids.forEach { app.repo.deleteSkill(it) } }
                confirmDelete = false
                exitDeleting()
            },
            onNo = { confirmDelete = false; exitDeleting() },
        )
    }
}

@Composable
private fun SkillCard(s: SkillData, capacity: Double, deleting: Boolean, marked: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val points = remember(s, capacity) { SkillLevel.series(s.skill.createdAt, s.logs, capacity, System.currentTimeMillis()) }
    val accent = when {
        marked -> TutColors.Red
        deleting -> Color.White
        else -> TutColors.Blue
    }
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (marked) TutColors.Red.copy(alpha = 0.14f) else TutColors.Surface)
            .border(if (marked) 2.dp else 1.dp, if (deleting) accent else TutColors.Outline, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.skill.name, color = TutColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (marked) {
                Text(
                    "Delete", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(TutColors.Red).padding(horizontal = 10.dp, vertical = 3.dp),
                )
            } else {
                LevelText(points.lastOrNull()?.level ?: 0.0)
            }
        }
        SkillGraph(points, accent, Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp))
    }
}

@Composable
private fun LevelText(level: Double) {
    val color = when {
        level > 0 -> TutColors.Green
        level < 0 -> TutColors.Red
        else -> TutColors.Muted
    }
    Text("Level ${if (level > 0) "+" else ""}${Fmt.number(level)}", color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

private val dayFmt get() = SimpleDateFormat("MMM d", Locale.getDefault())

/** Skill level (y) by day (x). A dashed line marks level 0. */
@Composable
private fun SkillGraph(points: List<SkillLevel.Point>, color: Color, modifier: Modifier) {
    val levels = points.map { it.level }
    val top = maxOf(0.0, levels.maxOrNull() ?: 0.0).let { if (it == 0.0 && (levels.minOrNull() ?: 0.0) == 0.0) 1.0 else it }
    val bottom = minOf(0.0, levels.minOrNull() ?: 0.0)
    Column(modifier) {
        Row(Modifier.weight(1f)) {
            // y axis: skill level
            Column(Modifier.width(36.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(Fmt.number(top), color = TutColors.Muted, fontSize = 10.sp)
                Text(Fmt.number(bottom), color = TutColors.Muted, fontSize = 10.sp)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val range = (top - bottom).takeIf { it > 0 } ?: 1.0
                fun y(v: Double) = ((top - v) / range * size.height).toFloat()
                fun x(i: Int) = if (points.size <= 1) size.width / 2 else i * size.width / (points.size - 1)
                drawLine(TutColors.Outline, Offset(0f, 0f), Offset(0f, size.height), 2f)
                drawLine(TutColors.Outline, Offset(0f, size.height), Offset(size.width, size.height), 2f)
                drawLine(
                    TutColors.Muted.copy(alpha = 0.6f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
                if (points.isEmpty()) return@Canvas
                val path = Path()
                points.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(x(i), y(p.level)) else path.lineTo(x(i), y(p.level))
                }
                drawPath(path, color, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val dot = if (points.size <= 31) 6f else 0f
                points.forEachIndexed { i, p ->
                    if (dot > 0f || i == points.lastIndex) drawCircle(color, if (i == points.lastIndex) 9f else dot, Offset(x(i), y(p.level)))
                }
            }
        }
        // x axis: days
        Row(Modifier.fillMaxWidth().padding(start = 36.dp, top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val fmt = dayFmt
            val labels = when {
                points.isEmpty() -> emptyList()
                points.size <= 7 -> points
                else -> listOf(points.first(), points[points.size / 2], points.last())
            }
            if (labels.size == 1) Spacer(Modifier.weight(1f))
            labels.forEach { Text(fmt.format(Date(it.dayStart)), color = TutColors.Muted, fontSize = 10.sp) }
            if (labels.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** The + under the last skill block: adds a skill after being held for [holdMs] (0 = a tap). */
@Composable
private fun AddSkillButton(holdMs: Long, onAdd: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val add by rememberUpdatedState(onAdd)
    Box(
        Modifier.padding(top = 4.dp).size(56.dp).clip(CircleShape).background(TutColors.SurfaceHigh)
            .border(1.dp, TutColors.Blue, CircleShape)
            .pointerInput(holdMs) {
                detectTapGestures(onPress = {
                    if (holdMs <= 0) {
                        if (tryAwaitRelease()) add()
                        return@detectTapGestures
                    }
                    val job = scope.launch {
                        val start = withFrameMillis { it }
                        while (true) {
                            progress = ((withFrameMillis { it } - start) / holdMs.toFloat()).coerceAtMost(1f)
                            if (progress >= 1f) { add(); break }
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                    progress = 0f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Add,
            if (holdMs > 0) "Hold ${Fmt.seconds(holdMs)} to add a skill" else "Add a skill",
            tint = TutColors.Blue, modifier = Modifier.size(30.dp),
        )
        if (progress > 0f) {
            Canvas(Modifier.size(56.dp)) {
                drawArc(
                    TutColors.Blue, -90f, 360f * progress, useCenter = false,
                    topLeft = Offset(3f, 3f), size = Size(size.width - 6f, size.height - 6f), style = Stroke(6f),
                )
            }
        }
    }
}

@Composable
private fun SkillNameDialog(
    title: String, current: String, nameTaken: (String) -> Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit,
) {
    var taken by remember { mutableStateOf<String?>(null) }
    RenameDialog(current, onDismiss, title = taken?.let { "\"$it\" already exists" } ?: title, color = TutColors.Blue) { name ->
        if (nameTaken(name)) taken = name else onSave(name)
    }
}

/** One skill opened up: bigger graph, the numbers, and every log (tap ✕ to remove a wrong one). */
@Composable
private fun SkillDetail(
    s: SkillData, capacity: Double, onBack: () -> Unit, nameTaken: (String) -> Boolean,
    onRename: (String) -> Unit, onDeleteLog: (Long) -> Unit, onSetCapacity: (Double) -> Unit,
) {
    BackHandler(onBack = onBack)
    val now = System.currentTimeMillis()
    // Edited locally (the graph follows straight away) and written once the slider settles.
    var draftCapacity by remember(capacity) { mutableStateOf(capacity) }
    val save by rememberUpdatedState(onSetCapacity)
    LaunchedEffect(draftCapacity) {
        if (draftCapacity != capacity) { delay(400); save(draftCapacity) }
    }
    val points = remember(s, draftCapacity) { SkillLevel.series(s.skill.createdAt, s.logs, draftCapacity, now) }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SkillLog?>(null) }
    val today = points.lastOrNull()?.hours ?: 0.0
    val timeFmt = remember { SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TopBar(null, onBack) }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "${s.skill.name}  ✎", color = TutColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { renaming = true },
                )
                LevelText(points.lastOrNull()?.level ?: 0.0)
                SkillGraph(points, TutColors.Blue, Modifier.fillMaxWidth().height(240.dp).padding(top = 12.dp))
                Spacer(Modifier.height(12.dp))
                TenthsStepper("Daily capacity (hours)", draftCapacity.toFloat(), 0f..16f) { draftCapacity = it.toDouble() }
                LabeledRow("Today", "${SkillParser.speakHours(today)} of ${Fmt.number(draftCapacity)} h")
                LabeledRow("Today's change", Fmt.number(SkillLevel.dailyChange(today, draftCapacity)).let { if (!it.startsWith("-") && it != "0") "+$it" else it })
                LabeledRow("Total hours", Fmt.number(s.logs.sumOf { it.hours }))
                Text("Logged hours", color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                if (s.logs.isEmpty()) Text("Nothing yet. Say \"${s.skill.name.lowercase()} 1 hour\" on New data.", color = TutColors.Muted, fontSize = 13.sp)
            }
        }
        items(s.logs.asReversed(), key = { it.id }) { log ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(timeFmt.format(Date(log.loggedAt)), color = TutColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("+${SkillParser.speakHours(log.hours)}", color = TutColors.Blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { removing = log }) { Icon(Icons.Default.Close, "Remove", tint = TutColors.Muted) }
            }
        }
    }

    if (renaming) {
        SkillNameDialog("Skill name", s.skill.name, nameTaken, onDismiss = { renaming = false }) {
            onRename(it); renaming = false
        }
    }
    removing?.let { log ->
        ConfirmDialog(
            title = "Remove ${SkillParser.speakHours(log.hours)} logged ${timeFmt.format(Date(log.loggedAt))}?",
            onYes = { onDeleteLog(log.id); removing = null },
            onNo = { removing = null },
            yes = "Remove", no = "Keep",
        )
    }
}
