package com.example.tut.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tut.TutApp
import com.example.tut.data.AppSettings
import com.example.tut.data.BestWorkout
import com.example.tut.data.CurvePoint
import com.example.tut.data.CurveUnit
import com.example.tut.data.LockedPrediction
import com.example.tut.data.Plan
import com.example.tut.data.PlannedWorkout
import com.example.tut.data.PredictionCheck
import com.example.tut.data.Predictions
import com.example.tut.data.ProgressScale
import com.example.tut.data.RirLevel
import com.example.tut.data.ProgressionRule
import com.example.tut.data.Supercompensation
import androidx.compose.ui.graphics.StrokeCap
import com.example.tut.data.WorkSet
import com.example.tut.data.Progress
import com.example.tut.data.Workout
import com.example.tut.parse.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DAY_MS = 24 * 3_600_000L

/**
 * Prediction curves: a model trained on every workout in the database predicts how strong the
 * best set of a workout will be compared with last time. "Plan a workout" locks in a typed-in
 * workout and later checks it against the real one; "Best next workout" suggests the workout
 * predicted to make the most progress.
 */
@Composable
fun PredictScreen(app: TutApp, onBack: () -> Unit) {
    val version by app.repo.version.collectAsState()
    val settings by app.settings.settings.collectAsState()
    var workouts by remember { mutableStateOf<List<Workout>>(emptyList()) }
    var model by remember { mutableStateOf<Predictions.Model?>(null) }
    var locked by remember { mutableStateOf<List<LockedPrediction>>(emptyList()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var justLocked by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    // Relearns whenever the database changes, so new workouts count straight away.
    LaunchedEffect(version, settings) {
        val w = withContext(Dispatchers.IO) { app.repo.allWorkouts() }
        locked = withContext(Dispatchers.IO) { app.repo.predictions() }
        workouts = w
        model = withContext(Dispatchers.Default) { Predictions.train(w, settings) }
    }

    // Parameters edited on this page go into the exercise's progression rule in settings.
    fun saveRule(rule: ProgressionRule) {
        val cur = app.settings.current
        val others = cur.progressionRules.filterNot { Predictions.key(it.name) == Predictions.key(rule.name) }
        app.settings.save(cur.copy(progressionRules = others + rule))
    }

    fun lock(p: LockedPrediction) {
        scope.launch {
            justLocked = withContext(Dispatchers.IO) { app.repo.addPrediction(p) }
            tab = 0
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Prediction curves", onBack)
        val m = model
        if (m == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TutColors.Green) }
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            ModelStatus(m)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabButton("Plan a workout", tab == 0, Modifier.weight(1f)) { tab = 0 }
                TabButton("Best next workout", tab == 1, Modifier.weight(1f)) { tab = 1 }
                TabButton("Where am I now?", tab == 2, Modifier.weight(1f)) { tab = 2 }
            }
            when (tab) {
                0 -> {
                    PlanTab(m, settings, onLock = ::lock, onSaveRule = ::saveRule)
                    LockedList(app, m, workouts, locked, settings, justLocked)
                }
                1 -> BestTab(m, settings, onLock = ::lock, onSaveRule = ::saveRule)
                else -> WhereTab(m, settings)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ModelStatus(m: Predictions.Model) {
    val text = when {
        !m.ready ->
            "Not enough data yet. The model learns from each workout compared with the one before it of the same " +
                "exercise, and needs at least ${Predictions.MIN_ROWS} of those (you have ${m.rows}). Keep recording " +
                "working sets with weight and reps (or time under tension)."
        m.typicalMiss == null ->
            "Learned from ${m.rows} workouts compared with the one before. That's still very little, so treat " +
                "predictions as rough guesses."
        else ->
            "Learned from ${m.rows} workouts compared with the one before. On workouts it wasn't shown while " +
                "learning, it was off by ${Fmt.number(round1(m.typicalMiss))}% on average."
    }
    Text(
        text, color = if (m.ready) TutColors.Muted else TutColors.Orange, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TutColors.Surface).padding(12.dp),
    )
}

@Composable
private fun TabButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.Black else TutColors.Muted, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) TutColors.Green else TutColors.Surface)
            .border(1.dp, if (selected) TutColors.Green else TutColors.Outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}

// ------------------------------------------------------------------ plan a workout

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanTab(m: Predictions.Model, s: AppSettings, onLock: (LockedPrediction) -> Unit, onSaveRule: (ProgressionRule) -> Unit) {
    var name by rememberSaveable { mutableStateOf(m.names.firstOrNull() ?: "") }
    val h = m.history(name)
    val last = h?.last
    val now = remember { System.currentTimeMillis() }

    var days by rememberSaveable { mutableIntStateOf(2) }
    var weight by rememberSaveable { mutableStateOf("") }
    var workingSets by rememberSaveable { mutableIntStateOf(3) }
    var warmupSets by rememberSaveable { mutableIntStateOf(0) }
    var restSec by rememberSaveable { mutableIntStateOf(120) }
    var tutSec by rememberSaveable { mutableIntStateOf(0) } // 0 = none picked
    var rir by rememberSaveable { mutableStateOf(RirLevel.MODERATE) }
    var filledFor by rememberSaveable { mutableStateOf<Long?>(null) }

    // Picking an exercise fills the boxes with what was done last time.
    LaunchedEffect(last?.exerciseId) {
        val hist = h ?: return@LaunchedEffect
        if (last == null || filledFor == last.exerciseId) return@LaunchedEffect
        filledFor = last.exerciseId
        val plan = Predictions.planOf(last, hist.workouts.getOrNull(h.workouts.size - 2)?.at ?: last.at, s)
        days = Predictions.daysSince(last.at, now).coerceIn(Predictions.restDays)
        weight = Fmt.number(m.nextWeight(hist))
        workingSets = plan.workingSets.coerceAtLeast(1)
        warmupSets = plan.warmupSets
        restSec = plan.restSec?.let { (it / 15).roundToInt() * 15 } ?: 120
        tutSec = hist.lastBest.tutSec?.let { (it / 5.0).roundToInt() * 5 } ?: 0
        rir = plan.rir?.let { rirLevelFor(it, s) } ?: RirLevel.MODERATE
    }

    Card(Modifier.padding(top = 12.dp)) {
        Text("Workout you plan to do", color = TutColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(40) }, singleLine = true,
            label = { Text("Exercise") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        if (m.names.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                m.names.take(12).forEach { n ->
                    Chip(n, Predictions.key(n) == Predictions.key(name)) { name = n }
                }
            }
        }
        if (last == null) {
            Text(
                if (name.isBlank()) "Pick an exercise you've done before."
                else "No earlier “$name” with a working set (weight and reps or time) to compare with.",
                color = TutColors.Orange, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
            return@Card
        }
        val hist = h!!
        Text(
            "Last time: ${Fmt.date(last.at)} · best set ${bestSetText(hist.lastBest, hist.scale, s)}" +
                limitText(m, last.name),
            color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
        )
        RestWarning(m, last.name)
        StepRow("Days of rest since last time", days, Predictions.restDays) { days = it }
        Text(
            "Planned for ${Fmt.date(last.at + days * DAY_MS)}", color = TutColors.Muted, fontSize = 12.sp,
        )
        OutlinedTextField(
            value = weight, onValueChange = { v -> weight = v.filter { it.isDigit() || it == '.' }.take(7) },
            singleLine = true, label = { Text("Working weight (${s.weightUnit.short})") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        StepRow("Working sets", workingSets, 1..10) { workingSets = it }
        StepRow("Rest between sets", restSec, 0..600, step = 15, text = { formatDuration(it) }) { restSec = it }
        StepRow("Time under tension", tutSec, 0..1_800, step = 5, text = { if (it == 0) "—" else formatDuration(it) }) { tutSec = it }
        PlannedTutNote(m, name, hist, weight.toDoubleOrNull(), tutSec, days, workingSets, restSec, if (s.rirEnabled) rirValueOf(rir, s) else null)
        if (s.rirEnabled) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Target RIR", color = TutColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                listOf(RirLevel.LOW, RirLevel.MODERATE, RirLevel.HIGH).forEach { level ->
                    Spacer(Modifier.width(6.dp))
                    Chip(level.label, rir == level) { rir = level }
                }
            }
        }

        val w = weight.toDoubleOrNull()?.takeIf { it > 0 }
        Button(
            onClick = {
                // The level counts as its number from the RIR settings.
                val rirValue = if (s.rirEnabled) rirValueOf(rir, s) else null
                val plan = Plan(days * 24.0, workingSets, warmupSets, restSec.toDouble(), rirValue?.toDouble())
                val curve = m.curve(name, plan) ?: return@Button
                val timed = hist.scale.timed
                onLock(
                    LockedPrediction(
                        id = 0, name = last.name, createdAt = System.currentTimeMillis(),
                        plannedFor = last.at + days * DAY_MS, baselineExerciseId = last.exerciseId, baselineAt = last.at,
                        baselineStrength = hist.lastStrength,
                        plan = PlannedWorkout(w!!, s.weightUnit, workingSets, warmupSets, restSec, rirValue, timed, tutSec.takeIf { it > 0 }),
                        predictedPct = Predictions.at(curve, plan.gapHours), curve = curve,
                        trainedOn = m.rows, typicalMiss = m.typicalMiss, verdict = null, source = "manual",
                    )
                )
            },
            enabled = m.ready && w != null,
            colors = ButtonDefaults.buttonColors(containerColor = TutColors.Green, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp),
        ) { Text("Lock in this workout", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        if (!m.ready) Text("The model needs more data before it can predict.", color = TutColors.Muted, fontSize = 12.sp)
        else if (w == null) Text("Enter the weight you plan to use.", color = TutColors.Muted, fontSize = 12.sp)
    }
    if (last != null) ParametersPanel(last.name, m, s, onSaveRule)
}

// ------------------------------------------------------------------ locked predictions

@Composable
private fun LockedList(
    app: TutApp, m: Predictions.Model, workouts: List<Workout>, locked: List<LockedPrediction>, s: AppSettings,
    justLocked: Long?,
) {
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<LockedPrediction?>(null) }
    val checks = remember(locked, workouts, m) { locked.associate { it.id to m.check(it, workouts, s) } }

    Text(
        "Locked predictions", color = TutColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 20.dp),
    )
    if (locked.isEmpty()) {
        Text(
            "None yet. Lock in a workout above, do it, and it's compared with the prediction here.",
            color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
        )
    } else {
        val done = checks.values.filterNotNull()
        if (done.isNotEmpty()) {
            val said = locked.mapNotNull { it.verdict }
            Text(
                "Checked ${done.size}: ${done.count { it.close }} close, " +
                    "average miss ${Fmt.number(round1(done.map { abs(it.missPct) }.average()))}%." +
                    if (said.isNotEmpty()) " You said ${said.count { it }} of ${said.size} were accurate." else "",
                color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    locked.forEach { p ->
        PredictionCard(
            p, checks[p.id], m, s, startOpen = p.id == justLocked || checks[p.id]?.let { p.verdict == null } == true,
            onVerdict = { v -> scope.launch(Dispatchers.IO) { app.repo.setPredictionVerdict(p.id, v) } },
            onDelete = { deleting = p },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Delete this ${p.name} prediction?",
            onYes = {
                scope.launch(Dispatchers.IO) { app.repo.deletePrediction(p.id) }
                deleting = null
            },
            onNo = { deleting = null },
            yes = "Delete", no = "Keep",
        )
    }
}

@Composable
private fun PredictionCard(
    p: LockedPrediction, check: PredictionCheck?, m: Predictions.Model, s: AppSettings, startOpen: Boolean,
    onVerdict: (Boolean?) -> Unit, onDelete: () -> Unit,
) {
    var open by remember(p.id) { mutableStateOf(startOpen) }
    val plan = p.plan
    val unit = plan.unit.short
    val scale = m.scale(p.name) ?: ProgressScale.Reps(plan.unit)
    val predictedStrength = scale.apply(p.baselineStrength, p.predictedPct)
    // A PR: stronger than every earlier workout of this exercise up to the one it started from.
    val bestBefore = m.history(p.name)?.let { h -> h.workouts.indices.filter { h.workouts[it].at <= p.baselineAt }.maxOfOrNull { h.strengths[it] } }
    val pr = bestBefore != null && predictedStrength > bestBefore + 1e-9

    Card(Modifier.padding(top = 10.dp).clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = TutColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Locked ${Fmt.date(p.createdAt)} · for ${Fmt.date(p.plannedFor)}" +
                        if (p.source == "best") " · suggested" else "",
                    color = TutColors.Muted, fontSize = 12.sp,
                )
            }
            when {
                check == null -> Pill("Waiting", TutColors.Muted)
                check.close -> Pill("Close", TutColors.Green)
                else -> Pill("Not close", TutColors.Red)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete prediction", tint = TutColors.Muted) }
        }
        Text(
            "Predicted best set: ${Fmt.number(plan.weight)} $unit × ${scale.effortText(scale.effortAt(predictedStrength, plan.weight))}" +
                " (${scale.changeText(p.predictedPct)} vs last time)" + if (pr) " · PR" else "",
            color = if (p.predictedPct >= 0) TutColors.Green else TutColors.Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
        plan.tutSec?.let { planned ->
            Text(
                "Planned: ${Fmt.number(plan.weight)} $unit × ${formatDuration(planned)} TUT",
                color = TutColors.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (open) {
            Text(planText(plan, p.plannedGapHours, s), color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            PredictionChart(
                p.curve, m.supercompensation(p.name, p.curve).through(p.plannedGapHours, p.predictedPct), s.curveUnit, scale::changeText,
                markers = listOfNotNull(
                    Marker(p.plannedGapHours, TutColors.Blue),
                    check?.let { Marker(it.gapHours, TutColors.Gold, "done") },
                ),
                actual = check?.let { it.gapHours to it.actualPct },
                modifier = Modifier.fillMaxWidth().height(300.dp).padding(top = 10.dp),
            )
        }
        if (check == null) {
            Text(
                "Not done yet. Record a ${p.name} workout and it's compared with this prediction automatically.",
                color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            CheckResult(check, scale, s, onVerdict, p.verdict)
        }
    }
}

@Composable
private fun CheckResult(
    c: PredictionCheck, scale: ProgressScale, s: AppSettings, onVerdict: (Boolean?) -> Unit, verdict: Boolean?,
) {
    val unit = s.weightUnit.short
    val setWeight = if (c.bestSet.weight == null) 0.0 else Progress.weightIn(c.bestSet, s.weightUnit)
    val actualEffort = scale.effortOf(c.bestSet)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(TutColors.SurfaceHigh).padding(10.dp),
    ) {
        Text("Done ${Fmt.date(c.workout.at)}, after ${daysText(c.gapHours)} of rest", color = TutColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val sets = c.sets
        if (sets != null) {
            val verdicts = sets.points.mapIndexed { i, p -> "${i + 1} " + if (p >= 1.0) "better" else if (p <= 0.0) "worse" else "same" }
            LabeledRow("Sets", verdicts.joinToString(" · ") + " → ${sets.setsPct.roundToInt()}%")
            sets.rating?.takeIf { abs(sets.pct - sets.setsPct) >= 0.5 }?.let { r ->
                LabeledRow("With your rating", "${r.label.lowercase()}: ${sets.pct.roundToInt()}%")
            }
            LabeledRow("Recovery score", "${sets.pct.roundToInt()}% (${scale.changeText(c.actualPct)}; 50% = same as last time)")
        }
        LabeledRow("Actual best set", "${Fmt.number(setWeight)} $unit × ${scale.effortText(actualEffort)}" + if (sets == null) " (${scale.changeText(c.actualPct)})" else "")
        if (sets != null) LabeledRow("Predicted", "${(50 + c.predictedPct).roundToInt()}% (${scale.changeText(c.predictedPct)})")
        else LabeledRow("Predicted for that", "${Fmt.number(setWeight)} $unit × ${scale.effortText(c.predictedEffortAtThatWeight)} (${scale.changeText(c.predictedPct)})")
        Text(
            if (c.close) "Close to the prediction: off by ${scale.changeText(abs(c.missPct)).drop(1)}."
            else "Not close to the prediction: ${if (c.missPct > 0) "more" else "less"} progress than predicted, by ${scale.changeText(abs(c.missPct)).drop(1)}.",
            color = if (c.close) TutColors.Green else TutColors.Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (verdict == null) {
            Text("Was this prediction accurate?", color = TutColors.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onVerdict(true) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = TutColors.Green, contentColor = Color.Black),
                ) { Text("Yes", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { onVerdict(false) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = TutColors.Red, contentColor = Color.Black),
                ) { Text(if (c.close) "No" else "No, confirm it didn't match", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        } else {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (verdict) "You said: accurate" else "You confirmed: did not match",
                    color = if (verdict) TutColors.Green else TutColors.Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Change", color = TutColors.Muted, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onVerdict(null) }.padding(6.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ best next workout

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BestTab(m: Predictions.Model, s: AppSettings, onLock: (LockedPrediction) -> Unit, onSaveRule: (ProgressionRule) -> Unit) {
    if (!m.ready) {
        Text(
            "Suggestions start once the model has enough data (see above).",
            color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
        )
        return
    }
    var name by rememberSaveable { mutableStateOf(m.names.firstOrNull() ?: "") }
    var best by remember { mutableStateOf<BestWorkout?>(null) }
    var working by remember { mutableStateOf(true) }
    LaunchedEffect(name, m) {
        working = true
        best = withContext(Dispatchers.Default) { m.best(name, System.currentTimeMillis(), s) }
        working = false
    }

    FlowRow(
        Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        m.names.forEach { n -> Chip(n, Predictions.key(n) == Predictions.key(name)) { name = n } }
    }
    ParametersPanel(m.history(name)?.last?.name ?: name, m, s, onSaveRule)
    val b = best
    if (working) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TutColors.Green) }
        return
    }
    if (b == null) {
        Text(
            "No suggestion for $name: the last one was more than 10 days ago, past the end of the curve. " +
                "Do a $name workout and suggestions pick up from there.",
            color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
        )
        return
    }
    val unit = s.weightUnit.short
    val scale = m.scale(b.name) ?: return
    Card(Modifier.padding(top = 12.dp)) {
        Text("Best next ${b.name}", color = TutColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Predicted ${scale.changeText(b.predictedPct)} vs last time (${Fmt.date(b.last.at)}, best set " +
                "${bestSetText(m.history(b.name)!!.lastBest, scale, s)})" + limitText(m, b.name),
            color = if (b.predictedPct >= 0) TutColors.Green else TutColors.Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp),
        )
        RestWarning(m, b.name)
        LabeledRow("Do it on", "${Fmt.date(b.last.at + (b.plan.gapHours * 3_600_000).toLong())} (${daysText(b.plan.gapHours)} of rest)")
        LabeledRow("Best set", "${Fmt.number(b.weight)} $unit × ${scale.effortText(b.effort)}")
        LabeledRow("Working sets", b.plan.workingSets.toString())
        LabeledRow("Rest between sets", formatDuration((b.plan.restSec ?: 0.0).roundToInt()))
        b.plan.rir?.let { LabeledRow("RIR", rirLevelFor(it, s).label) }
        PredictionChart(
            b.curve, m.supercompensation(b.name, b.curve).through(b.plan.gapHours, b.predictedPct), s.curveUnit, scale::changeText, markers = listOf(Marker(b.plan.gapHours, TutColors.Blue, "best")),
            modifier = Modifier.fillMaxWidth().height(300.dp).padding(top = 10.dp),
        )
        Text(
            "The rest, sets, rest between sets and RIR that the model predicts give the most progress. " +
                (if (scale is ProgressScale.Tut) "The weight follows your ladder: one step up once last time reached the ${formatDuration(scale.goalSec.roundToInt())} goal. "
                else "The weight is set so the best set is about as many reps as last time. ") +
                "It only knows what's in your database, so it can't suggest anything you haven't tried yet.",
            color = TutColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedButton(
            onClick = {
                val h = m.history(b.name) ?: return@OutlinedButton
                onLock(
                    LockedPrediction(
                        id = 0, name = b.name, createdAt = System.currentTimeMillis(),
                        plannedFor = b.last.at + (b.plan.gapHours * 3_600_000).toLong(),
                        baselineExerciseId = b.last.exerciseId, baselineAt = b.last.at, baselineStrength = h.lastStrength,
                        plan = PlannedWorkout(
                            b.weight, s.weightUnit, b.plan.workingSets, b.plan.warmupSets,
                            b.plan.restSec?.roundToInt(), b.plan.rir?.roundToInt(), scale.timed,
                            tutSec = if (scale.timed) b.effort.roundToInt() else null,
                        ),
                        predictedPct = b.predictedPct, curve = b.curve, trainedOn = m.rows, typicalMiss = m.typicalMiss,
                        verdict = null, source = "best",
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) { Text("Lock in this workout", color = TutColors.Green, fontWeight = FontWeight.Bold) }
    }
}

// ------------------------------------------------------------------ where am I now

/**
 * The supercompensation curve after the last workout of an exercise, with a line at today: how
 * far into the recovery it is, and how many more days until the predicted peak. The curve is the
 * model's prediction for doing the same workout as last time again after each rest length.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhereTab(m: Predictions.Model, s: AppSettings) {
    if (!m.ready) {
        Text(
            "The curve shows once the model has enough data (see above).",
            color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
        )
        return
    }
    var name by rememberSaveable { mutableStateOf(m.names.firstOrNull() ?: "") }
    FlowRow(
        Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        m.names.forEach { n -> Chip(n, Predictions.key(n) == Predictions.key(name)) { name = n } }
    }
    val h = m.history(name) ?: return
    val last = h.last
    val now = remember { System.currentTimeMillis() }
    val curve = remember(m, name) {
        m.curve(name, Predictions.planOf(last, h.workouts.getOrNull(h.workouts.size - 2)?.at ?: last.at, s))
    } ?: return
    val fit = remember(m, name, curve) { m.supercompensation(name, curve) }
    val since = (now - last.at) / 3_600_000.0
    val toPeak = fit.scaleHours - since
    val today = fit.at(since)
    val scale = h.scale

    Card(Modifier.padding(top = 12.dp)) {
        Text("Where am I now: ${last.name}", color = TutColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Last workout ${Fmt.date(last.at)}, ${daysText(since)} ago · best set ${bestSetText(h.lastBest, scale, s)}",
            color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
        )
        val (status, color) = when {
            toPeak > 12 && today < 0 -> "Still recovering: today is ${scale.changeText(today)} vs last time. " +
                "Rest ${daysText(toPeak)} more to reach the peak (${Fmt.date(last.at + (fit.scaleHours * 3_600_000).toLong())})." to TutColors.Red
            toPeak > 12 -> "On the way up: today is ${scale.changeText(today)} vs last time. " +
                "Rest ${daysText(toPeak)} more to reach the peak (${Fmt.date(last.at + (fit.scaleHours * 3_600_000).toLong())})." to TutColors.Green
            toPeak >= -12 -> "At the peak now: ${scale.changeText(today)} vs last time. A good day to train it." to TutColors.Gold
            else -> "The peak was ${daysText(-toPeak)} ago. Today is ${scale.changeText(today)} vs last time; " +
                "the gain is fading, so train it soon." to TutColors.Orange
        }
        Text(status, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        RestWarning(m, last.name)
        PredictionChart(
            curve, fit, s.curveUnit, scale::changeText,
            markers = listOf(Marker(since, TutColors.Blue, "today")),
            modifier = Modifier.fillMaxWidth().height(300.dp).padding(top = 10.dp),
            caption = "Line: the supercompensation curve after your last ${last.name}, if you do the same workout " +
                "again. The blue line is today" + (if (since > Predictions.gapGrid.last()) " (past the end of the curve)" else "") + ".",
        )
    }
}

// ------------------------------------------------------------------ chart

/** A dotted line down the chart at [hours] of rest, with an optional [label] at the top. */
private class Marker(val hours: Double, val color: Color, val label: String? = null)

/**
 * The supercompensation curve: after a workout, fitness first dips below last time (fatigue),
 * climbs back, overshoots (supercompensation, gold dot at its peak), then settles back. It is the
 * classic curve fitted to the model's predictions, which are drawn as dots: what the model
 * predicts for coming back after each rest length. [actual] is the real result.
 */
@Composable
private fun PredictionChart(
    curve: List<CurvePoint>, fit: Supercompensation.Fit, unit: CurveUnit, label: (Double) -> String,
    markers: List<Marker>, modifier: Modifier, actual: Pair<Double, Double>? = null,
    caption: String = "Line: the supercompensation curve, through the predicted result at the blue line.",
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = TutColors.Muted, fontSize = 10.sp)
    Column(modifier) {
        Text("Supercompensation curve", color = TutColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
            if (curve.isEmpty()) return@Canvas
            val left = 46.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            val top = 14.dp.toPx()
            val right = size.width - 6.dp.toPx()
            // 10 days, even for predictions locked in when the curve went further.
            val xMax = minOf(curve.last().hours, Predictions.gapGrid.last())
            val samples = (0..xMax.toInt() step 2).map { it.toDouble() to fit.at(it.toDouble()) }
            val values = curve.map { it.pct } + samples.map { it.second } + listOfNotNull(actual?.second)
            val yMax = maxOf(values.max(), 1.0) * 1.2
            val yMin = minOf(values.min(), -1.0) * 1.2
            fun x(h: Double) = (left + h.coerceIn(0.0, xMax) / xMax * (right - left)).toFloat()
            fun y(p: Double) = (bottom - (p - yMin) / (yMax - yMin) * (bottom - top)).toFloat()

            // Baseline: as strong as last time.
            drawLine(TutColors.Outline, Offset(left, y(0.0)), Offset(right, y(0.0)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            listOf(yMax / 1.2, 0.0, yMin / 1.2).forEach { v ->
                val t = measurer.measure(label(v), labelStyle)
                drawText(t, topLeft = Offset(left - t.size.width - 4.dp.toPx(), y(v) - t.size.height / 2))
            }
            // Day ticks every 2 days.
            var d = 2
            while (d * 24.0 <= xMax) {
                val xh = x(d * 24.0)
                drawLine(TutColors.Outline.copy(alpha = 0.5f), Offset(xh, bottom), Offset(xh, bottom + 4.dp.toPx()), 2f)
                val t = measurer.measure(if (unit == CurveUnit.DAYS) "${d}d" else "${d * 24}h", labelStyle)
                drawText(t, topLeft = Offset(xh - t.size.width / 2, bottom + 5.dp.toPx()))
                d += 2
            }

            markers.forEach { mk ->
                val xm = x(mk.hours)
                drawLine(mk.color, Offset(xm, top), Offset(xm, bottom), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                mk.label?.let { text ->
                    val t = measurer.measure(text, TextStyle(color = mk.color, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    drawText(t, topLeft = Offset((xm + 3.dp.toPx()).coerceAtMost(right - t.size.width), 0f))
                }
            }

            // The model's own predictions, one dot per rest length.
            curve.filter { it.hours <= xMax }.forEach { drawCircle(TutColors.Muted.copy(alpha = 0.7f), 2.5f.dp.toPx(), Offset(x(it.hours), y(it.pct))) }

            // The curve: red in the fatigue dip, green above last time, gold dot at the peak.
            for (k in 0 until samples.size - 1) {
                val (h1, p1) = samples[k]
                val (h2, p2) = samples[k + 1]
                val color = if ((p1 + p2) / 2 >= 0) TutColors.Green else TutColors.Red
                drawLine(color, Offset(x(h1), y(p1)), Offset(x(h2), y(p2)), 6f, cap = StrokeCap.Round)
            }
            val peakX = x(fit.scaleHours)
            val peakY = y(fit.amplitude)
            drawCircle(TutColors.Gold, 7.dp.toPx() / 2 + 2f, Offset(peakX, peakY))
            actual?.let { (h, p) ->
                drawCircle(TutColors.Text, 6.dp.toPx() / 2 + 2f, Offset(x(h), y(p)))
                drawCircle(TutColors.Gold, 6.dp.toPx() / 2 + 2f, Offset(x(h), y(p)), style = Stroke(4f))
            }
        }
        Text(
            "$caption Peak after " +
                "${daysText(fit.scaleHours)}, ${label(fit.amplitude)}. Dots: what the model predicts for each rest length." +
                if (fit.assumed) " Your workouts don't show yet how rest changes the result, so the shape is the classic one." else "",
            color = TutColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ---------------------------------------------------------------- exercise parameters

private val weightSteps = listOf(0.25f, 0.5f, 1f, 1.25f, 2f, 2.5f, 5f, 10f)

/**
 * The exercise's ladder settings, right on this page: time under tension goal, weight step, and
 * the trade between weight and time ("6:00 at a weight = 1 lb more for 2:00"). Saved into the
 * per-exercise progression rules in settings; the model relearns straight away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParametersPanel(name: String, m: Predictions.Model, s: AppSettings, onSave: (ProgressionRule) -> Unit) {
    if (name.isBlank()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val rule = s.progressionFor(name)
    var goal by remember(name, rule) { mutableIntStateOf(rule.tutGoalSec) }
    var step by remember(name, rule) { mutableStateOf(rule.loadStep) }
    var ownTrade by remember(name, rule) { mutableStateOf(rule.hasTrade) }
    var from by remember(name, rule) { mutableIntStateOf(rule.tradeFromSec ?: rule.tutGoalSec) }
    var added by remember(name, rule) { mutableStateOf(rule.tradeWeight ?: rule.loadStep) }
    var to by remember(name, rule) { mutableIntStateOf(rule.tradeToSec ?: (rule.tutGoalSec / 3)) }
    val unit = s.weightUnit.short

    Card(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("Parameters for $name", color = TutColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (open) "\u25B2" else "\u25BC", color = TutColors.Muted, fontSize = 12.sp)
        }
        val scale = m.scale(name)
        if (scale is ProgressScale.Tut) {
            Text(
                "Now: one ${Fmt.number(scale.step)} $unit step is worth ${formatDuration(scale.stepValueSec.roundToInt())} of time under tension " +
                    when (scale.stepValueSource) {
                        ProgressScale.StepValueSource.SET -> "(your numbers)."
                        ProgressScale.StepValueSource.LEARNED -> "(learned from your workouts)."
                        ProgressScale.StepValueSource.GUESSED -> "(a guess: half the goal, until you set it or add weight in a workout)."
                    },
                color = TutColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        } else if (scale != null) {
            Text(
                "$name is measured by reps right now. These apply once most of its workouts have time under tension.",
                color = TutColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (!open) return@Card

        StepRow("Time under tension goal", goal, 15..1_800, step = 15, text = { formatDuration(it) }) { goal = it }
        Text("Weight step ($unit)", color = TutColors.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            weightSteps.forEach { w -> Chip(Fmt.number(w.toDouble()), w == step) { step = w } }
        }

        Text("Weight vs time", color = TutColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Learn from my workouts", !ownTrade) { ownTrade = false }
            Chip("Set it myself", ownTrade) { ownTrade = true }
        }
        if (ownTrade) {
            Text(
                "If I can do a weight for ${formatDuration(from)}, I can do ${Fmt.number(added.toDouble())} $unit more for ${formatDuration(to)}.",
                color = TutColors.Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp),
            )
            StepRow("Time at a weight", from, 15..1_800, step = 15, text = { formatDuration(it) }) { from = it }
            Text("Weight added ($unit)", color = TutColors.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                weightSteps.forEach { w -> Chip(Fmt.number(w.toDouble()), w == added) { added = w } }
            }
            StepRow("Time with it added", to, 0..1_800, step = 15, text = { formatDuration(it) }) { to = it }
            if (to >= from) {
                Text("The time with weight added has to be shorter.", color = TutColors.Orange, fontSize = 12.sp)
            } else {
                val perStep = (from - to) / added * step
                Text(
                    "So one ${Fmt.number(step.toDouble())} $unit step is worth ${formatDuration(perStep.roundToInt())} of time under tension.",
                    color = TutColors.Muted, fontSize = 12.sp,
                )
            }
        }
        Button(
            onClick = {
                onSave(
                    ProgressionRule(
                        name = name, tutGoalSec = goal, loadStep = step,
                        tradeFromSec = if (ownTrade) from else null,
                        tradeWeight = if (ownTrade) added else null,
                        tradeToSec = if (ownTrade) to else null,
                    )
                )
            },
            enabled = !ownTrade || to < from,
            colors = ButtonDefaults.buttonColors(containerColor = TutColors.Green, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("Save parameters", fontWeight = FontWeight.Bold) }
    }
}

// ------------------------------------------------------------------ small pieces

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(TutColors.Surface)
            .border(1.dp, TutColors.Outline, RoundedCornerShape(16.dp)).padding(14.dp),
    ) { content() }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.Black else TutColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) TutColors.Green else TutColors.SurfaceHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun StepRow(
    label: String, value: Int, range: IntRange, step: Int = 1, text: (Int) -> String = { it.toString() },
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TutColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        StepButton("−", value - step >= range.first) { onChange(value - step) }
        Text(text(value), color = TutColors.Text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
        StepButton("+", value + step <= range.last) { onChange(value + step) }
    }
}

/**
 * Under the time-under-tension row: what the model predicts the best set can hold at the planned
 * weight, next to the time picked. The time picked doesn't change the prediction (like the weight).
 */
@Composable
private fun PlannedTutNote(
    m: Predictions.Model, name: String, h: Predictions.History, weight: Double?, tutSec: Int,
    days: Int, workingSets: Int, restSec: Int, rir: Int?,
) {
    val scale = h.scale
    val predicted = if (m.ready && weight != null && weight > 0) {
        m.curve(name, Plan(days * 24.0, workingSets, 0, restSec.toDouble(), rir?.toDouble()))?.let { curve ->
            val effort = scale.effortAt(scale.apply(h.lastStrength, Predictions.at(curve, days * 24.0)), weight)
            // On the reps scale a timed set counts one rep per SEC_PER_REP seconds.
            if (scale.timed) effort else effort * ProgressScale.SEC_PER_REP
        }
    } else null
    val text = when {
        predicted == null -> if (tutSec == 0) "Pick the time under tension you plan for your best set (— = none)." else null
        tutSec == 0 -> "Predicted at this weight: about ${formatDuration(predicted.roundToInt())} time under tension."
        else -> {
            val diff = tutSec - predicted
            "Predicted at this weight: about ${formatDuration(predicted.roundToInt())}. " + when {
                abs(diff) < 5 -> "Your plan matches the prediction."
                diff > 0 -> "Your plan is ${formatDuration(diff.roundToInt())} more than predicted."
                else -> "Your plan is ${formatDuration((-diff).roundToInt())} less than predicted."
            }
        }
    }
    if (text != null) Text(text, color = TutColors.Muted, fontSize = 12.sp)
}

private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

private fun daysText(hours: Double): String = Fmt.number(round1(hours / 24)).let { if (it == "1") "1 day" else "$it days" }

private fun bestSetText(set: WorkSet, scale: ProgressScale, s: AppSettings): String =
    "${Fmt.number(set.weight ?: 0.0)} ${(set.unit ?: s.weightUnit).short} × ${scale.effortText(scale.effortOf(set))}"

/**
 * Said plainly when every workout the model learned from had about the same rest: it then can't
 * know what rest does, so its curve is flat and the rest picked doesn't change the prediction.
 */
@Composable
private fun RestWarning(m: Predictions.Model, name: String) {
    if (!m.ready || m.restVaries(name)) return
    val range = m.restRange(name) ?: return
    val apart = if (round1(range.start / 24) == round1(range.endInclusive / 24)) daysText(range.start)
    else "${Fmt.number(round1(range.start / 24))} to ${daysText(range.endInclusive)}"
    Text(
        "Your $name workouts so far were $apart apart (${daysText(m.restAverage(name) ?: range.start)} on average). " +
            "That's less than a day of difference, so the model can't learn yet how rest changes the result: the curve " +
            "is mostly flat, and the rest days picked barely change the prediction. To teach it, add workouts with " +
            "clearly different rest (e.g. custom data blocks 2, 4 or 5 days apart).",
        color = TutColors.Orange, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp))
            .background(TutColors.Orange.copy(alpha = 0.12f)).padding(10.dp),
    )
}

/** " · limit +0:40 TUT" when workouts marked near the limit cap this exercise's predictions. */
private fun limitText(m: Predictions.Model, name: String): String {
    val limit = m.limit(name) ?: return ""
    val scale = m.scale(name) ?: return ""
    return " · limit ${scale.changeText(limit)}"
}

/** The RIR level whose number in settings is closest to [value]. */
private fun rirLevelFor(value: Double, s: AppSettings): RirLevel =
    listOf(RirLevel.HIGH to s.rirHigh, RirLevel.MODERATE to s.rirModerate, RirLevel.LOW to s.rirLow)
        .minBy { abs(it.second - value) }.first

private fun rirValueOf(level: RirLevel, s: AppSettings): Int = when (level) {
    RirLevel.HIGH -> s.rirHigh
    RirLevel.MODERATE -> s.rirModerate
    RirLevel.LOW -> s.rirLow
}

private fun planText(p: PlannedWorkout, gapHours: Double, s: AppSettings): String = listOfNotNull(
    "${daysText(gapHours)} of rest",
    "${p.workingSets} working sets",
    p.tutSec?.let { "${formatDuration(it)} time under tension" },
    p.restSec?.let { "${formatDuration(it)} between sets" },
    p.rir?.let { "RIR ${rirLevelFor(it.toDouble(), s).label.lowercase()}" },
).joinToString(" · ")
