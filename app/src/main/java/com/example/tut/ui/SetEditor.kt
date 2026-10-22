package com.example.tut.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.tut.data.AppSettings
import com.example.tut.data.RirLevel
import com.example.tut.data.WeightUnit
import com.example.tut.data.WorkSet

/** Small pop-up for viewing and hand-editing one set. Changes are saved with the Save button. */
@Composable
fun SetEditDialog(
    set: WorkSet,
    title: String,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (WorkSet) -> Unit,
) {
    var restMin by remember { mutableStateOf(set.restSec?.let { (it / 60).toString() } ?: "") }
    var restSec by remember { mutableStateOf(set.restSec?.let { (it % 60).toString() } ?: "") }
    var weight by remember { mutableStateOf(set.weight?.let { Fmt.number(it) } ?: "") }
    var unit by remember { mutableStateOf(set.unit ?: settings.weightUnit) }
    var reps by remember { mutableStateOf(set.reps?.toString() ?: "") }
    var tutMin by remember { mutableStateOf(set.tutSec?.let { (it / 60).toString() } ?: "") }
    var tutSec by remember { mutableStateOf(set.tutSec?.let { (it % 60).toString() } ?: "") }
    var rirLevel by remember { mutableStateOf(set.rirLevel) }
    var rirValue by remember { mutableStateOf(set.rirValue?.toString() ?: "") }
    var endRange by remember { mutableStateOf(set.endRangeSec?.toString() ?: "") }
    var topRest by remember { mutableStateOf(set.topRestSec?.toString() ?: "") }

    // Both can always be filled in: progress can go by reps or by time under tension.
    val showReps = true
    val showTut = true

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = TutColors.Surface) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TutColors.Text)
                Spacer(Modifier.padding(4.dp))

                FieldLabel("Rest time")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(restMin, { restMin = it }, "min", Modifier.weight(1f))
                    Text(" : ", color = TutColors.Muted)
                    NumberField(restSec, { restSec = it }, "sec", Modifier.weight(1f))
                }

                FieldLabel("Weight")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(weight, { weight = it }, unit.short, Modifier.weight(1f), decimal = true)
                    Spacer(Modifier.width(8.dp))
                    WeightUnit.entries.forEach { u ->
                        FilterChip(
                            selected = unit == u, onClick = { unit = u }, label = { Text(u.short) },
                            modifier = Modifier.padding(end = 4.dp), colors = chipColors(TutColors.Green),
                        )
                    }
                }

                if (showReps) {
                    FieldLabel("Reps")
                    NumberField(reps, { reps = it }, "reps", Modifier.fillMaxWidth())
                }
                if (showTut) {
                    FieldLabel("Time under tension")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NumberField(tutMin, { tutMin = it }, "min", Modifier.weight(1f))
                        Text(" : ", color = TutColors.Muted)
                        NumberField(tutSec, { tutSec = it }, "sec", Modifier.weight(1f))
                    }
                }

                if (settings.rirEnabled) {
                    FieldLabel("RIR")
                    Row {
                        RirLevel.entries.forEach { level ->
                            FilterChip(
                                selected = rirLevel == level,
                                onClick = {
                                    rirLevel = if (rirLevel == level) null else level
                                    rirValue = ""
                                },
                                label = { Text(level.label) },
                                modifier = Modifier.padding(end = 6.dp),
                                colors = chipColors(level.toColor()),
                            )
                        }
                    }
                    if (settings.rirShowNumber) {
                        NumberField(rirValue, { rirValue = it }, "RIR number", Modifier.fillMaxWidth())
                    }
                }

                FieldLabel("End range hold seconds (optional)")
                NumberField(endRange, { endRange = it }, "sec", Modifier.fillMaxWidth())

                FieldLabel("Rest at top seconds (optional)")
                NumberField(topRest, { topRest = it }, "sec", Modifier.fillMaxWidth())

                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = TutColors.Muted) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val rest = duration(restMin, restSec)
                            val tut = duration(tutMin, tutSec)
                            val rv = rirValue.toIntOrNull()
                            onSave(
                                set.copy(
                                    restSec = rest,
                                    weight = weight.toDoubleOrNull(),
                                    unit = if (weight.toDoubleOrNull() != null) unit else null,
                                    reps = if (showReps) reps.toIntOrNull() else set.reps,
                                    tutSec = if (showTut) tut else set.tutSec,
                                    rirLevel = if (rv != null) levelFor(rv, settings) else rirLevel,
                                    rirValue = rv,
                                    endRangeSec = endRange.toIntOrNull(),
                                    topRestSec = topRest.toIntOrNull(),
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TutColors.Green, contentColor = Color.Black),
                    ) { Text("Save", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

private fun duration(min: String, sec: String): Int? {
    val m = min.toIntOrNull()
    val s = sec.toIntOrNull()
    if (m == null && s == null) return null
    return (m ?: 0) * 60 + (s ?: 0)
}

private fun levelFor(v: Int, s: AppSettings): RirLevel =
    listOf(RirLevel.HIGH to s.rirHigh, RirLevel.MODERATE to s.rirModerate, RirLevel.LOW to s.rirLow)
        .minBy { kotlin.math.abs(it.second - v) }.first

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = TutColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
fun chipColors(color: Color) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = color.copy(alpha = 0.25f),
    selectedLabelColor = color,
    labelColor = TutColors.Muted,
)

@Composable
fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.all { it.isDigit() || (decimal && it == '.') } && v.length <= 7) onChange(v) },
        placeholder = { Text(placeholder, color = TutColors.Muted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

/** How a set's RIR is shown, following the settings (label or number). */
fun rirText(set: WorkSet, s: AppSettings): String? {
    val level = set.rirLevel
    if (s.rirShowNumber) {
        val v = set.rirValue ?: when (level) {
            RirLevel.HIGH -> s.rirHigh
            RirLevel.MODERATE -> s.rirModerate
            RirLevel.LOW -> s.rirLow
            null -> return null
        }
        return "RIR - $v"
    }
    return level?.let { "RIR - ${it.label}" } ?: set.rirValue?.let { "RIR - $it" }
}
