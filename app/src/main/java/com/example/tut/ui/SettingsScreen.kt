package com.example.tut.ui

import android.os.Build
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.input.pointer.pointerInput
import com.example.tut.parse.CommandParser
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tut.TutApp
import com.example.tut.data.Repository
import com.example.tut.DbMode
import kotlin.system.exitProcess
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import com.example.tut.data.Backup
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import android.content.Intent
import android.content.Context
import android.app.Activity
import com.example.tut.data.AppSettings
import com.example.tut.data.SttEngine
import com.example.tut.data.CurveUnit
import com.example.tut.data.BubbleColor
import com.example.tut.data.EffortMode
import com.example.tut.data.FeedbackMode
import com.example.tut.data.FeedbackSound
import com.example.tut.data.WeightUnit
import com.example.tut.data.ProgressionRule
import com.example.tut.data.WordAlias
import com.example.tut.record.Speaker
import com.example.tut.record.VoiceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Settings page: the same full set of settings wherever it is opened from (main page, TUT
 * database, Skills database). Changes are only kept when Save is pressed; the back arrow
 * discards them.
 */
@Composable
fun SettingsScreen(app: TutApp, onBack: () -> Unit) {
    var draft by remember { mutableStateOf(app.settings.current) }
    var savedNotice by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    LaunchedEffect(savedNotice) {
        if (savedNotice) { delay(1500); savedNotice = false }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack) {
            TextButton(onClick = { app.settings.save(draft); savedNotice = true }) {
                Text("Save", color = TutColors.Green, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        if (savedNotice) {
            Text(
                "Settings saved", color = Color.Black, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().background(TutColors.Green).padding(6.dp),
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            BackupSettings(app)
            DataSettings(draft) { draft = it }
            RecordingSettings(draft) { draft = it }
            ActivationWordSettings(draft) { draft = it }
            ProgressionSettings(draft) { draft = it }
            FatigueSettings(draft) { draft = it }
            FeedbackSettings(app, draft) { draft = it }
            ModelSettings(app, draft) { draft = it }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun DataSettings(s: AppSettings, update: (AppSettings) -> Unit) {
    Section("Database") {
        Choice("Weight unit", WeightUnit.entries, s.weightUnit, { it.short }) { update(s.copy(weightUnit = it)) }
        Choice("Measure sets by", EffortMode.entries, s.effortMode, {
            if (it == EffortMode.REPS) "Reps" else "Time under tension"
        }) { update(s.copy(effortMode = it)) }
        Stepper("Warm-up sets", s.warmupSets, 0..AppSettings.MAX_SETS) { update(s.copy(warmupSets = it)) }
        Stepper("Working sets", s.workingSets, 1..AppSettings.MAX_SETS) { update(s.copy(workingSets = it)) }
        Choice("Hold a big data block to delete it", listOf(0, 500, 1_000, 2_000, 3_000, 5_000, 10_000), s.deleteBlockHoldMs, {
            if (it == 0) "Off" else "${Fmt.number(it / 1000.0)} s"
        }) { update(s.copy(deleteBlockHoldMs = it)) }
        Hint("Holding a block on the Database page offers to delete it. Deleted blocks go to the trash can (top of the Database page) and stay there until you delete them from it. Off: blocks can't be held.")
        Choice("Hold a recording to delete it", listOf(0, 500, 1_000, 2_000, 3_000), s.deleteRecordingHoldMs, {
            if (it == 0) "Off" else "${Fmt.number(it / 1000.0)} s"
        }) { update(s.copy(deleteRecordingHoldMs = it)) }
        Hint("Holding a voice recording inside a big data block asks whether to delete it. Off: recordings can't be deleted.")
        Choice("Hold to rename an exercise", listOf(0, 500, 1_000, 2_000, 3_000), s.renameExerciseHoldMs, {
            if (it == 0) "Tap" else "${Fmt.number(it / 1000.0)} s"
        }) { update(s.copy(renameExerciseHoldMs = it)) }
        Hint("Inside an exercise in the database, holding the \"Hold to rename\" box next to its name lets you rename it; you're asked to confirm before it's saved.")
        Toggle("Custom data", s.customData) { update(s.copy(customData = it)) }
        Hint(
            "For entering workouts by hand. A + appears under every big data block in the database: it adds a " +
                "block right below, dated before the one above. Inside a block, tap its date to change the date " +
                "and time, and use + Add exercise to add exercises, then tap their sets to fill them in."
        )
    }
    Section("RIR (reps in reserve)") {
        Toggle("Record RIR", s.rirEnabled) { update(s.copy(rirEnabled = it)) }
        Toggle("Show RIR as a number", s.rirShowNumber) { update(s.copy(rirShowNumber = it)) }
        Stepper("High RIR =", s.rirHigh, 0..20) { update(s.copy(rirHigh = it)) }
        Stepper("Moderate RIR =", s.rirModerate, 0..20) { update(s.copy(rirModerate = it)) }
        Stepper("Low RIR =", s.rirLow, 0..20) { update(s.copy(rirLow = it)) }
    }
    Section("Progress threshold") {
        Hint(
            "Compared with the top working set of the last time the same exercise was done. Up by at least the " +
                "\"stronger\" amount is stronger (green), down by at least the \"weaker\" amount is weaker (red), " +
                "anything in between is the same (gray). 0 = any change counts. Weight is checked first; when it's " +
                "about the same, reps (or time under tension) decide."
        )
        DecimalStepper("Stronger: weight up by (${s.weightUnit.short})", s.weightUpThreshold) { update(s.copy(weightUpThreshold = it)) }
        DecimalStepper("Weaker: weight down by (${s.weightUnit.short})", s.weightDownThreshold) { update(s.copy(weightDownThreshold = it)) }
        Stepper("Stronger: reps up by", s.repsUpThreshold, 0..20) { update(s.copy(repsUpThreshold = it)) }
        Stepper("Weaker: reps down by", s.repsDownThreshold, 0..20) { update(s.copy(repsDownThreshold = it)) }
        Stepper("Stronger: TUT up by (seconds)", s.tutUpThresholdSec, 0..120) { update(s.copy(tutUpThresholdSec = it)) }
        Stepper("Weaker: TUT down by (seconds)", s.tutDownThresholdSec, 0..120) { update(s.copy(tutDownThresholdSec = it)) }
    }
    Section("Adaptation curve") {
        Choice("Time axis", CurveUnit.entries, s.curveUnit, { it.label }) { update(s.copy(curveUnit = it)) }
        Hint("Hours: 24 h, 48 h, 72 h … Days: day 1, day 2, day 3 …")
    }
    Section("Skills database") {
        TenthsStepper("Daily capacity for new skills (hours)", s.skillCapacityHours, 0f..16f) { update(s.copy(skillCapacityHours = it)) }
        Hint(
            "Hours a skill should get each day. Every hour done is +1 skill level and every hour of the capacity " +
                "not done is −1. Doing half the capacity keeps the level the same. Each skill has its own capacity: " +
                "change it by opening the skill's block."
        )
        Choice("Hold + to add a skill", listOf(0, 500, 1_000, 2_000, 3_000), s.addSkillHoldMs, {
            if (it == 0) "Tap" else "${Fmt.number(it / 1000.0)} s"
        }) { update(s.copy(addSkillHoldMs = it)) }
    }
}

@Composable
private fun RecordingSettings(s: AppSettings, update: (AppSettings) -> Unit) {
    Section("New data recording") {
        // 1-second steps up to 10 s, then 5-second steps.
        val t = s.transcriptSeconds
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Words disappear after (s)", color = TutColors.Text, modifier = Modifier.weight(1f))
            StepButton("−", t > 1) { update(s.copy(transcriptSeconds = if (t <= 10) t - 1 else t - 5)) }
            Text(t.toString(), color = TutColors.Text, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepButton("+", t < 120) { update(s.copy(transcriptSeconds = if (t < 10) t + 1 else t + 5)) }
        }
        Hint("How long what you said stays on the recording page after it pops up.")
        ColorChoice("Warm-up set bubbles (left of mic)", s.warmupColor) { update(s.copy(warmupColor = it)) }
        ColorChoice("Working set bubbles (right of mic)", s.workingColor) { update(s.copy(workingColor = it)) }
        Choice("Hold back arrow to leave", listOf(0, 500, 1_000, 2_000, 3_000, 5_000), s.backHoldMs, {
            if (it == 0) "Off" else "${Fmt.number(it / 1000.0)} s"
        }) { update(s.copy(backHoldMs = it)) }
        Hint("Off: the back arrow just goes back, without asking you to save first.")
        Choice("Hold to resume recording", listOf(0, 500, 1_000, 2_000, 3_000, 5_000), s.resumeHoldMs, {
            if (it == 0) "Tap" else "${Fmt.number(it / 1000.0)} s"
        }) { update(s.copy(resumeHoldMs = it)) }
        Hint("Every big data block in the database has a \"Resume recording\" button. Hold it this long to go back to recording into it.")
        Choice("Hold Save button to save", listOf(0, 500, 1_000, 1_500, 2_000, 3_000), s.saveHoldMs, {
            if (it == 0) "Off" else "${Fmt.number(it / 1000.0)} s"
        }) { update(s.copy(saveHoldMs = it)) }
        Hint("Off: one tap on Save saves. Saying \"save\" still asks yes or no.")
        Choice("Hold logo to switch TUT / Skills", listOf(0, 500, 1_000, 2_000, 3_000, 5_000), s.logoHoldMs, {
            if (it == 0) "Off" else "${Fmt.number(it / 1000.0)} s"
        }) { update(s.copy(logoHoldMs = it)) }
        Hint("Holding the logo on the main page switches between the TUT and Skills databases. Off: the logo can't be held.")
        Stepper("Say \"start\" this many times", s.startWordCount, 1..5) { update(s.copy(startWordCount = it)) }
        Stepper("Say \"stop\" this many times", s.stopWordCount, 1..5) { update(s.copy(stopWordCount = it)) }
        Hint("How many times in a row \"start\" or \"stop\" has to be said before it happens, e.g. 3: \"start start start\". Any other word said in between starts the count over. The Start and Stop buttons always work with one tap.")
        Toggle("Rapid input (say a whole set at once)", s.rapidInput) { update(s.copy(rapidInput = it)) }
        Hint(
            "For saying it all in one go, e.g. \"working set 1, 2 minutes time under tension, 10 pounds, 12 reps, done\". " +
                "Values aren't read back one by one (only \"done\" and commands are), longer phrases are allowed, " +
                "and live words while speaking are turned off so the result comes back faster."
        )
    }
    Section("Speech detection") {
        Hint("On the New data screen, bars that reach the white lines count as speech; bars inside them count as silence.")
        Stepper("Microphone sensitivity", s.micSensitivity, 1..com.example.tut.stt.SpeechEngine.MAX_SENSITIVITY) { update(s.copy(micSensitivity = it)) }
        Hint("Higher picks up quieter speech, but background noise is more likely to count as talking.")
        TenthsStepper("Pause that ends a phrase (seconds)", s.endPauseMs / 1000f, 0.3f..10f) {
            update(s.copy(endPauseMs = (it * 1000).roundToInt()))
        }
        Hint("Lower reacts faster; higher stops you being cut off mid-sentence.")
        Stepper("Pause before reading back a finished set (ms)", s.readbackDelayMs, 0..5000, step = 250) {
            update(s.copy(readbackDelayMs = it))
        }
        Hint("After you say \"done\", the voice waits this long before saying the set back to you. 0 = right away.")
        Toggle("Background noise filter", s.noiseFilterOn) { update(s.copy(noiseFilterOn = it)) }
        if (s.noiseFilterOn) {
            Stepper("Noise filter strength", s.noiseFilter, 1..10) { update(s.copy(noiseFilter = it)) }
            Hint("Higher ends a phrase sooner when there's steady noise (fan, hum, mic hiss). Too high can cut off quiet words.")
            Choice(
                "Noise level adapts over", listOf(500, 1_000, 2_000, 5_000, 10_000, 20_000, 30_000, 60_000, 120_000, 300_000),
                s.noiseAdaptMs, { if (it < 60_000) "${Fmt.number(it / 1000.0)} s" else "${it / 60_000} min" },
            ) { update(s.copy(noiseAdaptMs = it)) }
            Hint("Roughly how long the background level takes to catch up when the room gets louder or quieter. Longer is steadier: a short burst of noise barely moves the white lines. 1 s is the old behaviour.")
        } else {
            Hint("Off: the white lines stay put and don't follow background noise. Only microphone sensitivity decides what counts as speech, and a phrase ends when the sound drops back inside the lines.")
        }
    }
}

private val loadSteps = listOf(0.25f, 0.5f, 1f, 1.25f, 2f, 2.5f, 5f)

@Composable
private fun ProgressionSettings(s: AppSettings, update: (AppSettings) -> Unit) {
    Section("Progression (time under tension)") {
        Hint(
            "How an exercise progresses: time under tension goes up until it reaches the goal, then the weight goes " +
                "up one step. Prediction curves measure progress on this ladder for exercises recorded with time " +
                "under tension."
        )
        TimeStepper("Default time under tension goal", s.tutGoalSec) { update(s.copy(tutGoalSec = it)) }
        Choice("Default weight step (${s.weightUnit.short})", loadSteps, s.loadStep, { Fmt.number(it.toDouble()) }) {
            update(s.copy(loadStep = it))
        }
        Text("Per exercise", color = TutColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Hint("Exercises listed here use their own goal and step instead of the defaults.")
        fun set(i: Int, rule: ProgressionRule) =
            update(s.copy(progressionRules = s.progressionRules.toMutableList().also { it[i] = rule }))
        s.progressionRules.forEachIndexed { i, rule ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TutColors.SurfaceHigh).padding(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = rule.name, onValueChange = { set(i, rule.copy(name = it.take(40))) },
                        placeholder = { Text("Exercise, e.g. bench press", color = TutColors.Outline, fontSize = 13.sp) },
                        textStyle = TextStyle(color = TutColors.Text, fontSize = 14.sp),
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        update(s.copy(progressionRules = s.progressionRules.filterIndexed { j, _ -> j != i }))
                    }) { Icon(Icons.Default.Close, "Remove this exercise", tint = TutColors.Muted) }
                }
                TimeStepper("Time under tension goal", rule.tutGoalSec) { set(i, rule.copy(tutGoalSec = it)) }
                Choice("Weight step (${s.weightUnit.short})", loadSteps, rule.loadStep, { Fmt.number(it.toDouble()) }) {
                    set(i, rule.copy(loadStep = it))
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(TutColors.SurfaceHigh)
                    .border(1.dp, TutColors.Green, CircleShape)
                    .clickable {
                        update(s.copy(progressionRules = s.progressionRules + ProgressionRule("", s.tutGoalSec, s.loadStep)))
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, "Add an exercise", tint = TutColors.Green) }
        }
    }
}

private val fatigueWeights = listOf(0f, 0.5f, 1f, 2f, 2.5f, 5f, 10f)

@Composable
private fun FatigueSettings(s: AppSettings, update: (AppSettings) -> Unit) {
    Section("Fatigue") {
        Hint(
            "Each workout gets a fatigue level, counted against the same exercise's workout before it. Worse adds a " +
                "level; about the same takes one off (or keeps it, if a little worse); better resets it to 0. Set a level by hand inside an exercise in " +
                "the database. The prediction model learns from these levels."
        )
        TimeStepper("About the same: within", s.fatigueSameSec, step = 5, min = 0) { update(s.copy(fatigueSameSec = it)) }
        Hint("A change in time under tension this small counts as the same: one fatigue level less, or the level kept if it was a little worse.")
        TimeStepper("Two levels: a drop of", s.fatigueBigDropSec) { update(s.copy(fatigueBigDropSec = it)) }
        Choice("Two levels: weight down by (${s.weightUnit.short})", fatigueWeights, s.fatigueBigDropWeight, {
            if (it == 0f) "Off" else Fmt.number(it.toDouble())
        }) { update(s.copy(fatigueBigDropWeight = it)) }
        Hint("Dropping this much time under tension, or this much weight, from one workout to the next adds two levels instead of one.")
    }
}

/** − / + in steps of [step] seconds (15 unless given), shown as minutes and seconds. */
@Composable
private fun TimeStepper(label: String, value: Int, step: Int = 15, min: Int = 15, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TutColors.Text, modifier = Modifier.weight(1f))
        StepButton("−", value - step >= min) { onChange(value - step) }
        Text(
            com.example.tut.parse.formatDuration(value), color = TutColors.Text, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("+", value + step <= 1_800) { onChange(value + step) }
    }
}

@Composable
private fun ActivationWordSettings(s: AppSettings, update: (AppSettings) -> Unit) {
    Section("Activation words") {
        // − / + set the size of all the text in this section.
        val size = s.wordAliasTextSp.sp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", color = TutColors.Text, fontSize = size, modifier = Modifier.weight(1f))
            StepButton("−", s.wordAliasTextSp > 8) { update(s.copy(wordAliasTextSp = s.wordAliasTextSp - 1)) }
            Text(
                s.wordAliasTextSp.toString(), color = TutColors.Text, fontWeight = FontWeight.Bold, fontSize = size,
                modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            StepButton("+", s.wordAliasTextSp < 24) { update(s.copy(wordAliasTextSp = s.wordAliasTextSp + 1)) }
        }
        // Every word below is read-only until the lock is held down.
        var unlocked by rememberSaveable { mutableStateOf(false) }
        WordLock(unlocked, size) { unlocked = it }

        Text("Built-in words", color = TutColors.Text, fontWeight = FontWeight.SemiBold, fontSize = size, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Type a different word in a box to say that instead; the old word then stops working. Empty = the built-in word.",
            color = TutColors.Muted, fontSize = size,
        )
        // What each built-in word is currently heard as, to warn about two jobs sharing a word.
        val heardAs = CommandParser.builtInWords.associate { b ->
            b.word to (s.commandWords[b.word]?.let(CommandParser::normalize)?.takeIf { it.isNotBlank() } ?: b.word)
        }
        CommandParser.builtInWords.forEach { b ->
            val value = s.commandWords[b.word] ?: b.word
            val changed = heardAs[b.word] != b.word
            val clash = heardAs.filter { (k, v) -> k != b.word && v == heardAs[b.word] }.keys
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        if (changed) "${b.word} (changed)" else b.word, fontSize = size, fontWeight = FontWeight.SemiBold,
                        color = if (changed) TutColors.Gold else TutColors.Text,
                    )
                    Text(b.use, color = TutColors.Muted, fontSize = size * 0.85f)
                    if (clash.isNotEmpty()) {
                        Text("Same word as: ${clash.joinToString(", ")}", color = TutColors.Red, fontSize = size * 0.85f)
                    }
                }
                OutlinedTextField(
                    value = value, readOnly = !unlocked,
                    onValueChange = { update(s.copy(commandWords = s.commandWords + (b.word to it.take(40)))) },
                    placeholder = { Text(b.word, color = TutColors.Outline, fontSize = size) },
                    textStyle = TextStyle(color = if (unlocked) TutColors.Text else TutColors.Muted, fontSize = size),
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                if (unlocked && b.word in s.commandWords) {
                    IconButton(onClick = { update(s.copy(commandWords = s.commandWords - b.word)) }) {
                        Icon(Icons.Default.Close, "Back to \"${b.word}\"", tint = TutColors.Muted)
                    }
                }
            }
        }

        Text("Your own words", color = TutColors.Text, fontWeight = FontWeight.SemiBold, fontSize = size, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Saying the word in the left box counts as saying the word in the right box, e.g. bench = bench press, " +
                "poles = pull-ups. Tap + to add another pair.",
            color = TutColors.Muted, fontSize = size,
        )
        fun set(i: Int, pair: WordAlias) = update(s.copy(wordAliases = s.wordAliases.toMutableList().also { it[i] = pair }))
        s.wordAliases.forEachIndexed { i, pair ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pair.from, onValueChange = { set(i, pair.copy(from = it.take(40))) }, readOnly = !unlocked,
                    placeholder = { Text("bench", color = TutColors.Outline, fontSize = size) },
                    textStyle = TextStyle(color = if (unlocked) TutColors.Text else TutColors.Muted, fontSize = size),
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                Text("=", color = TutColors.Muted, fontSize = size * 1.3f, modifier = Modifier.padding(horizontal = 8.dp))
                OutlinedTextField(
                    value = pair.to, onValueChange = { set(i, pair.copy(to = it.take(40))) }, readOnly = !unlocked,
                    placeholder = { Text("bench press", color = TutColors.Outline, fontSize = size) },
                    textStyle = TextStyle(color = if (unlocked) TutColors.Text else TutColors.Muted, fontSize = size),
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                // Removing the only pair just empties its boxes.
                if (unlocked) {
                    IconButton(onClick = {
                        update(s.copy(wordAliases = (s.wordAliases.filterIndexed { j, _ -> j != i }).ifEmpty { listOf(WordAlias()) }))
                    }) { Icon(Icons.Default.Close, "Remove this pair", tint = TutColors.Muted) }
                }
            }
        }
        if (unlocked) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(TutColors.SurfaceHigh)
                        .border(1.dp, TutColors.Green, CircleShape)
                        .clickable { update(s.copy(wordAliases = s.wordAliases + WordAlias())) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, "Add another pair", tint = TutColors.Green) }
            }
        }
    }
}

private const val WORD_UNLOCK_HOLD_MS = 2_000

/** Locked: hold it for [WORD_UNLOCK_HOLD_MS] to unlock (it fills while held). Unlocked: a tap locks it again. */
@Composable
private fun WordLock(unlocked: Boolean, size: androidx.compose.ui.unit.TextUnit, onChange: (Boolean) -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val color = if (unlocked) TutColors.Green else TutColors.Gold
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).border(1.dp, color, shape)
            .pointerInput(unlocked) {
                detectTapGestures(onPress = {
                    if (unlocked) {
                        if (tryAwaitRelease()) onChange(false)
                        return@detectTapGestures
                    }
                    val job = scope.launch {
                        val start = withFrameMillis { it }
                        while (true) {
                            progress = ((withFrameMillis { it } - start) / WORD_UNLOCK_HOLD_MS.toFloat()).coerceAtMost(1f)
                            if (progress >= 1f) { onChange(true); break }
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                    progress = 0f
                })
            },
    ) {
        Box(Modifier.matchParentSize()) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(color.copy(alpha = 0.3f)))
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = color)
            Text(
                if (unlocked) "Unlocked: words can be edited. Tap to lock."
                else "Locked. Hold ${Fmt.number(WORD_UNLOCK_HOLD_MS / 1000.0)} s to edit words.",
                color = color, fontWeight = FontWeight.SemiBold, fontSize = size, modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun FeedbackSettings(app: TutApp, s: AppSettings, update: (AppSettings) -> Unit) {
    // A speaker of its own, just for previews on this page.
    val speaker = remember { Speaker(app) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    val voices by speaker.voices.collectAsState()

    fun preview(settings: AppSettings) {
        speaker.setVoice(settings.voiceName, settings.voicePitch)
        speaker.speak("working set 1, 10 pounds", settings.speechRate)
    }

    Section("When it hears an action word") {
        Choice("Respond with", FeedbackMode.entries, s.feedback, { it.label }) { update(s.copy(feedback = it)) }
        if (s.feedback.sound) {
            Choice("Sound", FeedbackSound.entries, s.feedbackSound, { it.label }) {
                speaker.beep(it)
                update(s.copy(feedbackSound = it))
            }
            Hint("Tap a sound to hear it. One sound plays per phrase it acts on.")
            Choice("Set done sound", FeedbackSound.entries, s.doneSound, { it.label }) {
                speaker.beep(it)
                update(s.copy(doneSound = it))
            }
            Hint("Played when \"done\" finishes a warm-up or working set and its bubble appears, instead of the sound above.")
        }
        if (s.feedback == FeedbackMode.SOUND) {
            Choice("Start sound", FeedbackSound.entries, s.startSound, { it.label }) {
                speaker.beep(it)
                update(s.copy(startSound = it))
            }
            Choice("Stop sound", FeedbackSound.entries, s.stopSound, { it.label }) {
                speaker.beep(it)
                update(s.copy(stopSound = it))
            }
            Hint("Played for the Start and Stop buttons, and for saying \"start\" or \"stop\", instead of the sound above.")
        }
        if (s.feedback.voice) {
            Text("Voice", color = TutColors.Text, modifier = Modifier.padding(top = 4.dp))
            val options = listOf<VoiceOption?>(null) + voices
            options.forEach { v ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(
                        selected = s.voiceName == v?.name,
                        onClick = { s.copy(voiceName = v?.name).also { update(it); preview(it) } },
                        colors = RadioButtonDefaults.colors(selectedColor = TutColors.Green),
                    )
                    Text(v?.label ?: "Phone default", color = TutColors.Text, fontSize = 14.sp)
                }
            }
            if (voices.isEmpty()) Hint("More voices can be installed in the phone's text-to-speech settings.")
            SliderRow("Pitch (tone of voice)", s.voicePitch, 0.5f..2f, "%.1fx".format(s.voicePitch)) {
                update(s.copy(voicePitch = (it * 10).roundToInt() / 10f))
            }
            SliderRow("Voice speed", s.speechRate, 0.5f..2f, "%.1fx".format(s.speechRate)) {
                update(s.copy(speechRate = (it * 10).roundToInt() / 10f))
            }
            OutlinedButton(onClick = { preview(s) }) { Text("Test voice", color = TutColors.Green) }
        }
    }
}

@Composable
private fun ModelSettings(app: TutApp, s: AppSettings, update: (AppSettings) -> Unit) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var models by remember { mutableStateOf(emptyList<File>()) }
    var importing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) { models = withContext(Dispatchers.IO) { app.models.availableModels() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = "Importing model…"
            scope.launch {
                importing = try {
                    val f = app.models.import(uri)
                    update(s.copy(modelFileName = f.name))
                    refresh++
                    null
                } catch (e: Exception) {
                    "Import failed: ${e.message}"
                }
            }
        }
    }

    Section("Speech-to-text model") {
        SttEngine.entries.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(
                    selected = s.sttEngine == e, onClick = { update(s.copy(sttEngine = e)) },
                    colors = RadioButtonDefaults.colors(selectedColor = TutColors.Green),
                )
                Text(e.label, color = TutColors.Text, fontSize = 14.sp)
            }
        }
        if (s.sttEngine == SttEngine.GOOGLE) {
            Hint(
                if (Build.VERSION.SDK_INT < 33) "Needs Android 13 or newer; this phone will keep using whisper."
                else "Writes while you talk. Works offline once the phone has its offline English pack " +
                    "(the app asks for it the first time if it's missing). The settings below are for whisper."
            )
        }
        if (models.isEmpty()) {
            Hint("No model found. Import a whisper.cpp model file (e.g. ${AppSettings.DEFAULT_MODEL}), or copy it to:\n${app.models.externalDir?.absolutePath}")
        }
        models.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(
                    selected = s.modelFileName == f.name, onClick = { update(s.copy(modelFileName = f.name)) },
                    colors = RadioButtonDefaults.colors(selectedColor = TutColors.Green),
                )
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = TutColors.Text, fontSize = 14.sp)
                    Text("${f.length() / (1024 * 1024)} MB", color = TutColors.Muted, fontSize = 11.sp)
                }
                if (f.parentFile == app.models.internalDir) {
                    TextButton(onClick = { app.models.delete(f); refresh++ }) { Text("Delete", color = TutColors.Red, fontSize = 12.sp) }
                }
            }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = importing == null || importing!!.startsWith("Import failed")) {
            Text("Import model file", color = TutColors.Green)
        }
        importing?.let { Hint(it) }
        Hint("Model changes take effect the next time New data is opened.")
        Stepper("CPU threads", s.sttThreads, 1..8) { update(s.copy(sttThreads = it)) }
        Toggle("Fast mode (shorter audio window)", s.fastMode) { update(s.copy(fastMode = it)) }
        Toggle("Show words while still speaking", s.livePartials) { update(s.copy(livePartials = it)) }
    }
}

/**
 * Save all data to one file, and add a saved file's data back in. Adding never removes what's
 * here: blocks the file also has (same start time) are replaced by the file's, the rest is
 * added. Undo puts back everything as it was before the last add (the app closes and reopens).
 */
@Composable
private fun BackupSettings(app: TutApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Pair<String, Backup.Summary>?>(null) } // undo: title + what it holds
    var adding by remember { mutableStateOf<Pair<Backup.Summary, Repository.MergeResult>?>(null) }
    var canUndo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { canUndo = withContext(Dispatchers.IO) { Backup.canUndo(context) } }

    fun summaryText(b: Backup.Summary) = listOfNotNull(
        "${b.blocks} big data ${if (b.blocks == 1) "block" else "blocks"}",
        "${b.skillLogs} skill ${if (b.skillLogs == 1) "log" else "logs"}",
        "${b.recordings} voice ${if (b.recordings == 1) "recording" else "recordings"}",
        if (b.settings) "settings" else null,
    ).joinToString(", ")

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "Saving…"
        scope.launch {
            status = try {
                val b = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        Backup.write(context, { mode, file -> app.repoFor(mode).copyTo(file) }, out)
                    }
                }
                "Saved: ${summaryText(b)}."
            } catch (e: Exception) {
                "Saving failed: ${e.message}"
            }
            busy = false
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "Reading the file…"
        scope.launch {
            try {
                adding = withContext(Dispatchers.IO) {
                    val b = context.contentResolver.openInputStream(uri)!!.use { Backup.stage(context, it, app.repo.schemaVersion) }
                    b to Backup.preview(context, app::repoFor)
                }
                status = null
            } catch (e: Exception) {
                Backup.cancel(context)
                status = "Can't add this file: ${e.message}"
            }
            busy = false
        }
    }

    Section("Save & restore all data") {
        Button(
            onClick = { saver.launch("TUT backup ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())}.zip") },
            enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = TutColors.Green, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save all data to a file", fontWeight = FontWeight.Bold) }
        Hint(
            "One file with every big data block and everything in it (exercises, sets, sleep, voice recordings), " +
                "the Skills database, locked predictions and your settings. Speech models aren't included: import them " +
                "again after a restore. Keep the file off the phone too (Google Drive, or email it to yourself)."
        )
        OutlinedButton(onClick = { opener.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Add data from a file", color = TutColors.Green, fontWeight = FontWeight.Bold)
        }
        Hint(
            "Adds the file's data to what's here; nothing that's only here is removed. A big data block in the file with " +
                "the same date and time as one here replaces it. You see what it will add and replace before anything " +
                "changes, and it can be undone. Settings in the file are not used."
        )
        if (canUndo) {
            TextButton(
                onClick = {
                    scope.launch {
                        val b = withContext(Dispatchers.IO) { Backup.stageUndo(context) }
                        ask = "Undo the last add?" to b
                    }
                },
                enabled = !busy,
            ) { Text("Undo the last add", color = TutColors.Orange) }
        }
        status?.let { Hint(it) }
    }

    adding?.let { (b, m) ->
        AddDataDialog(
            b, m,
            onCancel = { Backup.cancel(context); adding = null },
            onAdd = {
                adding = null
                busy = true
                status = "Adding…"
                scope.launch {
                    status = try {
                        val r = withContext(Dispatchers.IO) {
                            Backup.merge(context, app::repoFor).also { app.opsFor(DbMode.TUT).recolorAll() }
                        }
                        canUndo = true
                        "Added " + count(r.blocks.added, "new block", "new blocks") + ", replaced ${r.blocks.replaced}, " +
                            "kept ${r.blocks.kept} that weren't in the file."
                    } catch (e: Exception) {
                        "Adding failed: ${e.message}"
                    }
                    busy = false
                }
            },
        )
    }

    ask?.let { (title, b) ->
        AlertDialog(
            onDismissRequest = { Backup.cancel(context); ask = null },
            title = { Text(title) },
            text = {
                Text(
                    "Puts back all data as it was before the last add (" + (b.createdAt?.let { Fmt.dateTime(it) + ", " } ?: "") +
                        summaryText(b) + "). Anything added or changed since then is lost. The app closes and reopens to finish."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Backup.confirm(context)
                    ask = null
                    restartApp(context)
                }) { Text("Undo", color = TutColors.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { Backup.cancel(context); ask = null }) { Text("Cancel", color = TutColors.Muted) }
            },
        )
    }
}

@Composable
private fun AddDataDialog(b: Backup.Summary, m: Repository.MergeResult, onAdd: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add this file's data?") },
        text = {
            Text(
                (b.createdAt?.let { "Saved " + Fmt.dateTime(it) + ".\n\n" } ?: "") +
                    "Big data blocks:\n" +
                    "• " + count(m.blocks.added, "new block", "new blocks") + " will be added\n" +
                    "• " + count(m.blocks.replaced, "block", "blocks") + " with the same date and time will be replaced by the file's\n" +
                    "• " + count(m.blocks.kept, "block", "blocks") + " here aren't in the file and stay as they are" +
                    (if (m.logs.added + m.logs.replaced + m.logs.kept > 0) "\n\nSkill logs: ${m.logs.added} added, " +
                        "${m.logs.replaced} replaced, ${m.logs.kept} kept" else "") +
                    (if (b.recordings > 0) "\n\nVoice recordings in the file are added; none here are removed." else "")
            )
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Add", color = TutColors.Green, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = TutColors.Muted) } },
    )
}

private fun count(v: Int, one: String, many: String) = "$v ${if (v == 1) one else many}"

/** Closes the app and opens it again, so a confirmed restore goes in before anything is opened. */
private fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    (context as? Activity)?.finishAffinity()
    launch?.let { context.startActivity(it) }
    exitProcess(0)
}

// ---------------------------------------------------------------- building blocks

@Composable
/** A drop-down group of settings: tap the header to open or close it. Starts closed. */
private fun Section(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).background(TutColors.Surface)
            .border(1.dp, if (open) TutColors.Green else TutColors.Outline, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = TutColors.Green, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(if (open) "▲" else "▼", color = TutColors.Muted, fontSize = 12.sp)
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { content() }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = TutColors.Muted, fontSize = 12.sp)
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TutColors.Text, modifier = Modifier.weight(1f))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = TutColors.Green, checkedThumbColor = Color.Black),
        )
    }
}

@Composable
private fun <T> Choice(label: String, options: List<T>, value: T, name: (T) -> String, onChange: (T) -> Unit) {
    Column {
        Text(label, color = TutColors.Text)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            options.forEach { o ->
                FilterChip(
                    selected = o == value, onClick = { onChange(o) }, label = { Text(name(o)) },
                    modifier = Modifier.padding(end = 8.dp), colors = chipColors(TutColors.Green),
                )
            }
        }
    }
}

@Composable
private fun ColorChoice(label: String, value: BubbleColor, onChange: (BubbleColor) -> Unit) {
    Column {
        Row {
            Text(label, color = TutColors.Text, modifier = Modifier.weight(1f))
            Text(value.label, color = TutColors.Muted)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BubbleColor.entries.forEach { c ->
                val color = Color(c.argb)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(color)
                        .border(3.dp, if (c == value) TutColors.Text else Color.Transparent, CircleShape)
                        .clickable { onChange(c) },
                )
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TutColors.Text, modifier = Modifier.weight(1f))
        StepButton("−", value - step >= range.first) { onChange(value - step) }
        Text(value.toString(), color = TutColors.Text, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton("+", value + step <= range.last) { onChange(value + step) }
    }
}

@Composable
private fun DecimalStepper(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TutColors.Text, modifier = Modifier.weight(1f))
        StepButton("−", value >= 0.5f) { onChange(((value - 0.5f) * 2).roundToInt() / 2f) }
        Text(Fmt.number(value.toDouble()), color = TutColors.Text, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton("+", value < 100f) { onChange(((value + 0.5f) * 2).roundToInt() / 2f) }
    }
}

/** A value in tenths (e.g. 2.5): − / + step by 0.1, the slider jumps across the range. */
@Composable
internal fun TenthsStepper(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val tenths = (value * 10).roundToInt()
    fun set(t: Int) = onChange((t.coerceIn((range.start * 10).roundToInt(), (range.endInclusive * 10).roundToInt())) / 10f)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TutColors.Text, modifier = Modifier.weight(1f))
            StepButton("−", value > range.start) { set(tenths - 1) }
            Text("%.1f".format(tenths / 10f), color = TutColors.Text, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepButton("+", value < range.endInclusive) { set(tenths + 1) }
        }
        Slider(
            value = value, onValueChange = { set((it * 10).roundToInt()) }, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = TutColors.Blue, activeTrackColor = TutColors.Blue),
        )
    }
}

@Composable
internal fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = TutColors.SurfaceHigh, contentColor = TutColors.Text),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier.width(40.dp).height(36.dp),
    ) { Text(text, fontSize = 18.sp) }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, color = TutColors.Text, modifier = Modifier.weight(1f))
            Text(valueText, color = TutColors.Muted)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = TutColors.Green, activeTrackColor = TutColors.Green),
        )
    }
}
