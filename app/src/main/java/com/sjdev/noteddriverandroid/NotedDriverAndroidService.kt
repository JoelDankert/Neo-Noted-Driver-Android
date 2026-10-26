package com.sjdev.noteddriverandroid

import android.inputmethodservice.InputMethodService
import android.os.Environment
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.sjdev.noteddriverandroid.core.Effect
import com.sjdev.noteddriverandroid.core.NotedData
import com.sjdev.noteddriverandroid.core.NotedEngine
import com.sjdev.noteddriverandroid.core.Stroke
import com.sjdev.noteddriverandroid.core.LegacyOverrides
import java.io.File

/** Hardware-only adapter. Layout, compose, and held-key behavior live in the testable core. */
class NotedDriverAndroidService : InputMethodService() {
    private lateinit var engine: NotedEngine
    private var connection: InputConnection? = null

    override fun onCreate() {
        super.onCreate()
        val data = assets.open("noted_data.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        engine = NotedEngine(NotedData.parse(data), loadLegacyOverrides())
    }

    override fun onCreateInputView(): View? = null

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        releaseAndReset(connection)
        super.onStartInput(attribute, restarting)
        connection = currentInputConnection
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val target = currentInputConnection ?: return super.onKeyDown(keyCode, event)
        connection = target
        apply(target, engine.down(event.toStroke()))
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val target = currentInputConnection ?: connection ?: return super.onKeyUp(keyCode, event)
        apply(target, engine.up(event.toStroke()))
        return true
    }

    override fun onFinishInput() {
        releaseAndReset(connection ?: currentInputConnection)
        connection = null
        super.onFinishInput()
    }

    override fun onDestroy() {
        releaseAndReset(connection ?: currentInputConnection)
        connection = null
        super.onDestroy()
    }

    private fun releaseAndReset(target: InputConnection?) {
        val effects = engine.reset(SystemClock.uptimeMillis())
        if (target != null) apply(target, effects)
    }

    private fun apply(target: InputConnection, effects: List<Effect>) {
        for (effect in effects) when (effect) {
            is Effect.Text -> target.commitText(effect.value, 1)
            is Effect.Key -> {
                val now = SystemClock.uptimeMillis()
                val downTime = effect.downTime.takeIf { it > 0 } ?: now
                val eventTime = effect.eventTime.takeIf { it > 0 } ?: now
                target.sendKeyEvent(KeyEvent(downTime, eventTime,
                    if (effect.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                    effect.keyCode, effect.repeatCount, effect.meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    effect.flags, InputDevice.SOURCE_KEYBOARD))
            }
        }
    }

    private fun KeyEvent.toStroke() = Stroke(keyCode, scanCode, downTime, eventTime, repeatCount, deviceId, flags)

    private fun loadLegacyOverrides(): LegacyOverrides {
        @Suppress("DEPRECATION")
        val file = File(Environment.getExternalStorageDirectory(), "NotedDriver/noted_mappings.json")
        val text = runCatching { file.takeIf { it.isFile && it.canRead() }?.readText() }.getOrNull()
            ?: return LegacyOverrides.EMPTY
        return runCatching {
            LegacyOverrides.parse(text) { name ->
                KeyEvent.keyCodeFromString(name).takeUnless { it == KeyEvent.KEYCODE_UNKNOWN }
            }
        }.getOrDefault(LegacyOverrides.EMPTY)
    }
}
