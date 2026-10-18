package com.example.tut

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tut.data.BlockColor
import com.example.tut.data.RirLevel
import com.example.tut.data.SetKind
import com.example.tut.record.CollectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feeds transcript text through the recording-page controller and checks what lands in the
 * database. NOTE: leaves two "Squats" sessions in the app's database (handy for looking at
 * the data page); delete them from the app afterwards.
 */
@RunWith(AndroidJUnit4::class)
class CollectionFlowTest {

    @Test
    fun voiceNotesByVoice() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TutApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val sessionId = withContext(Dispatchers.IO) { app.repo.createSession(System.currentTimeMillis()) }
        try {
            val c = withContext(Dispatchers.Main) { CollectionController(app, sessionId, scope) }
            // Stand-in for the microphone: hand frames to whatever is listening.
            fun mic(from: Long, samples: Int) {
                var pos = from
                val frame = ShortArray(800) { 1000 }
                while (pos < from + samples) { c.engine.tap?.invoke(frame, 800, pos); pos += 800 }
            }

            c.handleText("Add voice note.")
            assertTrue(c.noteRecording.value)
            mic(0, 16_000)
            // Words said during the note are part of it, not actions.
            c.handleText("working set one 100 pounds done")
            assertEquals(0, withContext(Dispatchers.IO) { app.repo.exercises(sessionId) }.size)
            // A sentence that is just "done recording" is cut from the note (it started at 12 000).
            c.handleText("Done recording.", startSample = 12_000)
            assertFalse(c.noteRecording.value)

            // "add recording" continues the same note
            c.handleText("add recording")
            mic(20_000, 8_000)
            c.handleText("done recording")

            // "new recording" makes a separate one
            c.handleText("New recording")
            mic(40_000, 4_000)
            c.handleText("stop recording")

            val notes = withContext(Dispatchers.IO) { app.repo.sessionNotes(sessionId) }
            assertEquals(2, notes.size)
            assertEquals(12_000 + 8_000, (java.io.File(notes[0].path).length() - 44) / 2)
            assertEquals(4_000L, (java.io.File(notes[1].path).length() - 44) / 2)
            c.close()
        } finally {
            withContext(Dispatchers.IO) { app.repo.deleteSession(sessionId) }
            scope.cancel()
        }
    }

    @Test
    fun spokenActionsAreStoredAndCompared() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TutApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            // Last workout
            val first = withContext(Dispatchers.IO) { app.repo.createSession(System.currentTimeMillis()) }
            val c1 = withContext(Dispatchers.Main) { CollectionController(app, first, scope) }
            c1.handleText("Exercise squats. Warm-up set one, two minutes rest, 45 pounds, 12 reps, moderate RIR.")
            assertEquals("no bubble until done", 0, c1.bubbles.value.size)
            c1.handleText("Done.")
            assertEquals(1, c1.bubbles.value.size)
            c1.handleText("Working set 1. Rest 2:30. 100 pounds. 1:20 time under tension. Low RIR. Done.")
            c1.handleText("Working set to. Rest 3 minutes. 105 pounds. 6 reps. Low RIR. Done.")
            assertEquals(
                listOf("Warm-up set 1", "Working set 1", "Working set 2"),
                c1.bubbles.value.map { it.set.label },
            )
            assertTrue(c1.unsaved.value)

            // A second "exercise" renames the session's one exercise instead of adding another.
            c1.handleText("exercise back squats")
            c1.handleText("exercise squats")
            assertEquals(1, withContext(Dispatchers.IO) { app.repo.exercises(first) }.size)

            // "stop" pauses action words; "start" resumes them.
            c1.handleText("Stop. Working set 3, 500 pounds.")
            assertFalse(c1.listening.value)
            c1.handleText("Start")
            assertTrue(c1.listening.value)
            c1.confirmSave()
            c1.close()

            // This workout: heavier top working set -> green
            val second = withContext(Dispatchers.IO) { app.repo.createSession(System.currentTimeMillis()) }
            val c2 = withContext(Dispatchers.Main) { CollectionController(app, second, scope) }
            // values before the set is named are kept and applied to it
            c2.handleText("exercise squats. two minutes rest")
            c2.handleText("working set one, 110 pounds, 8 reps, high RIR, done")
            assertEquals(1, c2.bubbles.value.size)
            c2.close()

            val s2 = withContext(Dispatchers.IO) { app.repo.sessionFull(second)!! }
            val squats = s2.exercises.single()
            assertEquals("Squats", squats.exercise.name)
            val ws1 = squats.sets.single { it.kind == SetKind.WORKING && it.number == 1 }
            assertEquals(120, ws1.restSec)
            assertEquals(110.0, ws1.weight!!, 0.0)
            assertEquals(RirLevel.HIGH, ws1.rirLevel)
            assertEquals(BlockColor.GREEN, squats.exercise.color)
            assertEquals(BlockColor.GREEN, s2.session.color)

            val s1 = withContext(Dispatchers.IO) { app.repo.sessionFull(first)!! }
            assertTrue(s1.session.saved)
            assertEquals("Squats", s1.exercises.single().exercise.name)
            assertTrue(s1.exercises.single().sets.none { it.kind == SetKind.WORKING && it.number == 3 })
        } finally {
            scope.cancel()
        }
    }
}
