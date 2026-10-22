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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tut.TutApp
import com.example.tut.data.Gbdt
import com.example.tut.data.Predictions
import com.example.tut.parse.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Prediction model: a look inside the model behind the prediction curves. It learns its own
 * copy from the database, the same way the prediction curves page does, and only reads it:
 * the inputs it sees, how its trees add up to a prediction, and each tree drawn out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelScreen(app: TutApp, onBack: () -> Unit) {
    val version by app.repo.version.collectAsState()
    val settings by app.settings.settings.collectAsState()
    var model by remember { mutableStateOf<Predictions.Model?>(null) }
    BackHandler(onBack = onBack)

    LaunchedEffect(version, settings) {
        val w = withContext(Dispatchers.IO) { app.repo.allWorkouts() }
        model = withContext(Dispatchers.Default) { Predictions.train(w, settings) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Prediction model", onBack)
        val m = model
        if (m == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TutColors.Green) }
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val g = m.trees
            Box2 {
                Heading("What it is")
                Body(
                    "A gradient-boosted tree model. It starts from one guess (your average progress from one workout to " +
                        "the next), then adds ${g?.treeViews?.size ?: 150} small decision trees one after another. Each tree " +
                        "asks up to 3 yes/no questions about the inputs and adds a small step (${Fmt.number((g?.rate ?: 0.05) * 100)}% " +
                        "of its answer) to fix what the trees before it still got wrong.",
                )
                Body(
                    "Learned from ${m.rows} workouts compared with the one before of the same exercise." +
                        (m.typicalMiss?.let { " On workouts it wasn't shown while learning, it was off by ${Fmt.number(r1(it))}% on average." }
                            ?: " Too few yet to test how far off it is (needs 10)."),
                )
            }
            if (g == null) {
                Body(
                    "Not enough data yet: it needs at least ${Predictions.MIN_ROWS} workouts compared with the one before " +
                        "(you have ${m.rows}). Keep recording working sets with weight and reps (or time under tension).",
                    color = TutColors.Orange,
                )
                return@Column
            }

            var name by rememberSaveable { mutableStateOf(m.names.firstOrNull() ?: "") }
            FlowRow(
                Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                m.names.forEach { n ->
                    val on = Predictions.key(n) == Predictions.key(name)
                    Text(
                        n, color = if (on) Color.Black else TutColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) TutColors.Green else TutColors.Surface)
                            .border(1.dp, if (on) TutColors.Green else TutColors.Outline, RoundedCornerShape(50))
                            .clickable { name = n }.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            val now = remember { System.currentTimeMillis() }
            val x = remember(m, name) { m.inputsNow(name, now) } ?: return@Column
            val parts = remember(g, x) { g.treeViews.map { g.rate * leafOf(it, x) } }
            val raw = g.baseValue + parts.sum()
            val limit = m.limit(name)
            val final = limit?.let { minOf(raw, it) } ?: raw
            val scale = m.scale(name)
            val change: (Double) -> String = { v -> scale?.changeText(v) ?: (Fmt.number(r1(v)) + "%") }

            // ---- inputs
            Box2 {
                Heading("Inputs: $name today")
                Body("What the model is given if you did last time's $name workout again today.")
                Predictions.inputNames.forEachIndexed { i, label ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(label, color = TutColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(inputText(i, x[i]), color = TutColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ---- how it adds up
            Box2 {
                Heading("How the prediction adds up")
                AddRow("Starting guess", change(g.baseValue))
                AddRow("+ ${parts.size} trees together", change(parts.sum()))
                if (limit != null && raw > limit) AddRow("Capped at the limit you marked", change(limit))
                AddRow("= Prediction", change(final), strong = true)
                RunningTotal(g.baseValue, parts, Modifier.fillMaxWidth().height(140.dp).padding(top = 10.dp))
                Body("The line: the prediction after each tree is added, from the starting guess (left) to all trees (right).")
            }

            // ---- which inputs matter
            val counts = remember(g) {
                val c = IntArray(Predictions.inputNames.size)
                g.treeViews.forEach { countSplits(it, c) }
                c
            }
            Box2 {
                Heading("Which inputs the trees use")
                Body("How many questions, over all the trees, ask about each input. An input asked about often is one the model found matters.")
                val most = counts.max().coerceAtLeast(1)
                Predictions.inputNames.indices.sortedByDescending { counts[it] }.forEach { i ->
                    Text(Predictions.inputNames[i], color = TutColors.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(TutColors.Outline.copy(alpha = 0.4f))
                        ) {
                            if (counts[i] > 0) {
                                Box(
                                    Modifier.fillMaxWidth(counts[i] / most.toFloat()).height(10.dp)
                                        .clip(RoundedCornerShape(5.dp)).background(TutColors.Green)
                                )
                            }
                        }
                        Text(counts[i].toString(), color = TutColors.Muted, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
                    }
                }
            }

            // ---- biggest trees and one tree drawn out
            var shown by rememberSaveable(name) { mutableIntStateOf(parts.indices.maxByOrNull { abs(parts[it]) } ?: 0) }
            Box2 {
                Heading("Trees that moved this prediction most")
                Body("Tap one to draw it below.")
                parts.indices.sortedByDescending { abs(parts[it]) }.take(8).forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (t == shown) TutColors.Green.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { shown = t }.padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text("Tree ${t + 1}", color = TutColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(change(parts[t]), color = if (parts[t] >= 0) TutColors.Green else TutColors.Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Box2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton("‹") { shown = (shown - 1 + parts.size) % parts.size }
                    Text(
                        "Tree ${shown + 1} of ${parts.size}", color = TutColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                    StepButton("›") { shown = (shown + 1) % parts.size }
                }
                Body("Green: the way today's $name goes through this tree. The number at the end is what the tree adds.")
                TreeView(g.treeViews[shown], x, g.rate, change, depth = 0, label = null, onPath = true)
            }

            // ---- what it learned from
            val rows = remember(m) { m.learnedRows().sortedByDescending { it.at } }
            Box2 {
                Heading("Workouts it learned from")
                Body(
                    "Each one's real progress next to what the model says for it now. It has seen these, so it fits them " +
                        "closer than it would a new workout: the \"off by\" number at the top is the honest test.",
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Workout", color = TutColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("Real", color = TutColors.Muted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                    Text("Model", color = TutColors.Muted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                }
                rows.take(30).forEach { r ->
                    val sc = m.scale(r.name)
                    val fmt: (Double) -> String = { v -> sc?.changeText(v) ?: (Fmt.number(r1(v)) + "%") }
                    val fit = g.predict(r.inputs)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, color = TutColors.Text, fontSize = 12.sp)
                            Text(Fmt.date(r.at), color = TutColors.Muted, fontSize = 10.sp)
                        }
                        Text(fmt(r.actual), color = TutColors.Text, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                        Text(fmt(fit), color = TutColors.Muted, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                    }
                }
                if (rows.size > 30) Body("…and ${rows.size - 30} older ones.")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** One tree, as indented yes/no questions; [onPath] is the way [x] goes through it. */
@Composable
private fun TreeView(
    node: Gbdt.TreeView, x: DoubleArray, rate: Double, change: (Double) -> String,
    depth: Int, label: String?, onPath: Boolean,
) {
    val color = if (onPath) TutColors.Green else TutColors.Muted
    val prefix = label?.let { "$it  " } ?: ""
    val pad = Modifier.padding(start = (depth * 14).dp, top = 4.dp)
    when (node) {
        is Gbdt.TreeView.Leaf -> Text(
            prefix + "adds ${change(rate * node.value)}", color = color, fontSize = 13.sp,
            fontWeight = if (onPath) FontWeight.Bold else FontWeight.Normal, modifier = pad,
        )
        is Gbdt.TreeView.Split -> {
            Text(
                prefix + "${Predictions.inputNames[node.feature]} ≤ ${inputText(node.feature, node.threshold)}?",
                color = if (onPath) TutColors.Text else TutColors.Muted, fontSize = 13.sp,
                fontWeight = if (onPath) FontWeight.SemiBold else FontWeight.Normal, modifier = pad,
            )
            val left = x[node.feature] <= node.threshold
            TreeView(node.left, x, rate, change, depth + 1, "yes →", onPath && left)
            TreeView(node.right, x, rate, change, depth + 1, "no →", onPath && !left)
        }
    }
}

/** The prediction after each tree is added, from the starting guess on. */
@Composable
private fun RunningTotal(base: Double, parts: List<Double>, modifier: Modifier) {
    Canvas(modifier) {
        val totals = parts.runningFold(base) { acc, p -> acc + p }
        val lo = minOf(totals.min(), 0.0)
        val hi = maxOf(totals.max(), 0.0)
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        fun x(i: Int) = i / (totals.size - 1).coerceAtLeast(1).toFloat() * size.width
        fun y(v: Double) = (size.height - (v - lo) / span * size.height).toFloat()
        drawLine(TutColors.Outline, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        for (i in 0 until totals.size - 1) {
            drawLine(TutColors.Green, Offset(x(i), y(totals[i])), Offset(x(i + 1), y(totals[i + 1])), 5f, cap = StrokeCap.Round)
        }
        drawCircle(TutColors.Gold, 5.dp.toPx(), Offset(x(totals.size - 1), y(totals.last())))
    }
}

private fun leafOf(n: Gbdt.TreeView, x: DoubleArray): Double = when (n) {
    is Gbdt.TreeView.Leaf -> n.value
    is Gbdt.TreeView.Split -> leafOf(if (x[n.feature] <= n.threshold) n.left else n.right, x)
}

private fun countSplits(n: Gbdt.TreeView, counts: IntArray) {
    if (n is Gbdt.TreeView.Split) {
        counts[n.feature]++
        countSplits(n.left, counts)
        countSplits(n.right, counts)
    }
}

/** An input's value (or a question's threshold) in its own units. */
private fun inputText(i: Int, v: Double): String {
    if (Predictions.isMissing(v)) return "not given"
    return when (i) {
        0, 8 -> Fmt.number(r1(v / 24)).let { if (it == "1") "1 day" else "$it days" }
        2, 6 -> formatDuration(v.roundToInt())
        7, 9 -> (if (v >= 0) "+" else "−") + Fmt.number(r1(abs(v))) + "%"
        12 -> if (v >= 0.5) "yes" else "no"
        else -> Fmt.number(r1(v))
    }
}

private fun r1(v: Double) = (v * 10).roundToInt() / 10.0

@Composable
private fun Box2(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(TutColors.Surface)
            .border(1.dp, TutColors.Outline, RoundedCornerShape(14.dp)).padding(14.dp)
    ) { content() }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = TutColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Body(text: String, color: Color = TutColors.Muted) {
    Text(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun AddRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, color = if (strong) TutColors.Text else TutColors.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f),
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
        Text(value, color = if (strong) TutColors.Green else TutColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Text(
        text, color = TutColors.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.width(44.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, TutColors.Outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}
