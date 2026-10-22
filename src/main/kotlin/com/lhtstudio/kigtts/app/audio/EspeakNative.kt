// SPDX-License-Identifier: GPL-3.0-only
package com.lhtstudio.kigtts.app.audio

/** Only this plugin retains the old JNI symbol namespace; it never enters the host APK. */
internal object EspeakNative {
    init { System.loadLibrary("espeak_jni") }
    private external fun nativeInit(dataPath: String): Boolean
    private external fun nativePhonemize(text: String, voice: String): String
    private var initialized = false
    @Synchronized fun ensureInit(path: String): Boolean {
        if (!initialized) initialized = nativeInit(path)
        return initialized
    }
    fun phonemize(text: String, voice: String): String = nativePhonemize(text, voice)
}
