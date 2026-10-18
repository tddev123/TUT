package com.example.tut.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tut.R
import com.example.tut.TutApp
import com.example.tut.parse.SkillParser
import com.example.tut.record.LoggedHours
import com.example.tut.record.SkillController
import kotlinx.coroutines.delay

/**
 * New data page for the Skills database: say a skill and how long ("music 2 hours") and
 * it is logged straight away. Tapping a bubble takes that log back out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillRecordScreen(app: TutApp, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember { SkillController(app, scope) }
    val settings by app.settings.settings.collectAsState()
    val view = LocalView.current
    val startedAt = remember { System.currentTimeMillis() }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        controller.begin()
        onDispose {
            view.keepScreenOn = false
            controller.close()
        }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }
    var levels by remember { mutableStateOf(listOf<Float>()) }
    LaunchedEffect(Unit) {
        while (true) {
            levels = (levels + controller.engine.level.value).takeLast(64)
            delay(50)
        }
    }

    val listening by controller.listening.collectAsState()
    val transcript by controller.transcript.collectAsState()
    val partial by controller.engine.partial.collectAsState()
    val status by controller.modelStatus.collectAsState()
    val logged by controller.logged.collectAsState()
    val skills by controller.skills.collectAsState()
    var removing by remember { mutableStateOf<LoggedHours?>(null) }

    fun exit() {
        controller.close()
        onExit()
    }
    BackHandler { exit() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TopBar("New skill data", ::exit)
        Text(
            Fmt.dateTime(startedAt), color = TutColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
            ToggleButton("Start", active = listening, activeColor = TutColors.Blue) { controller.setListening(true) }
            Spacer(Modifier.width(14.dp))
            ToggleButton("Stop", active = !listening, activeColor = TutColors.Red) { controller.setListening(false) }
        }
        ModelStatusLine(status)

        Icon(
            painterResource(R.drawable.ic_mic), contentDescription = null,
            tint = if (listening) TutColors.Blue else TutColors.Muted,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp).size(72.dp),
        )
        val threshold by controller.engine.threshold.collectAsState()
        LiveWaveform(
            levels, stopped = !listening, modifier = Modifier.fillMaxWidth().height(64.dp),
            color = TutColors.Blue, threshold = threshold.takeIf { it > 0f },
        )
        Text(
            if (skills.isEmpty()) "No skills yet: add one with + on the Skills database page"
            else "Say a skill and the time, e.g. \"${skills.first().name.lowercase()} 2 hours\"",
            color = TutColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        val visible = transcript.filter { now - it.at < settings.transcriptSeconds * 1000L }.joinToString(" ") { it.text }
        TwoLineTail(
            listOf(visible, partial).filter { it.isNotBlank() }.joinToString(" "),
            Modifier.fillMaxWidth().padding(top = 8.dp), color = TutColors.Blue,
        )

        if (skills.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { skills.forEach { Pill(it.name, TutColors.Muted) } }
        }

        // Hours logged on this visit, newest first; tap one to take it back out.
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(top = 14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            logged.asReversed().forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(TutColors.Blue.copy(alpha = 0.18f))
                        .border(1.dp, TutColors.Blue, RoundedCornerShape(14.dp))
                        .clickable { removing = item }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.skillName, color = TutColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("+${SkillParser.speakHours(item.hours)}", color = TutColors.Blue, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(Fmt.elapsed(now - startedAt), color = TutColors.Blue, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }

    removing?.let { item ->
        ConfirmDialog(
            title = "Remove ${item.skillName} ${SkillParser.speakHours(item.hours)} from the Skills database?",
            onYes = { controller.remove(item); removing = null },
            onNo = { removing = null },
            yes = "Remove", no = "Keep",
        )
    }
}
