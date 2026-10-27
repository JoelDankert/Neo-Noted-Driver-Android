package com.sjdev.noteddriverandroid.core

/** Owns an editor session, so cleanup can never be routed to its replacement. */
class InputSession<T : Any>(
    private val engine: NotedEngine,
    private val emit: (T, List<Effect>) -> Unit,
) {
    private var connection: T? = null

    fun start(target: T?, now: Long) {
        finish(now)
        connection = target
    }

    fun finish(now: Long) {
        val old = connection
        connection = null
        val effects = engine.reset(now)
        if (old != null && effects.isNotEmpty()) emit(old, effects)
    }

    fun down(target: T?, event: Stroke): Boolean {
        moveTo(target, event.eventTime)
        if (target == null) return false
        val result = engine.dispatchDown(event)
        if (result.effects.isNotEmpty()) emit(target, result.effects)
        return result.handled
    }

    fun up(target: T?, event: Stroke): Boolean {
        val ownedBeforeChange = engine.owns(event)
        if (connection !== target) {
            // Balance the old editor's keys there, never against a new editor.
            moveTo(target, event.eventTime)
            return ownedBeforeChange
        }
        val result = engine.dispatchUp(event)
        if (target != null && result.effects.isNotEmpty()) emit(target, result.effects)
        return result.handled
    }

    private fun moveTo(target: T?, now: Long) {
        if (connection === target) return
        finish(now)
        connection = target
    }
}
