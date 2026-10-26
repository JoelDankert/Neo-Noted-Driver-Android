package com.sjdev.noteddriverandroid.core

import org.json.JSONObject

sealed interface LegacyAction {
    data class Text(val value: String) : LegacyAction
    data class Key(val keyCode: Int, val meta: Int = 0) : LegacyAction
    data class Sequence(val steps: List<Key>) : LegacyAction
}

class LegacyOverrides private constructor(
    private val layers: Map<Int, Map<Int, LegacyAction>>,
) {
    operator fun get(layer: Int, keyCode: Int): LegacyAction? = layers[layer]?.get(keyCode)

    companion object {
        val EMPTY = LegacyOverrides(emptyMap())

        fun parse(text: String, keyCodeResolver: (String) -> Int?): LegacyOverrides {
            val root = JSONObject(text)
            val mappings = root.getJSONObject("mappings")
            val layers = buildMap {
                for (layer in 1..6) {
                    val layerObject = mappings.optJSONObject("M$layer") ?: continue
                    put(layer, buildMap {
                        for (sourceName in layerObject.keys()) {
                            val sourceCode = requireKeyCode(sourceName, keyCodeResolver)
                            put(sourceCode, parseAction(layerObject.getJSONObject(sourceName), keyCodeResolver))
                        }
                    })
                }
            }
            return LegacyOverrides(layers)
        }

        private fun parseAction(value: JSONObject, resolver: (String) -> Int?): LegacyAction = when (value.getString("type")) {
            "TEXT" -> LegacyAction.Text(value.getString("text"))
            "KEYCODE" -> LegacyAction.Key(
                requireKeyCode(value.getString("key"), resolver),
                parseModifiers(value.optJSONArray("modifiers")),
            )
            "SEQUENCE" -> {
                val values = value.getJSONArray("steps")
                LegacyAction.Sequence(List(values.length()) { index ->
                    val step = values.getJSONObject(index)
                    LegacyAction.Key(
                        requireKeyCode(step.getString("key"), resolver),
                        parseModifiers(step.optJSONArray("modifiers")),
                    )
                })
            }
            else -> throw IllegalArgumentException("Unsupported legacy mapping type: ${value.getString("type")}")
        }

        private fun parseModifiers(values: org.json.JSONArray?): Int {
            var meta = 0
            if (values == null) return meta
            for (index in 0 until values.length()) {
                meta = meta or when (val modifier = values.getString(index)) {
                    "SHIFT" -> Meta.SHIFT
                    "CTRL" -> Meta.CTRL
                    "ALT" -> Meta.ALT
                    "META" -> Meta.META
                    else -> throw IllegalArgumentException("Unsupported legacy modifier: $modifier")
                }
            }
            return meta
        }

        private fun requireKeyCode(name: String, resolver: (String) -> Int?): Int =
            requireNotNull(resolver(name)) { "Unknown legacy key code: $name" }
    }
}
