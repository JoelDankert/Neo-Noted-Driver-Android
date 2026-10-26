package com.sjdev.noteddriverandroid.core

import org.json.JSONObject

data class NotedRow(val letter: Boolean, val slots: List<String>)

class NotedData(val rows: Map<String, NotedRow>, val compose: Map<String, String>) {
    val composePrefixes: Set<String> = buildSet {
        for (key in compose.keys) for (end in 7 until key.length step 7) add(key.substring(0, end))
    }

    companion object {
        fun parse(text: String): NotedData {
            val root = JSONObject(text)
            require(root.getInt("format") == 1)
            val rowsObject = root.getJSONObject("rows")
            val rows = buildMap {
                for (key in rowsObject.keys()) {
                    val row = rowsObject.getJSONObject(key)
                    val slots = row.getJSONArray("slots")
                    put(key, NotedRow(row.getBoolean("letter"), List(8) { slots.getString(it) }))
                }
            }
            val composeObject = root.getJSONObject("compose")
            val compose = buildMap {
                for (key in composeObject.keys()) put(key, composeObject.getString(key))
            }
            require(rows.size == 91 && compose.size == 7622)
            return NotedData(rows, compose)
        }
    }
}
