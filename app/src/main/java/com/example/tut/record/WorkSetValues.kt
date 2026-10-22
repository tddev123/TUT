package com.example.tut.record

import com.example.tut.data.DataOps
import com.example.tut.data.RirLevel
import com.example.tut.data.WeightUnit
import com.example.tut.data.WorkSet
import com.example.tut.parse.Action

/** A partial set of values heard in speech; null fields leave the stored value unchanged. */
data class WorkSetValues(
    val restSec: Int? = null,
    val weight: Double? = null,
    val unit: WeightUnit? = null,
    val reps: Int? = null,
    val tutSec: Int? = null,
    val rirLevel: RirLevel? = null,
    val rirValue: Int? = null,
    val endRangeSec: Int? = null,
) {
    fun isEmpty() = this == WorkSetValues()

    fun with(action: Action, defaultUnit: WeightUnit, ops: DataOps): WorkSetValues = when (action) {
        is Action.Rest -> copy(restSec = action.seconds)
        is Action.Weight -> copy(weight = action.value, unit = action.unit ?: defaultUnit)
        is Action.Reps -> copy(reps = action.count)
        is Action.Tut -> copy(tutSec = action.seconds)
        is Action.EndRange -> copy(endRangeSec = action.seconds)
        is Action.Rir -> if (action.value != null) copy(rirLevel = ops.levelFor(action.value), rirValue = action.value)
        else copy(rirLevel = action.level, rirValue = null)
        else -> this
    }

    fun applyTo(set: WorkSet): WorkSet {
        val rirChanged = rirLevel != null || rirValue != null
        return set.copy(
            restSec = restSec ?: set.restSec,
            weight = weight ?: set.weight,
            unit = unit ?: set.unit,
            reps = reps ?: set.reps,
            tutSec = tutSec ?: set.tutSec,
            rirLevel = if (rirChanged) rirLevel else set.rirLevel,
            rirValue = if (rirChanged) rirValue else set.rirValue,
            endRangeSec = endRangeSec ?: set.endRangeSec,
        )
    }
}
