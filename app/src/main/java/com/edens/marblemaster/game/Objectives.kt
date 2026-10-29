package com.edens.marblemaster.game

/**
 * Goals rolled at the start of every run.
 *
 * Each kind reads a counter the engine already keeps, so a run can be steered toward clears,
 * tosses, streaks or powerups instead of only "score more". [label] is the short form shown in
 * the HUD, [title] the full sentence shown when the goal is met.
 */
enum class ObjectiveKind(val label: String, val target: Int, val title: String) {
    CLEAR("CLEAR", 30, "Clear 30 marbles"),
    BLOB("BLOB", 7, "Clear 7 in one blob"),
    TOSS("TOSS", 6, "Toss 6 marbles"),
    LEVEL("LEVEL", 6, "Reach level 6"),
    STREAK("STREAK", 3, "Build a x3 streak"),
    CHAIN("CHAIN", 2, "Land 2 cascades"),
    POWER("POWER", 3, "Fire 3 powerups")
}

/** One objective of the current run: its kind plus the progress made toward the target. */
class Objective(val kind: ObjectiveKind) {
    val target: Int get() = kind.target

    /** Counter reached so far, capped at [target]. */
    var progress = 0
        internal set

    /** True once [progress] hit [target] and the reward was paid. */
    var done = false
        internal set
}
