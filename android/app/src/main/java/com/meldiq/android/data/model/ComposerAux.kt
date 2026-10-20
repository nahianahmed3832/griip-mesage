package com.meldiq.android.data.model

/**
 * The composer's two buttons on the left (attach, emoji) step aside while someone types, so the field gets their space
 * (round 7). Pure rules, so the timing is testable on the JVM:
 *
 * - typing (the text itself changes, not only the cursor) hides them;
 * - two seconds without typing bring them back;
 * - touching anything outside the composer, or the field losing focus, brings them back at once;
 * - an empty field (sent, cleared) shows them at once.
 *
 * Every change that matters starts a new [State.generation]. The idle timer belongs to one generation, so a timer
 * started before newer typing or an outside touch can never act on the newer state.
 */
object ComposerAux {
    /** How long after the last keystroke the buttons come back. */
    const val IDLE_MS = 2_000L

    data class State(val hidden: Boolean = false, val generation: Long = 0)

    fun onTextChanged(s: State, old: String, new: String): State = when {
        new == old -> s // the cursor or selection moved: not typing
        new.isEmpty() -> State(hidden = false, generation = s.generation + 1)
        else -> State(hidden = true, generation = s.generation + 1)
    }

    /** The idle timer of [generation] ran out. Ignored when anything happened since it started. */
    fun onIdle(s: State, generation: Long): State =
        if (s.hidden && s.generation == generation) State(hidden = false, generation = s.generation + 1) else s

    /** A touch outside the composer, or the field lost focus. */
    fun onOutside(s: State): State = if (s.hidden) State(hidden = false, generation = s.generation + 1) else s
}
