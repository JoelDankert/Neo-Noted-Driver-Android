package com.sjdev.noteddriverandroid.core

object PhysicalKeys {
    private val scanNames = buildMap {
        put(1,"esc"); put(14,"backspace"); put(15,"tab"); put(28,"enter"); put(57,"space")
        for (s in 2..13) put(s, "sc%03X".format(s))
        for (s in 16..27) put(s, "sc%03X".format(s))
        for (s in 30..40) put(s, "sc%03X".format(s))
        for (s in 43..53) put(s, "sc%03X".format(s))
        put(55,"sc037"); put(74,"sc04A"); put(78,"sc04E")
        for (s in 71..73) put(s, "sc%03X".format(s))
        for (s in 75..83) put(s, "sc%03X".format(s))
        put(86,"sc056")
    }
    private val fallback = buildMap {
        put(K.ESCAPE,"esc"); put(K.BACKSPACE,"backspace"); put(K.TAB,"tab"); put(K.ENTER,"enter")
        put(K.SPACE,"space"); put(K.FORWARD_DEL,"del"); put(K.INSERT,"ins"); put(K.HOME,"home")
        put(K.END,"end"); put(K.PAGE_UP,"pgup"); put(K.PAGE_DOWN,"pgdn"); put(K.DPAD_UP,"up")
        put(K.DPAD_DOWN,"down"); put(K.DPAD_LEFT,"left"); put(K.DPAD_RIGHT,"right")
        for (i in 0..9) put(K.D0+i, if (i==0) "sc00B" else "sc%03X".format(i+1))
        val scans = intArrayOf(30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44)
        for (i in scans.indices) put(K.A+i, "sc%03X".format(scans[i]))
        put(K.GRAVE,"sc029"); put(K.MINUS,"sc00C"); put(K.EQUALS,"sc00D")
        put(K.LBRACKET,"sc01A"); put(K.RBRACKET,"sc01B"); put(K.SEMICOLON,"sc027")
        put(K.APOSTROPHE,"sc028"); put(K.COMMA,"sc033"); put(K.PERIOD,"sc034"); put(K.SLASH,"sc035")
        // Without an evdev scan code Android exposes no portable ISO-102nd-key identity.
        // Preserve this driver's historical DE-ISO convention: BACKSLASH is Mod4L; POUND is the # key.
        put(K.BACKSLASH,"sc056"); put(K.POUND,"sc02B")
        for (i in 0..11) put(K.F1+i, "F${i+1}")
        for (i in 0..9) put(K.NUM0+i, "sc%03X".format(intArrayOf(82,79,80,81,75,76,77,71,72,73)[i]))
        put(K.NUM_DIV,"sc135"); put(K.NUM_MUL,"sc037"); put(K.NUM_SUB,"sc04A"); put(K.NUM_ADD,"sc04E")
        put(K.NUM_DOT,"sc053"); put(K.NUM_ENTER,"numpadenter")
    }
    fun resolve(keyCode: Int, scanCode: Int): String? =
        if (scanCode > 0) scanNames[scanCode] ?: fallback[keyCode] else fallback[keyCode]
}
