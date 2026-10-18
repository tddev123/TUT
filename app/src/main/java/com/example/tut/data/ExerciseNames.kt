package com.example.tut.data

/** Short names that mean the same exercise as a longer one ("bench" is bench press). */
object ExerciseNames {
    private val aliases = mapOf(
        "bench" to "Bench press",
    )

    /** The name to store: an alias becomes its full exercise name, anything else stays as given. */
    fun canonical(name: String): String = aliases[name.trim().lowercase()] ?: name.trim()

    /** Every alias, lower-case, with the name it stands for. */
    val all: Map<String, String> get() = aliases
}
