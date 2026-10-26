package com.sjdev.noteddriverandroid.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class NotedEngineTest {
    private lateinit var data:NotedData
    private lateinit var engine:NotedEngine
    @Before fun setup(){ data=NotedData.parse(File("src/main/assets/noted_data.json").readText()); engine=NotedEngine(data) }
    private fun down(code:Int,scan:Int=0,repeat:Int=0,time:Long=10)=engine.down(Stroke(code,scan,time,time+1,repeat,1))
    private fun up(code:Int,scan:Int=0,time:Long=20)=engine.up(Stroke(code,scan,10,time,0,1))
    private fun text(e:List<Effect>)=e.filterIsInstance<Effect.Text>().joinToString(""){it.value}

    @Test fun generatedDataIsCompleteAndPinned(){
        assertEquals(91,data.rows.size); assertEquals(7622,data.compose.size); assertEquals(1644,data.composePrefixes.size)
        assertEquals("é",decode(data.compose.getValue("T__acutU000065")))
        val digest=MessageDigest.getInstance("SHA-256").digest(File("src/main/assets/noted_data.json").readBytes()).joinToString(""){"%02x".format(it)}
        assertEquals("3466e2902a99138de41fd9dabb075655f1d42012200656068e2af5f49981716b",digest)
    }
    @Test fun sixLayersAndShiftAreResolved(){
        assertEquals("z",text(down(K.A+16,16))); up(K.A+16,16)
        down(K.SHIFT_L,42); assertEquals("Z",text(down(K.A+16,16))); up(K.A+16,16); up(K.SHIFT_L,42)
        down(K.CAPS,58); assertEquals("…",text(down(K.A+16,16))); up(K.A+16,16); up(K.CAPS,58)
        down(K.BACKSLASH,86); assertTrue(down(K.A+16,16).first() is Effect.Key); up(K.A+16,16); up(K.BACKSLASH,86)
        down(K.CAPS,58); down(K.SHIFT_L,42); assertEquals("ζ",text(down(K.A+16,16))); up(K.A+16,16); up(K.SHIFT_L,42); up(K.CAPS,58)
        down(K.CAPS,58); down(K.BACKSLASH,86); assertEquals("ℤ",text(down(K.A+16,16)))
    }
    @Test fun deadKeysAndComposeWork(){
        assertEquals("",text(down(K.POUND,43))); up(K.POUND,43); assertEquals("é",text(down(K.A+4,33))); up(K.A+4,33)
        down(K.BACKSLASH,86); down(K.EQUALS,13); up(K.EQUALS,13); up(K.BACKSLASH,86); assertEquals("ü",text(down(K.A+4,18))); up(K.A+4,18)
        down(K.POUND,43); up(K.POUND,43); assertEquals("'",text(down(K.SPACE,57)))
        up(K.SPACE,57); down(K.POUND,43); up(K.POUND,43); assertEquals("\u0301",text(down(K.POUND,43))); up(K.POUND,43)
        down(K.CAPS,58); down(K.APOSTROPHE,40); up(K.APOSTROPHE,40); up(K.CAPS,58)
        assertEquals("",text(down(K.A+17,19))); up(K.A+17,19); assertEquals("æ",text(down(K.A+4,33)))
    }
    @Test fun composeConsumesNativeActionKeysBeforeDispatch(){
        fun compose() {
            down(K.CAPS,58); down(K.TAB,15); up(K.TAB,15); up(K.CAPS,58)
        }

        compose()
        assertTrue(down(K.NUM_ADD,78).isEmpty())
        assertTrue(up(K.NUM_ADD,78).isEmpty())
        assertEquals("±",text(down(K.NUM_SUB,74)))
        assertTrue(up(K.NUM_SUB,74).isEmpty())

        compose()
        assertEquals("♫",text(run { down(K.CAPS,58); val result=down(K.TAB,15); up(K.TAB,15); up(K.CAPS,58); result }))

        val nativeOutputData = NotedData(
            mapOf(
                "tab" to NotedRow(false,List(8) { "S__Comp" }),
                "sc010" to NotedRow(false,List(8) { "a" }),
            ),
            mapOf("S__CompU000061" to "S__NAdd"),
        )
        val nativeOutputEngine = NotedEngine(nativeOutputData)
        assertTrue(nativeOutputEngine.down(Stroke(K.TAB,15,1,1)).isEmpty())
        nativeOutputEngine.up(Stroke(K.TAB,15,1,2))
        val nativeOutput = nativeOutputEngine.down(Stroke(K.A+16,16,3,3)).filterIsInstance<Effect.Key>()
        assertEquals(listOf(true,false),nativeOutput.map { it.down })
        assertTrue(nativeOutput.all { it.keyCode == K.NUM_ADD })
    }
    @Test fun composeCancelBackspaceAndInvalidAreConsumed(){
        down(K.CAPS,58); down(K.APOSTROPHE,40); up(K.APOSTROPHE,40); up(K.CAPS,58)
        down(K.A,30); up(K.A,30); assertTrue(down(K.BACKSPACE,14).isEmpty()); up(K.BACKSPACE,14)
        assertTrue(down(K.ESCAPE,1).isEmpty()); up(K.ESCAPE,1)
    }
    @Test fun heldTargetAndRepeatTimingAreStable(){
        val first=down(K.A+16,16,time=123).single() as Effect.Text; assertEquals("z",first.value)
        down(K.SHIFT_L,42); assertEquals("z",text(down(K.A+16,16,repeat=2,time=123)))
        assertTrue(up(K.A+16,16).isEmpty())
    }
    @Test fun shortcutUsesBaseNotedKeyAndRightAltIsNotShortcut(){
        down(K.CTRL_L,29); val key=down(K.A+16,16).single() as Effect.Key
        assertEquals(K.Z,key.keyCode); assertEquals(Meta.CTRL,key.meta); assertEquals(10,key.downTime)
        val released=up(K.A+16,16).single() as Effect.Key; assertFalse(released.down); assertEquals(K.Z,released.keyCode)
        up(K.CTRL_L,29); down(K.ALT_R,100); assertNotEquals("z",text(down(K.A+16,16)))
    }
    @Test fun dualModifiersLocksAndSharedMetaAreRobust(){
        down(K.SHIFT_L,42); down(K.SHIFT_R,54); up(K.SHIFT_L,42); up(K.SHIFT_R,54)
        assertEquals("Z",text(down(K.A+16,16))); up(K.A+16,16)
        down(K.BACKSLASH,86); down(K.ALT_R,100); up(K.BACKSLASH,86); up(K.ALT_R,100)
        assertTrue(down(K.A+16,16).first() is Effect.Key)
        val fresh=NotedEngine(data); fresh.down(Stroke(K.CTRL_L,29,1,1)); fresh.down(Stroke(K.CTRL_R,97,1,1)); fresh.up(Stroke(K.CTRL_L,29,1,2))
        val k=fresh.down(Stroke(K.A+16,16,3,3)).single() as Effect.Key; assertEquals(Meta.CTRL,k.meta)
    }
    @Test fun focusResetReleasesNativeHeldKeysAndClearsCompose(){
        down(K.CTRL_L,29,time=55); down(K.A+16,16,time=56)
        val releases=engine.reset(99).filterIsInstance<Effect.Key>(); assertEquals(2,releases.size); assertTrue(releases.all{!it.down&&it.eventTime==99L})
        assertEquals("z",text(down(K.A+16,16)))
    }
    @Test fun scanCodesWinAndFallbackIsExplicit(){
        assertEquals("sc056",PhysicalKeys.resolve(K.POUND,86)); assertEquals("sc02B",PhysicalKeys.resolve(K.POUND,0))
        assertEquals("sc00C", PhysicalKeys.resolve(K.MINUS, 12))
        assertEquals("sc00D", PhysicalKeys.resolve(K.EQUALS, 13))
        assertEquals("sc01B", PhysicalKeys.resolve(K.RBRACKET, 27))
        assertEquals("sc056",PhysicalKeys.resolve(K.BACKSLASH,0)); assertNull(PhysicalKeys.resolve(999,0))
    }

    @Test fun everyShippedInternalTokenIsTypedAndNeverLiteralText() {
        val tokens = buildSet {
            data.rows.values.flatMapTo(this) { it.slots.filter { value -> value.startsWith("S_") } }
            data.compose.values.forEach { encoded ->
                Regex("S_[A-Za-z0-9_]+").findAll(encoded).forEach { add(it.value) }
            }
        }
        assertTrue(tokens.isNotEmpty())
        tokens.forEach { token ->
            assertNotNull("untyped shipped token $token", NotedActions.parse(token))
        }
        assertEquals(NativeAction(K.DPAD_LEFT, Meta.SHIFT), NotedActions.parse("S_SN_Le"))
        assertEquals(NativeAction(K.HOME, Meta.SHIFT), NotedActions.parse("S_SNHom"))
    }

    @Test fun numericNumpadActionsForceNumLockWithoutChangingNavigation() {
        assertEquals(0x200000,Meta.NUM_LOCK)
        val numLock = Meta.NUM_LOCK
        (0..9).forEach { digit ->
            assertEquals(NativeAction(K.NUM0 + digit, numLock), NotedActions.parse("S__N__$digit"))
        }
        assertEquals(NativeAction(K.NUM_DOT, numLock), NotedActions.parse("S__NDot"))
        assertEquals(NativeAction(K.NUM0, numLock or Meta.SHIFT), NotedActions.parse("S_SN__0"))
        assertEquals(NativeAction(K.INSERT), NotedActions.parse("S__NIns"))
        assertEquals(NativeAction(K.FORWARD_DEL), NotedActions.parse("S__NDel"))
        assertEquals(NativeAction(K.HOME, Meta.SHIFT), NotedActions.parse("S_SNHom"))
    }

    @Test fun heldNumericNumpadActionKeepsNumLockAcrossRepeatAndUp() {
        val numLock = Meta.NUM_LOCK
        down(K.CTRL_L,29)
        val press = down(K.NUM7,71,time=123).single() as Effect.Key
        assertTrue(press.down)
        assertEquals(K.NUM7,press.keyCode)
        assertEquals(numLock or Meta.CTRL,press.meta)

        val repeat = down(K.NUM7,71,repeat=2,time=150).single() as Effect.Key
        assertTrue(repeat.down)
        assertEquals(123L,repeat.downTime)
        assertEquals(2,repeat.repeatCount)
        assertEquals(numLock or Meta.CTRL,repeat.meta)

        val release = up(K.NUM7,71,time=180).single() as Effect.Key
        assertFalse(release.down)
        assertEquals(123L,release.downTime)
        assertEquals(numLock or Meta.CTRL,release.meta)
    }

    @Test fun printableControlCharactersUseNativeActions() {
        val controls = listOf("\b" to K.BACKSPACE, "\t" to K.TAB, "\n" to K.ENTER, "\u001b" to K.ESCAPE)
        controls.forEach { (value, keyCode) ->
            val custom = NotedData(
                mapOf("sc010" to NotedRow(false, List(8) { value })),
                emptyMap(),
            )
            val controlEngine = NotedEngine(custom)
            val effects = controlEngine.down(Stroke(K.A + 16, 16, 3, 4)).filterIsInstance<Effect.Key>()
            assertEquals(listOf(true, false), effects.map { it.down })
            assertTrue(effects.all { it.keyCode == keyCode })
        }
    }

    @Test fun heldNavigationKeepsTargetMetaAndTimingAcrossLayerChangesAndReset() {
        down(K.BACKSLASH, 86)
        val press = engine.down(Stroke(K.A + 16, 16, 123, 124, 0, 1, 0x20)).single() as Effect.Key
        assertEquals(K.PAGE_UP, press.keyCode)
        down(K.SHIFT_L, 42)
        val repeat = down(K.A + 16, 16, repeat = 3, time = 150).single() as Effect.Key
        assertEquals(press.keyCode, repeat.keyCode)
        assertEquals(press.downTime, repeat.downTime)
        assertEquals(151L, repeat.eventTime)
        assertEquals(press.meta, repeat.meta)
        assertEquals(0x20, repeat.flags)
        val release = engine.reset(200).filterIsInstance<Effect.Key>().single { it.keyCode == K.PAGE_UP }
        assertEquals(123L, release.downTime)
        assertEquals(200L, release.eventTime)
        assertEquals(0x20, release.flags)
    }

    @Test fun unsupportedShortcutPunctuationIsConsumedWithoutText() {
        down(K.CTRL_L, 29)
        val effects = down(K.LBRACKET, 26)
        assertTrue(effects.none { it is Effect.Text })
    }

    @Test fun all32ModifierStatesResolveCanonicalLayersIncludingSlotsSevenAndEight() {
        val normal = "12123535444466664444353512126666"
        val letter = "12213535444466664444353512216666"
        val slotSeven = "00000000010100000101000000000000"
        val slotEight = "00000000000001010000000000000101"
        for (state in 0 until 32) {
            val stateData = NotedData(
                mapOf(
                    "sc010" to NotedRow(false, (1..8).map(Int::toString)),
                    "sc011" to NotedRow(true, (1..8).map(Int::toString)),
                ),
                emptyMap(),
            )
            fun configured(): NotedEngine = NotedEngine(stateData).also { candidate ->
                if (state and 16 != 0) toggleMod4Lock(candidate)
                if (state and 2 != 0) toggleShiftLock(candidate)
                if (state and 8 != 0) candidate.down(Stroke(K.BACKSLASH, 86, 3, 3))
                if (state and 4 != 0) candidate.down(Stroke(K.CAPS, 58, 4, 4))
                if (state and 1 != 0) candidate.down(Stroke(K.SHIFT_L, 42, 5, 5))
            }
            fun expected(base: String): String = when {
                slotSeven[state] == '1' -> "7"
                slotEight[state] == '1' -> "8"
                else -> base[state].toString()
            }
            assertEquals("normal state $state", expected(normal), text(configured().down(Stroke(K.A + 16, 16, 6, 6))))
            assertEquals("letter state $state", expected(letter), text(configured().down(Stroke(K.A + 17, 17, 6, 6))))

            val actualEngine = NotedEngine(data)
            configureState(actualEngine, state)
            data.rows.forEach { (physical, row) ->
                val base = if (row.letter) letter else normal
                val layer = when {
                    slotSeven[state] == '1' && row.slots[6].isNotEmpty() -> 7
                    slotEight[state] == '1' && row.slots[7].isNotEmpty() -> 8
                    else -> base[state].digitToInt()
                }
                assertEquals("$physical state $state", row.slots[layer - 1], actualEngine.selectedToken(physical))
            }
        }
    }

    @Test fun mod3SuppressesPhysicalShiftButShiftedNavigationKeepsSelectionMeta() {
        val fresh = NotedEngine(data)
        assertTrue(fresh.down(Stroke(K.CAPS, 58, 1, 1)).isEmpty())
        assertTrue(fresh.down(Stroke(K.SHIFT_L, 42, 2, 2)).isEmpty())
        assertTrue(fresh.up(Stroke(K.SHIFT_L, 42, 2, 3)).isEmpty())

        val shiftFirst = NotedEngine(data)
        assertTrue((shiftFirst.down(Stroke(K.SHIFT_L, 42, 4, 4)).single() as Effect.Key).down)
        val syntheticUp = shiftFirst.down(Stroke(K.CAPS, 58, 5, 5)).single() as Effect.Key
        assertFalse(syntheticUp.down)
        assertEquals(K.SHIFT_L, syntheticUp.keyCode)
        assertTrue(shiftFirst.up(Stroke(K.SHIFT_L, 42, 4, 6)).isEmpty())

        val selected = NotedEngine(data)
        configureState(selected, 9) // held Mod4 + Shift selects slot 7 on keypad navigation rows
        val nav = selected.down(Stroke(K.NUM7, 71, 10, 11)).single() as Effect.Key
        assertEquals(K.HOME, nav.keyCode)
        assertEquals(Meta.SHIFT, nav.meta)
    }

    @Test fun rightMod3MatchesCapsLayer() {
        val caps = NotedEngine(data).also { it.down(Stroke(K.CAPS, 58, 1, 1)) }
        val right = NotedEngine(data).also { it.down(Stroke(K.APOSTROPHE, 40, 1, 1)) }
        assertEquals(text(caps.down(Stroke(K.A + 16, 16, 2, 2))), text(right.down(Stroke(K.A + 16, 16, 2, 2))))
    }

    @Test fun legacyOverridesValidateAndPreserveTextKeyAndSequenceOwnership() {
        val keyCodes = mapOf("KEYCODE_Q" to K.A + 16, "KEYCODE_Z" to K.Z, "KEYCODE_A" to K.A)
        val json = """{"mappings":{"M1":{
            "KEYCODE_Q":{"type":"KEYCODE","key":"KEYCODE_Z"},
            "KEYCODE_A":{"type":"SEQUENCE","steps":[{"key":"KEYCODE_Z","modifiers":["CTRL"]}]}
        },"M2":{"KEYCODE_Q":{"type":"TEXT","text":"custom"}}}}"""
        val overrides = LegacyOverrides.parse(json) { keyCodes[it] }
        val custom = NotedEngine(data, overrides)
        val keyDown = custom.down(Stroke(K.A + 16, 16, 10, 11)).single() as Effect.Key
        assertEquals(K.Z, keyDown.keyCode)
        assertEquals(K.Z, (custom.up(Stroke(K.A + 16, 16, 10, 20)).single() as Effect.Key).keyCode)
        val sequence = custom.down(Stroke(K.A, 30, 30, 31)).filterIsInstance<Effect.Key>()
        assertEquals(listOf(true, false), sequence.map { it.down })
        assertTrue(custom.up(Stroke(K.A, 30, 30, 32)).isEmpty())
        assertFails { LegacyOverrides.parse("""{"mappings":{"M1":{"NOPE":{"type":"TEXT","text":"x"}}}}""") { keyCodes[it] } }
        assertFails { LegacyOverrides.parse("""{"mappings":{"M1":{"KEYCODE_Q":{"type":"BROKEN"}}}}""") { keyCodes[it] } }
    }

    private fun toggleShiftLock(target: NotedEngine) {
        target.down(Stroke(K.SHIFT_L, 42, 1, 1))
        target.down(Stroke(K.SHIFT_R, 54, 1, 1))
        target.up(Stroke(K.SHIFT_L, 42, 1, 2))
        target.up(Stroke(K.SHIFT_R, 54, 1, 2))
    }

    private fun toggleMod4Lock(target: NotedEngine) {
        target.down(Stroke(K.BACKSLASH, 86, 1, 1))
        target.down(Stroke(K.ALT_R, 100, 1, 1))
        target.up(Stroke(K.BACKSLASH, 86, 1, 2))
        target.up(Stroke(K.ALT_R, 100, 1, 2))
    }

    private fun configureState(target: NotedEngine, state: Int) {
        if (state and 16 != 0) toggleMod4Lock(target)
        if (state and 2 != 0) toggleShiftLock(target)
        if (state and 8 != 0) target.down(Stroke(K.BACKSLASH, 86, 3, 3))
        if (state and 4 != 0) target.down(Stroke(K.CAPS, 58, 4, 4))
        if (state and 1 != 0) target.down(Stroke(K.SHIFT_L, 42, 5, 5))
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }
    @Test fun unicodeSupplementaryAndMultiOutputDecode(){
        assertEquals("😀x",decode("U01F600U000078"))
        // Compose, 1, e, r -> a three-codepoint official result.
        down(K.CAPS,58); down(K.APOSTROPHE,40); up(K.APOSTROPHE,40); up(K.CAPS,58)
        down(K.D1,2); up(K.D1,2); down(K.A+4,33); up(K.A+4,33)
        assertEquals("1ᵉʳ",text(down(K.A+17,38))); up(K.A+17,38)
        // Compose, layer-3 |, 0 -> supplementary mathematical bold digit zero.
        down(K.CAPS,58); down(K.APOSTROPHE,40); up(K.APOSTROPHE,40)
        down(K.A+2,46); up(K.A+2,46); up(K.CAPS,58)
        assertEquals("𝟘",text(down(K.D0,11)))
    }
    private fun decode(encoded:String):String { val b=StringBuilder(); for(i in encoded.indices step 7)b.appendCodePoint(encoded.substring(i+1,i+7).toInt(16)); return b.toString() }
}
