package com.sjdev.noteddriverandroid.core

data class NativeAction(val keyCode: Int, val meta: Int = 0)

object NotedActions {
    private val actions = mapOf(
        "S__PgUp" to K.PAGE_UP,
        "S____Up" to K.DPAD_UP,
        "S___Del" to K.FORWARD_DEL,
        "S__PgDn" to K.PAGE_DOWN,
        "S__Home" to K.HOME,
        "S__Left" to K.DPAD_LEFT,
        "S__Down" to K.DPAD_DOWN,
        "S__Rght" to K.DPAD_RIGHT,
        "S___End" to K.END,
        "S___Ins" to K.INSERT,
        "S__Back" to K.BACKSPACE,
        "S___Tab" to K.TAB,
        "S__Entr" to K.ENTER,
        "S____Esc" to K.ESCAPE,
        "S__NEnt" to K.NUM_ENTER,
        "S__NAdd" to K.NUM_ADD,
        "S__NSub" to K.NUM_SUB,
        "S__NMul" to K.NUM_MUL,
        "S__NDiv" to K.NUM_DIV,
        // Navigation key codes keep keypad navigation independent of Num Lock.
        "S__NClr" to K.CLEAR,
        "S__NDel" to K.FORWARD_DEL,
        "S__NEnd" to K.END,
        "S__NHom" to K.HOME,
        "S__NIns" to K.INSERT,
        "S__NPDn" to K.PAGE_DOWN,
        "S__NPUp" to K.PAGE_UP,
        "S__N_Dn" to K.DPAD_DOWN,
        "S__N_Le" to K.DPAD_LEFT,
        "S__N_Ri" to K.DPAD_RIGHT,
        "S__N_Up" to K.DPAD_UP,
    )
    private val functionToken = Regex("S____F([1-9])|S___F(1[0-2])")
    private val numpadDigitToken = Regex("S__N__([0-9])")

    fun parse(token: String): NativeAction? {
        if (token == "S__Comp") return NativeAction(K.COMPOSE)
        if (token == "S__NDot") return NativeAction(K.NUM_DOT, Meta.NUM_LOCK)
        actions[token]?.let { return NativeAction(it) }
        functionToken.matchEntire(token)?.let { match ->
            val number = match.groupValues.drop(1).first { it.isNotEmpty() }.toInt()
            return NativeAction(K.F1 + number - 1)
        }
        numpadDigitToken.matchEntire(token)?.let { match ->
            return NativeAction(K.NUM0 + match.groupValues[1].toInt(), Meta.NUM_LOCK)
        }
        if (token.startsWith("S_S")) {
            val unshifted = "S__" + token.removePrefix("S_S")
            return parse(unshifted)?.let { it.copy(meta = it.meta or Meta.SHIFT) }
        }
        return null
    }

    fun controlCharacter(codePoint: Int): NativeAction? = when (codePoint) {
        0x08 -> NativeAction(K.BACKSPACE)
        0x09 -> NativeAction(K.TAB)
        0x0A, 0x0D -> NativeAction(K.ENTER)
        0x1B -> NativeAction(K.ESCAPE)
        else -> null
    }
}
