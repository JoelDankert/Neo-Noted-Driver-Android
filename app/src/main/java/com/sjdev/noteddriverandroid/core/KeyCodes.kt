package com.sjdev.noteddriverandroid.core

/** Android KeyEvent values, kept here so the state machine has no Android dependency. */
object K {
    const val UNKNOWN = 0
    const val BACK = 4
    const val D0 = 7
    const val D1 = 8
    const val D9 = 16
    const val POUND = 18
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val CLEAR = 28
    const val A = 29
    const val Z = 54
    const val COMMA = 55
    const val PERIOD = 56
    const val ALT_L = 57
    const val ALT_R = 58
    const val SHIFT_L = 59
    const val SHIFT_R = 60
    const val TAB = 61
    const val SPACE = 62
    const val ENTER = 66
    const val BACKSPACE = 67
    const val GRAVE = 68
    const val MINUS = 69
    const val EQUALS = 70
    const val LBRACKET = 71
    const val RBRACKET = 72
    const val BACKSLASH = 73
    const val SEMICOLON = 74
    const val APOSTROPHE = 75
    const val SLASH = 76
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ESCAPE = 111
    const val FORWARD_DEL = 112
    const val CTRL_L = 113
    const val CTRL_R = 114
    const val CAPS = 115
    const val SCROLL = 116
    const val META_L = 117
    const val META_R = 118
    const val HOME = 122
    const val END = 123
    const val INSERT = 124
    const val F1 = 131
    const val F12 = 142
    const val NUM_LOCK = 143
    const val NUM0 = 144
    const val NUM7 = 151
    const val NUM9 = 153
    const val NUM_DIV = 154
    const val NUM_MUL = 155
    const val NUM_SUB = 156
    const val NUM_ADD = 157
    const val NUM_DOT = 158
    const val NUM_ENTER = 160

    /** Internal sentinel; it is never sent to Android. */
    const val COMPOSE = -2
}

object Meta {
    const val SHIFT = 0x1
    const val ALT = 0x2
    const val CTRL = 0x1000
    const val META = 0x10000
    const val NUM_LOCK = 0x200000
    const val SHIFT_LEFT = 0x40
    const val SHIFT_RIGHT = 0x80
    const val ALT_LEFT = 0x10
    const val ALT_RIGHT = 0x20
    const val CTRL_LEFT = 0x2000
    const val CTRL_RIGHT = 0x4000
    const val META_LEFT = 0x20000
    const val META_RIGHT = 0x40000
    const val CAPS_LOCK = 0x100000
}
