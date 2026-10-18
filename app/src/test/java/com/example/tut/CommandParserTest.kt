package com.example.tut

import com.example.tut.data.RecoveryFeeling
import com.example.tut.data.RirLevel
import com.example.tut.data.SetKind
import com.example.tut.data.WeightUnit
import com.example.tut.parse.Action
import com.example.tut.parse.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {

    private fun p(text: String) = CommandParser.parse(text)

    @Test
    fun specExampleSpelledOut() {
        assertEquals(
            listOf(
                Action.SelectSet(SetKind.WARMUP, 1),
                Action.Rest(120),
                Action.Weight(5.0, WeightUnit.LBS),
                Action.Reps(30),
            ),
            p("Warm-up set one two minutes rest Five pounds Thirty reps"),
        )
    }

    @Test
    fun specExampleWhisperStyle() {
        assertEquals(
            listOf(
                Action.SelectSet(SetKind.WARMUP, 1),
                Action.Rest(120),
                Action.Weight(5.0, WeightUnit.LBS),
                Action.Tut(120),
                Action.Rir(RirLevel.MODERATE, null),
            ),
            p("Warm-up set 1, 2 minutes rest. 5 lbs. 2 minutes time under tension. Moderate RIR."),
        )
    }

    @Test
    fun restVariants() {
        assertEquals(listOf(Action.Rest(130)), p("Rest time, 2:10"))
        assertEquals(listOf(Action.Rest(130)), p("rest time 2.10"))
        assertEquals(listOf(Action.Rest(90)), p("rest 90 seconds"))
        assertEquals(listOf(Action.Rest(150)), p("two and a half minutes rest"))
        assertEquals(listOf(Action.Rest(150)), p("2 minutes 30 seconds rest"))
        // "second" in the singular, as in "a 30-second rest"
        val set1 = Action.SelectSet(SetKind.WORKING, 1)
        for (t in listOf(
            "Working set one, two minutes, 30 second rest.",
            "Working set one, two minutes, 30-second rest.",
            "working set 1 2 minutes 30 second rest",
            "working set one two minutes thirty second rest",
            "Working set one. Two minutes, thirty-second rest.",
            "working set 1, 2 minutes and 30 seconds rest",
            "working set 1 2:30 rest",
        )) assertEquals(t, listOf(set1, Action.Rest(150)), p(t))
        assertEquals(listOf(Action.Rest(30)), p("30 second rest"))
        assertEquals(listOf(Action.Rest(45)), p("rest forty-five second"))
        assertEquals(listOf(Action.Rest(60)), p("a minute rest"))
        assertEquals(listOf(Action.Rest(120)), p("rest two"))
    }

    @Test
    fun durationDoesNotSwallowReps() {
        assertEquals(listOf(Action.Rest(120), Action.Reps(30)), p("rest 2 minutes 30 reps"))
    }

    @Test
    fun weightVariants() {
        assertEquals(listOf(Action.Weight(9.0, WeightUnit.LBS)), p("nine pounds"))
        assertEquals(listOf(Action.Weight(225.0, WeightUnit.LBS)), p("two hundred and twenty five pounds"))
        assertEquals(listOf(Action.Weight(42.5, WeightUnit.KG)), p("42.5 kilograms"))
        assertEquals(listOf(Action.Weight(100.0, null)), p("weight 100"))
        assertEquals(listOf(Action.Weight(2.5, WeightUnit.KG)), p("two point five kilos"))
    }

    @Test
    fun weightHundredths() {
        assertEquals(listOf(Action.Weight(7.25, WeightUnit.LBS)), p("7.25 pounds"))
        assertEquals(listOf(Action.Weight(7.85, WeightUnit.KG)), p("weight 7.85 kg"))
        assertEquals(listOf(Action.Weight(7.25, WeightUnit.LBS)), p("seven point two five pounds"))
        assertEquals(listOf(Action.Weight(7.25, WeightUnit.LBS)), p("seven point twenty five pounds"))
        assertEquals(listOf(Action.Weight(7.85, WeightUnit.LBS)), p("seven point eighty five pounds"))
        assertEquals(listOf(Action.Weight(7.15, WeightUnit.LBS)), p("seven point fifteen pounds"))
        assertEquals(listOf(Action.Weight(7.05, WeightUnit.LBS)), p("seven point oh five pounds"))
        assertEquals(listOf(Action.Weight(7.25, WeightUnit.LBS)), p("seven and a quarter pounds"))
        assertEquals(listOf(Action.Weight(7.75, WeightUnit.LBS)), p("seven and three quarters pounds"))
        // a weight with decimals right after a rest time stays a weight
        assertEquals(listOf(Action.Rest(120), Action.Weight(7.25, WeightUnit.LBS)), p("2 minutes rest 7.25 pounds"))
        assertEquals(listOf(Action.Rest(120), Action.Weight(7.25, WeightUnit.LBS)), p("rest 2 minutes 7.25 pounds"))
    }

    @Test
    fun repsAndTut() {
        assertEquals(listOf(Action.Reps(23)), p("23 reps"))
        assertEquals(listOf(Action.Reps(12)), p("reps twelve"))
        assertEquals(listOf(Action.Tut(80)), p("1:20 time under tension"))
        assertEquals(listOf(Action.Tut(80)), p("TUT one minute twenty seconds"))
        assertEquals(listOf(Action.Tut(45)), p("tut 45"))
    }

    @Test
    fun rirVariants() {
        assertEquals(listOf(Action.Rir(RirLevel.HIGH, null)), p("High R.I.R."))
        assertEquals(listOf(Action.Rir(RirLevel.LOW, null)), p("RIR low"))
        assertEquals(listOf(Action.Rir(null, 4)), p("RIR 4"))
        assertEquals(listOf(Action.Rir(RirLevel.MODERATE, null)), p("medium rir"))
    }

    @Test
    fun setsAndCommands() {
        assertEquals(listOf(Action.SelectSet(SetKind.WORKING, 3)), p("Working set three"))
        assertEquals(listOf(Action.SelectSet(SetKind.WORKING, 2)), p("second working set"))
        assertEquals(listOf(Action.SelectSet(SetKind.WARMUP, 12)), p("warmup set 12"))
        assertEquals(listOf(Action.Stop), p("Stop."))
        assertEquals(listOf(Action.Start, Action.SelectSet(SetKind.WORKING, 1)), p("Start. Working set one."))
        assertEquals(listOf(Action.Save), p("save"))
        assertEquals(listOf(Action.Reps(8), Action.Done), p("8 reps. Done."))
    }

    @Test
    fun voiceNoteCommands() {
        assertEquals(listOf(Action.AddNote), p("Add voice note."))
        assertEquals(listOf(Action.AddNote), p("add a voicenote"))
        assertEquals(listOf(Action.AddNote), p("Add recording"))
        assertEquals(listOf(Action.NewNote), p("New recording."))
        assertEquals(listOf(Action.EndNote), p("Done recording."))
        assertEquals(listOf(Action.EndNote), p("stop recording"))
        // "done recording" must not also count as "done" (finish the set)
        assertEquals(listOf(Action.Reps(8), Action.Done), p("8 reps, done"))
    }

    @Test
    fun misheardSetNumbers() {
        assertEquals(listOf(Action.SelectSet(SetKind.WORKING, 2)), p("Working set to"))
        assertEquals(listOf(Action.SelectSet(SetKind.WORKING, 4)), p("working set for"))
        assertEquals(listOf(Action.SelectSet(SetKind.WARMUP, 2)), p("warm up set too"))
    }

    @Test
    fun misheardRir() {
        assertEquals(listOf(Action.Rir(RirLevel.HIGH, null)), p("High R.A.R."))
        assertEquals(listOf(Action.Rir(RirLevel.LOW, null)), p("rar low"))
        assertEquals(listOf(Action.Rir(null, 3)), p("R E R 3"))
        assertEquals(listOf(Action.Rir(RirLevel.MODERATE, null)), p("moderate are eye are"))
        assertEquals(listOf(Action.Rir(RirLevel.MODERATE, null)), p("modern RIR"))
        assertEquals(listOf(Action.Rir(RirLevel.HIGH, null)), p("RIR hi"))
        assertEquals(listOf(Action.Rir(RirLevel.LOW, null)), p("lo rir"))
    }

    @Test
    fun misheardSets() {
        assertEquals(listOf(Action.SelectSet(SetKind.WARMUP, 2)), p("Warm up sex 2"))
        assertEquals(listOf(Action.SelectSet(SetKind.WORKING, 1)), p("working said one"))
        assertEquals(listOf(Action.SelectSet(SetKind.WORKING, 3)), p("walking set three"))
        assertEquals(listOf(Action.SelectSet(SetKind.WARMUP, 3)), p("worm up set tree"))
        assertEquals(listOf(Action.SelectSet(SetKind.WORKING, 2)), p("working sex to"))
    }

    @Test
    fun misheardUnitsAndTut() {
        assertEquals(listOf(Action.Weight(45.0, WeightUnit.LBS)), p("45 ponds"))
        assertEquals(listOf(Action.Weight(20.0, WeightUnit.KG)), p("20 kilo grams"))
        assertEquals(listOf(Action.Weight(100.0, null)), p("wait 100"))
        assertEquals(listOf(Action.Reps(12)), p("12 wraps"))
        assertEquals(listOf(Action.Rest(120)), p("rest to minutes"))
        assertEquals(listOf(Action.Rest(90)), p("rust 90 seconds"))
        assertEquals(listOf(Action.Tut(80)), p("1:20 time on tension"))
        assertEquals(listOf(Action.Tut(45)), p("toot 45"))
    }

    @Test
    fun misheardCommands() {
        assertEquals(listOf(Action.Save), p("Safe."))
        assertEquals(listOf(Action.Done), p("Dun."))
        assertEquals(listOf(Action.EndNote), p("Dawn recording."))
        assertEquals(listOf(Action.EndNote), p("done according"))
        assertEquals(listOf(Action.AddNote), p("add boys note"))
        assertEquals(listOf(Action.NewNote), p("knew recording"))
        assertEquals(listOf(Action.Exercise("squats")), p("Exorcise squats"))
    }

    @Test
    fun misheardWordsDoNotCrossOver() {
        // "sex" on its own is not a set, and doesn't change the rest time next to it
        assertEquals(listOf(Action.Rest(120)), p("sex two minutes rest"))
        // "wait" before a time stays a rest, not a weight
        assertEquals(listOf(Action.Rest(120)), p("wait 2 minutes rest"))
        // "walking" only means "working" right before "set"
        assertEquals(listOf(Action.Exercise("walking lunges")), p("exercise walking lunges"))
        // "according" only means "recording" after a voice-note command
        assertEquals(emptyList<Action>(), p("according to the plan"))
        // "safe" mid-sentence is not "save"
        assertEquals(emptyList<Action>(), p("is that safe for my back"))
        // "hi" without RIR next to it is nothing
        assertEquals(emptyList<Action>(), p("hi there"))
        // rear delts is not RIR
        assertEquals(listOf(Action.Exercise("rear delt flyes")), p("exercise rear delt flyes"))
    }

    @Test
    fun yesNoAnswers() {
        for (yes in listOf("Yes.", "yeah", "Confirm.", "yes please", "sure", "save it", "Safe.")) {
            assertEquals(yes, true, CommandParser.answer(yes))
        }
        for (no in listOf("No.", "nope", "Abort.", "Never mind.", "nevermind", "cancel", "don't save")) {
            assertEquals(no, false, CommandParser.answer(no))
        }
        for (neither in listOf("working set 2", "uh", "yes no")) {
            assertEquals(neither, null, CommandParser.answer(neither))
        }
    }

    @Test
    fun wholeSetInOneBreath() {
        val expected = listOf(
            Action.SelectSet(SetKind.WORKING, 1),
            Action.Tut(120),
            Action.Weight(10.0, WeightUnit.LBS),
            Action.Reps(12),
            Action.Rir(RirLevel.MODERATE, null),
            Action.Done,
        )
        // Whisper with punctuation, without, and with everything spelled out
        assertEquals(expected, p("Working set 1, 2 minutes time under tension, 10 pounds, 12 reps, moderate RIR, done."))
        assertEquals(expected, p("working set 1 2 minutes time under tension 10 pounds 12 reps moderate rir done"))
        assertEquals(expected, p("working set one two minutes time under tension ten pounds twelve reps moderate RIR done"))
        assertEquals(
            listOf(
                Action.SelectSet(SetKind.WARMUP, 2), Action.Rest(90), Action.Weight(45.0, WeightUnit.LBS),
                Action.Reps(8), Action.Rir(RirLevel.HIGH, null), Action.Done,
            ),
            p("warm up set two 90 seconds rest 45 pounds 8 reps high RIR done"),
        )
    }

    @Test
    fun exerciseName() {
        assertEquals(
            listOf(Action.Exercise("squats"), Action.SelectSet(SetKind.WARMUP, 1)),
            p("Exercise squats. Warm up set one."),
        )
        assertEquals(listOf(Action.Exercise("bench press")), p("exercise bench press"))
    }

    @Test
    fun fillerWordsIgnored() {
        assertEquals(
            listOf(Action.SelectSet(SetKind.WORKING, 1), Action.Weight(135.0, WeightUnit.LBS), Action.Reps(8)),
            p("okay so this is working set one and I'm going with 135 pounds, did 8 reps, that felt hard"),
        )
        assertEquals(emptyList<Action>(), p("Thank you."))
    }

    @Test
    fun renamedBuiltInWords() {
        val words = mapOf("start" to "begin", "rest" to "Pause", "done" to "")
        assertEquals(listOf(Action.Start, Action.Rest(120)), CommandParser.parse("Begin. Two minutes pause", words))
        // The old word stops working; an empty box keeps the built-in word.
        assertEquals(listOf(Action.Done), CommandParser.parse("start, done", words))
        // Two words can swap.
        val swap = mapOf("start" to "stop", "stop" to "start")
        assertEquals(listOf(Action.Stop, Action.Start), CommandParser.parse("start stop", swap))
        assertEquals(true, CommandParser.answer("affirmative", mapOf("yes" to "affirmative")))
    }

    @Test
    fun weightWithoutSayingWeight() {
        val lbs = listOf(Action.Weight(10.0, WeightUnit.LBS))
        for (t in listOf("10 pounds", "£10", "£10.", "10lbs", "10lb", "10 lbs")) assertEquals(t, lbs, p(t))
        assertEquals(listOf(Action.Weight(10.0, WeightUnit.KG)), p("10kg"))
        assertEquals(listOf(Action.Weight(10.0, WeightUnit.LBS), Action.Reps(12)), p("£10, 12 reps"))
    }

    @Test
    fun sleepHours() {
        assertEquals(listOf(Action.Sleep(8.0)), p("Eight hours of sleep"))
        assertEquals(listOf(Action.Sleep(6.0)), p("6 hours sleep"))
        assertEquals(listOf(Action.Sleep(7.5)), p("I slept seven and a half hours"))
        assertEquals(listOf(Action.Sleep(9.0)), p("sleep 9 hours"))
        assertEquals(listOf(Action.Sleep(6.5)), p("six point five hours of sleep"))
    }

    @Test
    fun sleepDoesNotTouchSetValues() {
        assertEquals(
            listOf(Action.Sleep(8.0), Action.SelectSet(SetKind.WORKING, 1), Action.Weight(100.0, WeightUnit.LBS), Action.Reps(10)),
            p("8 hours of sleep working set 1 100 pounds 10 reps"),
        )
        assertEquals(emptyList<Action>(), p("30 hours of sleep"))
    }

    @Test
    fun recoveryFeeling() {
        fun r(f: RecoveryFeeling) = listOf(Action.Recovery(f))
        assertEquals(r(RecoveryFeeling.FRESH), p("Recovery feeling fresh"))
        assertEquals(r(RecoveryFeeling.VERY_FRESH), p("recovery feeling very fresh"))
        assertEquals(r(RecoveryFeeling.CANT_TELL), p("Recovery feeling, can't tell."))
        assertEquals(r(RecoveryFeeling.CANT_TELL), p("recovery feeling can’t tell"))
        assertEquals(r(RecoveryFeeling.SORE), p("recovery feeling sore"))
        assertEquals(r(RecoveryFeeling.SORE), p("recovery feeling soar"))
        assertEquals(r(RecoveryFeeling.KIND_OF_SORE), p("recovery feeling kind of sore"))
        assertEquals(r(RecoveryFeeling.KIND_OF_SORE), p("recover feeling kinda soar"))
        assertEquals(emptyList<Action>(), p("my legs feel sore"))
    }

    @Test
    fun recoveryDoesNotTouchSetValues() {
        assertEquals(
            listOf(Action.Recovery(RecoveryFeeling.FRESH), Action.Sleep(8.0), Action.SelectSet(SetKind.WORKING, 1), Action.Reps(10)),
            p("recovery feeling fresh 8 hours of sleep working set 1 10 reps"),
        )
    }
}
