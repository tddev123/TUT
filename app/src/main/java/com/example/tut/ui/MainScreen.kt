package com.example.tut.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tut.DbMode
import com.example.tut.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mode: DbMode,
    logoHoldMs: Long,
    onToggleMode: () -> Unit,
    onSettings: () -> Unit,
    onNewData: (startedAt: Long) -> Unit,
    onDatabase: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val requestMic = LocalMicPermission.current

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            HoldLogo(mode, logoHoldMs, onToggleMode, Modifier.align(Alignment.Center))
            IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TutColors.Muted)
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(Fmt.date(now), fontSize = 22.sp, color = TutColors.Text, fontWeight = FontWeight.Medium)
            Text(Fmt.time(now), fontSize = 40.sp, color = TutColors.Text, fontWeight = FontWeight.Bold)
        }

        BigBlock(
            Modifier.fillMaxWidth().weight(1f),
            onClick = { requestMic { onNewData(System.currentTimeMillis()) } },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("New data", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TutColors.Text)
                Spacer(Modifier.height(12.dp))
                Icon(
                    painterResource(R.drawable.ic_mic), contentDescription = "Start recording",
                    tint = if (mode == DbMode.SKILLS) TutColors.Blue else TutColors.Green, modifier = Modifier.size(96.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        BigBlock(Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp), onClick = onDatabase) {
            Text("Database", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TutColors.Text)
        }
    }
}

/**
 * The logo doubles as the database switch: holding it for [holdMs] flips between
 * TUT (green) and Skills (blue). The logo fills up while held. [holdMs] 0 turns the hold off.
 */
@Composable
private fun HoldLogo(mode: DbMode, holdMs: Long, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val toggle by rememberUpdatedState(onToggle)
    Box(
        modifier.pointerInput(holdMs) {
            if (holdMs <= 0) return@pointerInput
            detectTapGestures(onPress = {
                val job = scope.launch {
                    val start = withFrameMillis { it }
                    while (true) {
                        progress = ((withFrameMillis { it } - start) / holdMs.toFloat()).coerceAtMost(1f)
                        if (progress >= 1f) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            toggle()
                            break
                        }
                    }
                }
                tryAwaitRelease()
                job.cancel()
                progress = 0f
            })
        }
    ) {
        Logo(mode, progress)
    }
}

@Composable
private fun Logo(mode: DbMode, progress: Float) {
    val color = if (mode == DbMode.SKILLS) TutColors.Blue else TutColors.Green
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .drawBehind { drawRect(color.copy(alpha = 0.35f), size = size.copy(width = size.width * progress)) }
            .border(2.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Text(mode.label.uppercase(), color = color, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
    }
}
