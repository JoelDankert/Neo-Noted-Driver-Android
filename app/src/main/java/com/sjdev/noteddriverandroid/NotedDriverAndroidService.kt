package com.sjdev.noteddriverandroid

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import kotlin.collections.HashMap
import kotlin.collections.set

/**
 * Minimal hardware-IME with proper key DOWN/UP and layer handling.
 *
 * Layers (by *hold* state):
 *   M1: base (no special holds)
 *   M2: SHIFT held  (real system modifier is sent)
 *   M3: CAPS held OR AE(ä-position) held  (internal layer; CAPS/AE are momentary)
 *   M4: GRAVE (< > | key) held  (internal layer; key is consumed, no output)
 *   M5: CAPS + SHIFT held (internal layer; SHIFT is suppressed so apps don't see it)
 *   M6: CAPS + GRAVE held (internal layer)
 *
 * Mapping source is JSON:
 *   - /sdcard/NotedDriver/noted_mappings.json   (preferred)
 *   - assets/noted_mappings.json                (fallback)
 *   - tiny built-in defaults as last resort
 *
 * JSON format per layer:
 * {
 *   "mappings": {
 *     "M1": {
 *       "KEYCODE_A": { "type":"KEYCODE", "key":"KEYCODE_B" },
 *       "KEYCODE_SLASH": { "type":"SEQUENCE", "steps":[
 *           { "key":"KEYCODE_7", "modifiers":["SHIFT"] }
 *       ] },
 *       "KEYCODE_APOSTROPHE": { "type":"TEXT", "text":"ä" }
 *     },
 *     "M2": { ... }, "M3": { ... }, "M4": { ... }, "M5": { ... }, "M6": { ... }
 *   }
 * }
 *
 * Types:
 *  - KEYCODE  : sends real KeyEvent DOWN/UP of target key; optional "modifiers": ["SHIFT","CTRL","ALT"]
 *  - TEXT     : commits literal text (use only when no reliable chord exists)
 *  - SEQUENCE : presses/releases the given chord(s) explicitly (mods + key) in order
 */
class NotedDriverAndroidService : InputMethodService() {

    companion object {
        private const val TAG = "NotedDriverSvc"

        // Layer ids
        private const val L_M1 = "M1"
        private const val L_M2 = "M2"
        private const val L_M3 = "M3"
        private const val L_M4 = "M4"
        private const val L_M5 = "M5"
        private const val L_M6 = "M6"

        // Triggers
        private const val KEY_CAPS  = KeyEvent.KEYCODE_CAPS_LOCK
        private const val KEY_AE    = KeyEvent.KEYCODE_APOSTROPHE // ä-position on many DE-ISO layouts
        private const val KEY_GRAVE = KeyEvent.KEYCODE_BACKSLASH      // often physical < > | key on Android

        // Real system modifiers
        private const val SHIFT_L = KeyEvent.KEYCODE_SHIFT_LEFT
        private const val SHIFT_R = KeyEvent.KEYCODE_SHIFT_RIGHT
        private const val CTRL_L  = KeyEvent.KEYCODE_CTRL_LEFT
        private const val CTRL_R  = KeyEvent.KEYCODE_CTRL_RIGHT
        private const val ALT_L   = KeyEvent.KEYCODE_ALT_LEFT
        private const val ALT_R   = KeyEvent.KEYCODE_ALT_RIGHT
    }

    // ---------- State ----------
    private val physical = mutableSetOf<Int>()       // physically pressed keys (codes)
    private val sentMods = mutableSetOf<Int>()       // modifiers for which we sent DOWN
    private val supprNoDown = mutableSetOf<Int>()    // modifiers suppressed (we won't send DOWN)
    private val supprSentUp = mutableSetOf<Int>()    // modifiers for which we forced an UP when suppressing
    private var metaMask: Int = 0                    // META_* flags currently active for forwarded keys

    private var capsHeld  = false                    // CAPS (momentary)
    private var aeHeld    = false                    // AE (ä-position) (momentary)
    private var graveHeld = false                    // GRAVE (momentary, our M4 switch)

    private val downTime = HashMap<Int, Long>()      // physical key -> downTime
    private val fwdMeta  = HashMap<Int, Int>()       // physical key -> meta at DOWN (forwarded)
    private data class Synth(val keyCode: Int, val metaAtDown: Int)
    private val synth    = HashMap<Int, Synth>()     // physical key -> mapped target + meta at DOWN

    // ---------- Mapping model ----------
    private sealed class Action {
        data class KeyCode(val keyCode: Int, val extraMeta: Int = 0) : Action()
        data class Text(val text: String) : Action()
        data class Sequence(val steps: List<Step>) : Action() {
            data class Step(val keyCode: Int, val mods: Int = 0)
        }
    }
    private val mappings: MutableMap<String, Map<Int, Action>> = mutableMapOf()

    override fun onCreate() {
        super.onCreate()
        loadMappingsOrDefaults()
    }

    override fun onCreateInputView(): View? = null // hardware-only IME

    // ---------- Key handling ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ic = currentInputConnection ?: return super.onKeyDown(keyCode, event)
        physical.add(keyCode)

        // Triggers (momentary layer switches)
        if (keyCode == KEY_CAPS) {
            val was = capsHeld
            capsHeld = true
            // If SHIFT is currently "sent", suppress it so apps won't see SHIFT in CAPS+SHIFT layer (M5)
            if (!was) {
                releaseShiftIfSent(ic)
            }
            return true // consume CAPS
        }
        if (keyCode == KEY_AE) {
            aeHeld = true
            return true // consume AE (ä-position)
        }
        if (keyCode == KEY_GRAVE) {
            graveHeld = true
            return true // consume GRAVE (< > |)
        }

        // System modifiers (we manage DOWN/UP explicitly)
        if (isModifier(keyCode)) {
            val isShift = (keyCode == SHIFT_L || keyCode == SHIFT_R)
            // While CAPS is held, we don't want apps to see SHIFT (M5 semantics)
            if (isShift && capsHeld) {
                if (sentMods.contains(keyCode)) {
                    val dt = downTime[keyCode] ?: SystemClock.uptimeMillis()
                    sendKey(ic, KeyEvent.ACTION_UP, keyCode, dt, metaMask)
                    sentMods.remove(keyCode)
                    metaMask = metaMask and clearMaskFor(keyCode)
                    supprSentUp.add(keyCode)
                    Log.d(TAG, "Suppressed SHIFT: synthetic UP for $keyCode (CAPS held → M5)")
                } else {
                    supprNoDown.add(keyCode)
                    Log.d(TAG, "Suppressed SHIFT: no DOWN for $keyCode (CAPS held → M5)")
                }
                return true
            }
            // Normal modifier path
            if (!sentMods.contains(keyCode) && !supprNoDown.contains(keyCode) && !supprSentUp.contains(keyCode)) {
                val dt = SystemClock.uptimeMillis()
                downTime[keyCode] = dt
                sendKey(ic, KeyEvent.ACTION_DOWN, keyCode, dt, metaMask)
                sentMods.add(keyCode)
                metaMask = metaMask or maskFor(keyCode)
                Log.d(TAG, "Modifier DOWN $keyCode meta=$metaMask")
            }
            return true
        }

        // Non-modifier key
        val layer = activeLayer()
        val action = lookup(layer, keyCode) ?: if (layer != L_M1) lookup(L_M1, keyCode) else null

        val dt = SystemClock.uptimeMillis()
        downTime[keyCode] = dt

        if (action != null) {
            when (action) {
                is Action.KeyCode -> {
                    val meta = metaMask or action.extraMeta
                    sendKey(ic, KeyEvent.ACTION_DOWN, action.keyCode, dt, meta)
                    synth[keyCode] = Synth(action.keyCode, meta)
                    Log.d(TAG, "Mapped DOWN phys=$keyCode -> tgt=${action.keyCode} meta=$meta (layer=$layer)")
                }
                is Action.Text -> {
                    // Only when a reliable chord does not exist
                    ic.commitText(action.text, 1)
                    Log.d(TAG, "Mapped TEXT phys=$keyCode -> '${action.text}' (layer=$layer)")
                }
                is Action.Sequence -> {
                    // Executes its own press/release; nothing left to mirror on keyUp
                    sendSequence(ic, action)
                    Log.d(TAG, "Executed SEQUENCE for phys=$keyCode (layer=$layer)")
                }
            }
            return true
        } else {
            // Forward physical key as-is; mirror meta at DOWN on UP
            sendKey(ic, KeyEvent.ACTION_DOWN, keyCode, dt, metaMask)
            fwdMeta[keyCode] = metaMask
            Log.d(TAG, "Forward DOWN $keyCode meta=$metaMask (layer=$layer)")
            return true
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val ic = currentInputConnection ?: return super.onKeyUp(keyCode, event)
        physical.remove(keyCode)

        // Release triggers
        if (keyCode == KEY_CAPS)  { capsHeld = false; Log.d(TAG, "CAPS released");  return true }
        if (keyCode == KEY_AE)    { aeHeld   = false; Log.d(TAG, "AE released");    return true }
        if (keyCode == KEY_GRAVE) { graveHeld = false; Log.d(TAG, "GRAVE released"); return true }

        // Modifiers
        if (isModifier(keyCode)) {
            if (supprNoDown.remove(keyCode)) return true
            if (supprSentUp.remove(keyCode)) return true
            if (sentMods.contains(keyCode)) {
                val dt = downTime[keyCode] ?: SystemClock.uptimeMillis()
                sendKey(ic, KeyEvent.ACTION_UP, keyCode, dt, metaMask)
                sentMods.remove(keyCode)
                metaMask = metaMask and clearMaskFor(keyCode)
                Log.d(TAG, "Modifier UP $keyCode meta=$metaMask")
            }
            return true
        }

        // Normal keys
        val dt = downTime.remove(keyCode) ?: SystemClock.uptimeMillis()
        val s = synth.remove(keyCode)
        if (s != null) {
            sendKey(ic, KeyEvent.ACTION_UP, s.keyCode, dt, s.metaAtDown)
            Log.d(TAG, "Mapped UP   phys=$keyCode -> tgt=${s.keyCode} meta=${s.metaAtDown}")
        } else {
            val metaAtDown = fwdMeta.remove(keyCode) ?: metaMask
            sendKey(ic, KeyEvent.ACTION_UP, keyCode, dt, metaAtDown)
            Log.d(TAG, "Forward UP  $keyCode meta=$metaAtDown")
        }
        return true
    }

    override fun onFinishInput() {
        super.onFinishInput()
        cleanup()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cleanup()
    }

    // ---------- Layer selection ----------
    private fun activeLayer(): String {
        val shiftHeld = physical.contains(SHIFT_L) || physical.contains(SHIFT_R)

        // Priority: combos first
        return when {
            (capsHeld && graveHeld) -> L_M6      // CAPS + GRAVE
            (capsHeld && shiftHeld) -> L_M5      // CAPS + SHIFT (SHIFT suppressed to app)
            graveHeld               -> L_M4      // GRAVE only
            shiftHeld               -> L_M2      // SHIFT only (real modifier)
            (capsHeld || aeHeld)    -> L_M3      // CAPS or AE → M3
            else                    -> L_M1
        }
    }

    // ---------- Utilities ----------
    private fun isModifier(code: Int): Boolean =
        code == SHIFT_L || code == SHIFT_R || code == CTRL_L || code == CTRL_R || code == ALT_L || code == ALT_R

    private fun maskFor(code: Int): Int = when (code) {
        SHIFT_L -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        SHIFT_R -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
        CTRL_L  -> KeyEvent.META_CTRL_ON  or KeyEvent.META_CTRL_LEFT_ON
        CTRL_R  -> KeyEvent.META_CTRL_ON  or KeyEvent.META_CTRL_RIGHT_ON
        ALT_L   -> KeyEvent.META_ALT_ON   or KeyEvent.META_ALT_LEFT_ON
        ALT_R   -> KeyEvent.META_ALT_ON   or KeyEvent.META_ALT_RIGHT_ON
        else    -> 0
    }

    private fun clearMaskFor(code: Int): Int = maskFor(code).inv()

    private fun sendKey(ic: InputConnection, action: Int, keyCode: Int, downTime: Long, meta: Int) {
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(downTime, now, action, keyCode, 0, meta))
    }

    /** Press/release a chorded sequence exactly like a human would. */
    private fun sendSequence(ic: InputConnection, seq: Action.Sequence) {
        for (s in seq.steps) {
            val dt = SystemClock.uptimeMillis()

            // Press requested modifiers (use left ctrl/shift and right alt)
            if (s.mods and KeyEvent.META_SHIFT_ON != 0) sendKey(ic, KeyEvent.ACTION_DOWN, SHIFT_L, dt, metaMask)
            if (s.mods and KeyEvent.META_CTRL_ON  != 0) sendKey(ic, KeyEvent.ACTION_DOWN, CTRL_L,  dt, metaMask)
            if (s.mods and KeyEvent.META_ALT_ON   != 0) sendKey(ic, KeyEvent.ACTION_DOWN, ALT_R,   dt, metaMask)

            val usedMeta = s.mods
            sendKey(ic, KeyEvent.ACTION_DOWN, s.keyCode, dt, usedMeta)
            sendKey(ic, KeyEvent.ACTION_UP,   s.keyCode, dt, usedMeta)

            // Release modifiers in reverse order
            if (s.mods and KeyEvent.META_ALT_ON   != 0) sendKey(ic, KeyEvent.ACTION_UP, ALT_R,   dt, metaMask)
            if (s.mods and KeyEvent.META_CTRL_ON  != 0) sendKey(ic, KeyEvent.ACTION_UP, CTRL_L,  dt, metaMask)
            if (s.mods and KeyEvent.META_SHIFT_ON != 0) sendKey(ic, KeyEvent.ACTION_UP, SHIFT_L, dt, metaMask)
        }
    }

    /** If SHIFT was already sent, release it so the app doesn't see SHIFT in CAPS layers (for M5). */
    private fun releaseShiftIfSent(ic: InputConnection) {
        val now = SystemClock.uptimeMillis()
        if (sentMods.contains(SHIFT_L)) {
            val dt = downTime[SHIFT_L] ?: now
            sendKey(ic, KeyEvent.ACTION_UP, SHIFT_L, dt, metaMask)
            sentMods.remove(SHIFT_L)
            supprSentUp.add(SHIFT_L)
        }
        if (sentMods.contains(SHIFT_R)) {
            val dt = downTime[SHIFT_R] ?: now
            sendKey(ic, KeyEvent.ACTION_UP, SHIFT_R, dt, metaMask)
            sentMods.remove(SHIFT_R)
            supprSentUp.add(SHIFT_R)
        }
        // Remove SHIFT bits from meta
        metaMask = metaMask and
                (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON).inv()
    }

    private fun cleanup() {
        val ic = currentInputConnection
        val now = SystemClock.uptimeMillis()
        if (ic != null) {
            for (m in sentMods.toList()) {
                val dt = downTime[m] ?: now
                sendKey(ic, KeyEvent.ACTION_UP, m, dt, metaMask)
            }
        }
        sentMods.clear()
        supprNoDown.clear()
        supprSentUp.clear()
        physical.clear()
        downTime.clear()
        fwdMeta.clear()
        synth.clear()
        metaMask = 0
        capsHeld = false
        aeHeld = false
        graveHeld = false
    }

    // ---------- Mappings ----------
    private fun lookup(layerId: String, srcKey: Int): Action? = mappings[layerId]?.get(srcKey)

    private fun loadMappingsOrDefaults() {
        val sd = File("/sdcard/NotedDriver/noted_mappings.json")
        val jsonText: String? = when {
            sd.exists() -> runCatching { sd.readText() }.getOrNull()
            else -> runCatching {
                val isr: InputStream = assets.open("noted_mappings.json")
                isr.bufferedReader().use { it.readText() }
            }.getOrNull()
        }

        if (jsonText != null) {
            try {
                val parsed = parseMappings(jsonText)
                mappings.clear()
                mappings.putAll(parsed)
                Log.i(TAG, "Loaded mappings from JSON")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse mappings JSON: ${e.message}")
            }
        }

        // Tiny defaults so the IME is usable even without a JSON file
        val m1 = hashMapOf<Int, Action>()
        val m2 = hashMapOf<Int, Action>()
        val m3 = hashMapOf<Int, Action>() // CAPS/AE layer — supply in JSON
        val m4 = hashMapOf<Int, Action>() // GRAVE layer — supply in JSON
        val m5 = hashMapOf<Int, Action>() // CAPS+SHIFT — supply in JSON
        val m6 = hashMapOf<Int, Action>() // CAPS+GRAVE — supply in JSON

        mappings.clear()
        mappings[L_M1] = m1
        mappings[L_M2] = m2
        mappings[L_M3] = m3
        mappings[L_M4] = m4
        mappings[L_M5] = m5
        mappings[L_M6] = m6
        Log.i(TAG, "Using built-in default mappings")
    }

    private fun parseMappings(text: String): Map<String, Map<Int, Action>> {
        val out = mutableMapOf<String, Map<Int, Action>>()
        val root = JSONObject(text)
        val mobj = root.optJSONObject("mappings") ?: JSONObject()

        val layers = mobj.keys()
        while (layers.hasNext()) {
            val lid = layers.next()
            val layerMap = mutableMapOf<Int, Action>()
            val obj = mobj.getJSONObject(lid)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val srcName = keys.next()
                val srcCode = keyNameToCode(srcName) ?: continue
                val entryAny = obj.get(srcName)
                if (entryAny !is JSONObject) continue

                when (entryAny.optString("type", "KEYCODE")) {
                    "KEYCODE" -> {
                        val tgtName = entryAny.optString("key", srcName)
                        val tgtCode = keyNameToCode(tgtName) ?: continue
                        val modsArr = entryAny.optJSONArray("modifiers")
                        var mods = 0
                        if (modsArr != null) {
                            var i = 0
                            while (i < modsArr.length()) {
                                mods = mods or modifierNameToMeta(modsArr.getString(i))
                                i++
                            }
                        }
                        layerMap[srcCode] = Action.KeyCode(tgtCode, mods)
                    }

                    "TEXT" -> {
                        layerMap[srcCode] = Action.Text(entryAny.optString("text", ""))
                    }

                    "SEQUENCE" -> {
                        val arr = entryAny.optJSONArray("steps") ?: JSONArray()
                        val steps = mutableListOf<Action.Sequence.Step>()
                        var i = 0
                        while (i < arr.length()) {
                            val sObj = arr.getJSONObject(i)
                            val keyName = sObj.optString("key")
                            val keyCode = keyNameToCode(keyName)
                            if (keyCode == null) { i++; continue }
                            val modsArr = sObj.optJSONArray("modifiers")
                            var mods = 0
                            if (modsArr != null) {
                                var j = 0
                                while (j < modsArr.length()) {
                                    mods = mods or modifierNameToMeta(modsArr.getString(j))
                                    j++
                                }
                            }
                            steps.add(Action.Sequence.Step(keyCode, mods))
                            i++
                        }
                        if (steps.isNotEmpty()) {
                            layerMap[srcCode] = Action.Sequence(steps)
                        }
                    }
                }
            }
            out[lid] = layerMap.toMap()
        }
        return out.toMap()
    }

    private fun keyNameToCode(name: String): Int? = try {
        val f = KeyEvent::class.java.getField(name)
        f.getInt(null)
    } catch (_: Exception) { null }

    private fun modifierNameToMeta(name: String): Int = when (name.uppercase()) {
        "SHIFT" -> KeyEvent.META_SHIFT_ON
        "CTRL", "CONTROL" -> KeyEvent.META_CTRL_ON
        "ALT" -> KeyEvent.META_ALT_ON
        "ALTGR" -> (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        else -> 0
    }
}
