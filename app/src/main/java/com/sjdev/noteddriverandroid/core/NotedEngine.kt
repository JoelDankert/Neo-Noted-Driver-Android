package com.sjdev.noteddriverandroid.core

data class Stroke(
    val keyCode: Int,
    val scanCode: Int = 0,
    val downTime: Long = 0,
    val eventTime: Long = 0,
    val repeatCount: Int = 0,
    val deviceId: Int = 0,
    val flags: Int = 0,
    val metaState: Int = 0,
    val metaStateReliable: Boolean = false,
)

data class KeyDispatch(val handled: Boolean, val effects: List<Effect>)

sealed interface Effect {
    data class Text(val value: String) : Effect
    data class Key(
        val down: Boolean,
        val keyCode: Int,
        val downTime: Long,
        val eventTime: Long,
        val repeatCount: Int,
        val meta: Int,
        val flags: Int = 0,
    ) : Effect
}

class NotedEngine(
    private val data: NotedData,
    private val legacyOverrides: LegacyOverrides = LegacyOverrides.EMPTY,
) {
    private data class PhysicalId(val deviceId: Int, val scanCode: Int, val keyCode: Int)
    private sealed interface Held {
        data class Consumed(val token: String = "") : Held
        data class Native(val keyCode: Int, val meta: Int, val downTime: Long, val flags: Int) : Held
    }

    private enum class ModifierRole {
        LEFT_SHIFT, RIGHT_SHIFT, LEFT_MOD3, RIGHT_MOD3, LEFT_MOD4, RIGHT_MOD4,
        LEFT_CTRL, RIGHT_CTRL, LEFT_ALT, LEFT_META, RIGHT_META,
    }

    private val held = mutableMapOf<PhysicalId, Held>()
    private val modifierPresses = mutableMapOf<PhysicalId, ModifierRole>()
    private var reportedModifiers: Set<ModifierRole> = emptySet()
    private val modifiers: Set<ModifierRole>
        get() = modifierPresses.values.toSet() + reportedModifiers
    private var shiftLock = false
    private var mod4Lock = false
    private var nonModifierUsed = false
    private var compose = ""

    fun dispatchDown(event: Stroke) = KeyDispatch(true, down(event))

    fun dispatchUp(event: Stroke): KeyDispatch {
        val owned = owns(event)
        return KeyDispatch(owned, up(event))
    }

    fun owns(event: Stroke): Boolean = event.id() in held

    fun down(event: Stroke): List<Effect> {
        reconcileMetadata(event)
        val id = event.id()
        held[id]?.let { previous ->
            return when (previous) {
                is Held.Consumed -> if (event.repeatCount > 0) emitToken(previous.token) else emptyList()
                is Held.Native -> listOf(
                    Effect.Key(true, previous.keyCode, previous.downTime, event.eventTime, event.repeatCount, previous.meta, previous.flags),
                )
            }
        }

        modifierRole(event)?.let { role ->
            modifierDown(role, id)
            val output = if (role.isMod3()) suppressNativeShifts(event.eventTime) else nativeModifierEvent(event, true)
            val ownsNativeKey = event.keyCode in NATIVE_MODIFIER_KEYS &&
                !(role.isShift() && modifiers.any { it.isMod3() })
            held[id] = if (ownsNativeKey) Held.Native(event.keyCode, shortcutMeta(), event.downTime, event.flags) else Held.Consumed()
            return output
        }

        nonModifierUsed = true
        legacyOverrides[legacyLayer(), event.keyCode]?.let { action ->
            return emitLegacy(id, event, action)
        }
        val physical = PhysicalKeys.resolve(event.keyCode, event.scanCode)
            ?: return holdNative(id, event, event.keyCode, shortcutMeta())
        val row = data.rows[physical]
            ?: return holdNative(id, event, event.keyCode, shortcutMeta())

        if (compose.isNotEmpty() && event.keyCode == K.ESCAPE) {
            compose = ""
            held[id] = Held.Consumed()
            return emptyList()
        }
        if (compose.isNotEmpty() && event.keyCode == K.BACKSPACE) {
            compose = removeLastEncodedToken(compose)
            held[id] = Held.Consumed()
            return emptyList()
        }

        val token = row.slots[resolveLayer(row) - 1]
        if (shortcutHeld()) {
            val baseToken = token.ifEmpty { row.slots[0] }
            val action = shortcutAction(baseToken) ?: shortcutAction(row.slots[0])
            if (action != null) return holdNative(id, event, action.keyCode, action.meta or shortcutMeta())
            held[id] = Held.Consumed()
            return emptyList()
        }

        if (compose.isEmpty()) {
            NotedActions.parse(token)?.let { action ->
                if (action.keyCode == K.COMPOSE) {
                    compose = "S__Comp"
                    held[id] = Held.Consumed()
                    return emptyList()
                }
                return holdNative(id, event, action.keyCode, action.meta or shortcutMeta())
            }
        }
        held[id] = Held.Consumed(token)
        return emitToken(token)
    }

    fun up(event: Stroke): List<Effect> {
        reconcileMetadata(event, modifierRole(event))
        val previous = held.remove(event.id()) ?: return emptyList()
        modifierRole(event)?.let {
            val output = if (previous is Held.Native) {
                listOf(Effect.Key(false, previous.keyCode, previous.downTime, event.eventTime, 0, previous.meta, previous.flags))
            } else {
                emptyList()
            }
            modifierPresses.remove(event.id())
            return output
        }
        return if (previous is Held.Native) {
            listOf(Effect.Key(false, previous.keyCode, previous.downTime, event.eventTime, 0, previous.meta, previous.flags))
        } else {
            emptyList()
        }
    }

    fun reset(now: Long): List<Effect> {
        val output = held.values.filterIsInstance<Held.Native>().map {
            Effect.Key(false, it.keyCode, it.downTime, now, 0, it.meta, it.flags)
        }
        held.clear()
        modifierPresses.clear()
        reportedModifiers = emptySet()
        shiftLock = false
        mod4Lock = false
        nonModifierUsed = false
        compose = ""
        return output
    }

    private fun Stroke.id() = PhysicalId(deviceId, scanCode, keyCode)

    private fun holdNative(id: PhysicalId, event: Stroke, keyCode: Int, meta: Int): List<Effect> {
        held[id] = Held.Native(keyCode, meta, event.downTime, event.flags)
        return listOf(Effect.Key(true, keyCode, event.downTime, event.eventTime, event.repeatCount, meta, event.flags))
    }

    private fun modifierRole(event: Stroke): ModifierRole? = when {
        event.keyCode == K.SHIFT_L -> ModifierRole.LEFT_SHIFT
        event.keyCode == K.SHIFT_R -> ModifierRole.RIGHT_SHIFT
        event.keyCode == K.CAPS -> ModifierRole.LEFT_MOD3
        event.scanCode == 40 || event.scanCode == 0 && event.keyCode == K.APOSTROPHE -> ModifierRole.RIGHT_MOD3
        event.scanCode == 86 || event.scanCode == 0 && event.keyCode == K.BACKSLASH -> ModifierRole.LEFT_MOD4
        event.keyCode == K.ALT_R -> ModifierRole.RIGHT_MOD4
        event.keyCode == K.CTRL_L -> ModifierRole.LEFT_CTRL
        event.keyCode == K.CTRL_R -> ModifierRole.RIGHT_CTRL
        event.keyCode == K.ALT_L -> ModifierRole.LEFT_ALT
        event.keyCode == K.META_L -> ModifierRole.LEFT_META
        event.keyCode == K.META_R -> ModifierRole.RIGHT_META
        else -> null
    }

    private fun modifierDown(role: ModifierRole, id: PhysicalId) {
        // Chords require observed physical presses, not snapshot bits that already
        // include this very modifier-down event. Multiple devices own separately.
        val physical = modifierPresses.values.toSet()
        if (role !in physical) {
            if (role.isShift() && physical.any { it.isShift() } && !nonModifierUsed) shiftLock = !shiftLock
            if (role.isMod3() && physical.any { it.isMod3() } && !nonModifierUsed) compose = "S__Comp"
            if (role.isMod4() && physical.any { it.isMod4() } && !nonModifierUsed) mod4Lock = !mod4Lock
        }
        modifierPresses[id] = role
        nonModifierUsed = false
    }

    private fun reconcileMetadata(event: Stroke, released: ModifierRole? = null) {
        // Snapshots describe this event, not a persistent second keyboard. Never
        // replay them into another device's event or infer Caps/Mod3 from locks.
        reportedModifiers = if (!event.metaStateReliable) emptySet() else buildSet {
            val bits = event.metaState
            fun sides(generic: Int, left: Int, right: Int, leftRole: ModifierRole, rightRole: ModifierRole) {
                if (bits and left != 0) add(leftRole)
                if (bits and right != 0) add(rightRole)
                if (bits and generic != 0 && bits and (left or right) == 0) add(leftRole)
            }
            sides(Meta.SHIFT, Meta.SHIFT_LEFT, Meta.SHIFT_RIGHT, ModifierRole.LEFT_SHIFT, ModifierRole.RIGHT_SHIFT)
            sides(Meta.CTRL, Meta.CTRL_LEFT, Meta.CTRL_RIGHT, ModifierRole.LEFT_CTRL, ModifierRole.RIGHT_CTRL)
            sides(Meta.META, Meta.META_LEFT, Meta.META_RIGHT, ModifierRole.LEFT_META, ModifierRole.RIGHT_META)
            sides(Meta.ALT, Meta.ALT_LEFT, Meta.ALT_RIGHT, ModifierRole.LEFT_ALT, ModifierRole.RIGHT_MOD4)
        } - setOfNotNull(released)
    }

    private fun ModifierRole.isShift() = this == ModifierRole.LEFT_SHIFT || this == ModifierRole.RIGHT_SHIFT
    private fun ModifierRole.isMod3() = this == ModifierRole.LEFT_MOD3 || this == ModifierRole.RIGHT_MOD3
    private fun ModifierRole.isMod4() = this == ModifierRole.LEFT_MOD4 || this == ModifierRole.RIGHT_MOD4

    private fun nativeModifierEvent(event: Stroke, down: Boolean): List<Effect> {
        if (event.keyCode !in NATIVE_MODIFIER_KEYS) return emptyList()
        if (modifierRole(event)?.isShift() == true && modifiers.any { it.isMod3() }) return emptyList()
        return listOf(Effect.Key(down, event.keyCode, event.downTime, event.eventTime, event.repeatCount, shortcutMeta(), event.flags))
    }

    private fun suppressNativeShifts(eventTime: Long): List<Effect> {
        val output = mutableListOf<Effect>()
        held.entries.forEach { entry ->
            val native = entry.value as? Held.Native ?: return@forEach
            if (native.keyCode == K.SHIFT_L || native.keyCode == K.SHIFT_R) {
                output += Effect.Key(false, native.keyCode, native.downTime, eventTime, 0, native.meta, native.flags)
                entry.setValue(Held.Consumed())
            }
        }
        return output
    }

    private fun resolveLayer(row: NotedRow): Int {
        val shift = modifiers.any { it.isShift() }
        val mod3 = modifiers.any { it.isMod3() }
        val mod4 = modifiers.any { it.isMod4() }
        val position = (if (mod4Lock) 16 else 0) + (if (mod4) 8 else 0) +
            (if (mod3) 4 else 0) + (if (shiftLock) 2 else 0) + (if (shift) 1 else 0)
        val normal = NORMAL_LAYERS[position].digitToInt()
        val letter = LETTER_LAYERS[position].digitToInt()
        return when {
            SLOT_SEVEN[position] == '1' && row.slots[6].isNotEmpty() -> 7
            SLOT_EIGHT[position] == '1' && row.slots[7].isNotEmpty() -> 8
            row.letter -> letter
            else -> normal
        }
    }

    internal fun selectedToken(physical: String): String? =
        data.rows[physical]?.let { row -> row.slots[resolveLayer(row) - 1] }

    private fun legacyLayer(): Int {
        val shift = modifiers.any { it.isShift() }
        val mod3 = modifiers.any { it.isMod3() }
        val mod4 = modifiers.any { it.isMod4() }
        return when {
            mod3 && mod4 -> 6
            mod3 && shift -> 5
            mod4 -> 4
            mod3 -> 3
            shift -> 2
            else -> 1
        }
    }

    private fun emitLegacy(id: PhysicalId, event: Stroke, action: LegacyAction): List<Effect> = when (action) {
        is LegacyAction.Text -> {
            held[id] = Held.Consumed()
            listOf(Effect.Text(action.value))
        }
        is LegacyAction.Key -> holdNative(id, event, action.keyCode, action.meta or shortcutMeta())
        is LegacyAction.Sequence -> {
            held[id] = Held.Consumed()
            action.steps.flatMap { immediateNative(NativeAction(it.keyCode, it.meta or shortcutMeta())) }
        }
    }

    private fun shortcutHeld() = modifiers.any { it in SHORTCUT_MODIFIERS }

    private fun shortcutMeta(): Int {
        var meta = 0
        if (modifiers.any { it.isShift() } && modifiers.none { it.isMod3() }) meta = meta or Meta.SHIFT
        if (ModifierRole.LEFT_CTRL in modifiers || ModifierRole.RIGHT_CTRL in modifiers) meta = meta or Meta.CTRL
        if (ModifierRole.LEFT_ALT in modifiers) meta = meta or Meta.ALT
        if (ModifierRole.LEFT_META in modifiers || ModifierRole.RIGHT_META in modifiers) meta = meta or Meta.META
        return meta
    }

    private fun emitToken(value: String): List<Effect> {
        if (value.isEmpty()) return emptyList()
        val encoded = if (value.startsWith("S_") || value.startsWith("T_")) value else encodeCodePoints(value)
        val candidate = compose + encoded
        val output = when {
            candidate in data.compose -> data.compose.getValue(candidate).also { compose = "" }
            candidate in data.composePrefixes -> {
                compose = candidate
                return emptyList()
            }
            compose.isNotEmpty() -> {
                compose = ""
                return emptyList()
            }
            else -> encoded
        }
        return decodeOutput(output)
    }

    private fun encodeCodePoints(value: String): String = buildString {
        var offset = 0
        while (offset < value.length) {
            val codePoint = Character.codePointAt(value, offset)
            append("U%06X".format(codePoint))
            offset += Character.charCount(codePoint)
        }
    }

    private fun decodeOutput(output: String): List<Effect> {
        val effects = mutableListOf<Effect>()
        val text = StringBuilder()
        var offset = 0
        fun flushText() {
            if (text.isNotEmpty()) {
                effects += Effect.Text(text.toString())
                text.clear()
            }
        }
        while (offset < output.length) {
            if (output[offset] == 'U' && offset + 7 <= output.length) {
                val codePoint = output.substring(offset + 1, offset + 7).toInt(16)
                NotedActions.controlCharacter(codePoint)?.let { action ->
                    flushText()
                    effects += immediateNative(action)
                } ?: text.appendCodePoint(codePoint)
                offset += 7
                continue
            }
            val actionToken = NotedActionsToken.longestAt(output, offset)
                ?: error("Unknown internal output token at offset $offset: $output")
            flushText()
            effects += immediateNative(NotedActions.parse(actionToken)!!)
            offset += actionToken.length
        }
        flushText()
        return effects
    }

    private fun immediateNative(action: NativeAction): List<Effect> = listOf(
        Effect.Key(true, action.keyCode, 0, 0, 0, action.meta),
        Effect.Key(false, action.keyCode, 0, 0, 0, action.meta),
    )

    private fun shortcutAction(value: String): NativeAction? = when {
        value.length == 1 && value[0] in 'a'..'z' -> NativeAction(K.A + (value[0] - 'a'))
        value.length == 1 && value[0] in 'A'..'Z' -> NativeAction(K.A + (value[0] - 'A'))
        value.length == 1 && value[0] in '0'..'9' -> NativeAction(K.D0 + (value[0] - '0'))
        else -> NotedActions.parse(value)?.takeUnless { it.keyCode == K.COMPOSE }
    }

    private fun removeLastEncodedToken(value: String): String {
        if (value.isEmpty()) return value
        return if (value.length >= 7) value.dropLast(7) else ""
    }

    private object NotedActionsToken {
        fun longestAt(value: String, offset: Int): String? {
            val remaining = value.substring(offset)
            return (8 downTo 7).asSequence()
                .mapNotNull { length -> remaining.takeIf { it.length >= length }?.substring(0, length) }
                .firstOrNull { NotedActions.parse(it) != null }
        }
    }

    private companion object {
        const val NORMAL_LAYERS = "12123535444466664444353512126666"
        const val LETTER_LAYERS = "12213535444466664444353512216666"
        const val SLOT_SEVEN = "00000000010100000101000000000000"
        const val SLOT_EIGHT = "00000000000001010000000000000101"
        val NATIVE_MODIFIER_KEYS = setOf(K.CTRL_L, K.CTRL_R, K.ALT_L, K.META_L, K.META_R, K.SHIFT_L, K.SHIFT_R)
        val SHORTCUT_MODIFIERS = setOf(
            ModifierRole.LEFT_CTRL, ModifierRole.RIGHT_CTRL, ModifierRole.LEFT_ALT,
            ModifierRole.LEFT_META, ModifierRole.RIGHT_META,
        )
    }
}
