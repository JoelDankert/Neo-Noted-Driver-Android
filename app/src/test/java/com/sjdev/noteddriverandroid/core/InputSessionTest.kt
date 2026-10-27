package com.sjdev.noteddriverandroid.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InputSessionTest {
    private class Target { val effects = mutableListOf<Effect>() }
    private fun engine() = NotedEngine(NotedData.parse(File("src/main/assets/noted_data.json").readText()))
    private fun session(engine: NotedEngine = engine()) = InputSession<Target>(engine) { target, effects -> target.effects += effects }
    private fun letter() = Stroke(K.A + 16, 16, 10, 11)

    @Test fun downWithoutConnectionThenUpWithConnectionIsDelegated() {
        val candidate = session()
        val next = Target()
        assertFalse(candidate.down(null, letter()))
        assertFalse(candidate.up(next, letter().copy(eventTime = 12)))
        assertTrue(next.effects.isEmpty())
    }

    @Test fun consumedTextAndModifiersOwnTheirEmptyUps() {
        val candidate = session()
        val target = Target()
        candidate.start(target, 1)
        assertTrue(candidate.down(target, letter()))
        target.effects.clear()
        assertTrue(candidate.up(target, letter().copy(eventTime = 12)))
        assertTrue(target.effects.isEmpty())
        val caps = Stroke(K.CAPS, 58, 20, 20)
        assertTrue(candidate.down(target, caps))
        assertTrue(candidate.up(target, caps.copy(eventTime = 21)))
        assertTrue(target.effects.isEmpty())
    }

    @Test fun changedConnectionReleasesOnlyTheOldTargetAndConsumesItsOwnedUp() {
        val candidate = session()
        val old = Target()
        val next = Target()
        candidate.start(old, 1)
        val ctrl = Stroke(K.CTRL_L, 29, 2, 2)
        candidate.down(old, ctrl)
        candidate.down(old, letter())
        assertTrue(candidate.up(next, letter().copy(eventTime = 12)))
        assertTrue(next.effects.isEmpty())
        val keys = old.effects.filterIsInstance<Effect.Key>()
        assertEquals(keys.count { it.down }, keys.count { !it.down })
        assertFalse(candidate.up(next, letter().copy(eventTime = 13)))
    }

    @Test fun connectionLossClearsOwnershipAndBalancesOldKeys() {
        val core = engine()
        val candidate = session(core)
        val old = Target()
        candidate.start(old, 1)
        val ctrl = Stroke(K.CTRL_L, 29, 2, 2)
        candidate.down(old, ctrl)
        assertTrue(candidate.up(null, ctrl.copy(eventTime = 3)))
        assertFalse(core.owns(ctrl))
        assertEquals(listOf(true, false), old.effects.filterIsInstance<Effect.Key>().map { it.down })
    }

    @Test fun fieldRestartRestoresHeldShiftThenClearsItsSnapshot() {
        val candidate = session()
        val old = Target()
        val next = Target()
        candidate.start(old, 1)
        candidate.down(old, Stroke(K.SHIFT_L, 42, 2, 2))
        candidate.start(next, 3)
        candidate.down(next, letter().copy(metaState = Meta.SHIFT_RIGHT, metaStateReliable = true))
        assertEquals(listOf(Effect.Text("Z")), next.effects)
        candidate.up(next, letter().copy(eventTime = 12, metaState = Meta.SHIFT_RIGHT, metaStateReliable = true))
        next.effects.clear()
        candidate.down(next, letter().copy(downTime = 20, eventTime = 20, metaStateReliable = true))
        assertEquals(listOf(Effect.Text("z")), next.effects)
    }

    @Test fun finishBalancesHeldKeysAndDelegatesTheirLaterUnownedRelease() {
        val candidate = session()
        val old = Target()
        candidate.start(old, 1)
        val ctrl = Stroke(K.CTRL_L, 29, 2, 2)
        candidate.down(old, ctrl)
        candidate.finish(3)
        assertEquals(listOf(true, false), old.effects.filterIsInstance<Effect.Key>().map { it.down })
        assertFalse(candidate.up(null, ctrl.copy(eventTime = 4)))
        val next = Target()
        candidate.start(next, 5)
        assertFalse(candidate.up(next, ctrl.copy(eventTime = 6)))
        assertTrue(next.effects.isEmpty())
    }

    @Test fun fieldRestartUsesIncomingHeldCtrlWithoutReplayingOldOutput() {
        val candidate = session()
        val old = Target()
        val next = Target()
        candidate.start(old, 1)
        candidate.down(old, Stroke(K.CTRL_L, 29, 2, 2))
        candidate.start(next, 3)
        assertTrue(next.effects.isEmpty())
        candidate.down(next, letter().copy(metaState = Meta.CTRL, metaStateReliable = true))
        assertEquals(Meta.CTRL, (next.effects.single() as Effect.Key).meta)
        candidate.finish(20)
        val keys = next.effects.filterIsInstance<Effect.Key>()
        assertEquals(listOf(true, false), keys.map { it.down })
    }
}
