// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

import ai.onnxruntime.*
import android.os.SystemClock
import android.util.Log
import com.kigtts.plugin.api.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** One native session and phonemizer per active pack, entirely inside the provider process. */
internal interface PiperInferenceEngine : AutoCloseable {
    suspend fun synthesize(speaker: Int, request: TtsRequest): PcmAudio
}
internal class PiperModel private constructor(private val pack: PiperPack, private val session: OrtSession,
    private val toIds: (String) -> IntArray) : PiperInferenceEngine {
    private val runtime = OrtEnvironment.getEnvironment()
    private val inputName = session.inputNames.firstOrNull { it == "input" }
        ?: session.inputNames.first { it.contains("input") && !it.contains("length") }
    private val lengthName = session.inputNames.first { it.contains("length") || it.contains("len") }
    private val scaleName = session.inputNames.firstOrNull { it.contains("scale") }
    private val speakerName = session.inputNames.firstOrNull { it.contains("sid") }

    override suspend fun synthesize(speaker: Int, request: TtsRequest): PcmAudio {
        val ids = toIds(request.text).map { it.toLong() }.toLongArray()
        require(ids.size >= 3) { "文本无法转换为此语音包的音素" }
        currentCoroutineContext().ensureActive()
        val inputs = linkedMapOf<String, OnnxTensor>()
        try {
            inputs[inputName] = OnnxTensor.createTensor(runtime, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
            inputs[lengthName] = OnnxTensor.createTensor(runtime, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1))
            scaleName?.let { inputs[it] = OnnxTensor.createTensor(runtime,
                FloatBuffer.wrap(PiperAudioSettings.scales(pack, request.rate)), longArrayOf(3)) }
            speakerName?.let { inputs[it] = OnnxTensor.createTensor(runtime,
                LongBuffer.wrap(longArrayOf(speaker.toLong())), longArrayOf(1)) }
            session.run(inputs).use { result ->
                currentCoroutineContext().ensureActive()
                val samples = unwrap(result[0].value)
                require(samples.isNotEmpty()) { "Piper 未产生音频" }
                val pcm = ByteArray(samples.size * 2)
                val volume = PiperAudioSettings.parameters(pack).first { it.id == "volume" }.value
                samples.forEachIndexed { index, value ->
                    val sample = ((value * volume).coerceIn(-1f, 1f) * 32767).toInt()
                    pcm[index * 2] = sample.toByte(); pcm[index * 2 + 1] = (sample shr 8).toByte()
                }
                return PcmAudio(pcm, pack.sampleRate)
            }
        } finally { inputs.values.forEach { it.close() } }
    }
    override fun close() { session.close(); Log.i("PiperPerf", "model released") }
    companion object {
        fun open(environment: PluginEnvironment, pack: PiperPack): PiperModel {
            val start = SystemClock.elapsedRealtime()
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
                OrtEnvironment.getEnvironment().createSession(pack.model.path, options)
            }
            try {
                val converter = if (pack.phonemeType.contains("espeak")) {
                    EspeakPhonemizer(environment.assets, ensureEspeakData(environment), pack.espeakVoice, pack.ids, pack.phoneMap)::toIds
                } else PiperPhonemizer(pack.dictionary, pack.ids, pack.phoneMap)::toIds
                if (pack.language.startsWith("zh")) converter("你好")
                return PiperModel(pack, session, converter).also {
                    Log.i("PiperPerf", "model loaded ms=${SystemClock.elapsedRealtime() - start}")
                }
            } catch (error: Throwable) { session.close(); throw error }
        }
    }
}
internal fun PiperPack.cacheKey(): List<String> = listOf(model, config, dictionary, java.io.File(directory, "manifest.json"))
    .map { file -> file?.let { "${it.canonicalPath}:${it.length()}:${it.lastModified()}" }.orEmpty() } +
    listOf(phonemeType, espeakVoice, ids.hashCode().toString(), phoneMap.hashCode().toString(), sampleRate.toString())
private fun unwrap(value: Any?): FloatArray = when (value) {
    is FloatArray -> value
    is Array<*> -> value.firstOrNull()?.let(::unwrap) ?: FloatArray(0)
    else -> FloatArray(0)
}
