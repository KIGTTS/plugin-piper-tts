package com.kigtts.engines.piper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kigtts.plugin.api.*
import com.kigtts.plugin.android.AndroidPluginEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PiperSynthesisTest {
    @get:Rule val files = TemporaryFolder()
    private fun fixture(): Pair<PiperPlugin, PluginStorage> = runBlocking {
        val storage = PluginStorage(files.newFolder())
        val archive = files.newFile()
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, text) in listOf("model.onnx" to "model", "model.onnx.json" to """{"phoneme_type":"text","phoneme_id_map":{"^":[1],"$":[2],"a":[3]},"speaker_id_map":{"one":0,"two":1}}""")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        PiperVoices(storage).import(archive)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val environment = object : AndroidPluginEnvironment {
            override val context = context
            override val storage = storage
            override val assets = files.newFolder()
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("network not used")
        }
        PiperPlugin().also { it.attach(environment) } to storage
    }
    private class Model : PiperInferenceEngine {
        val requests = mutableListOf<TtsRequest>()
        val speakers = mutableListOf<Int>()
        var closed = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun synthesize(speaker: Int, request: TtsRequest): PcmAudio {
            requests += request; speakers += speaker
            if (requests.size > 1) gate?.await()
            return PcmAudio(byteArrayOf(1, 0), 22050)
        }
        override fun close() { closed++ }
    }
    @Test fun modelAndPhonemizerFactoryReusedAcrossRequestsAndSpeakers() = runBlocking {
        val (plugin, _) = fixture(); val models = mutableListOf<Model>()
        plugin.modelFactory = { _, _ -> Model().also { models += it } }
        val engine = requireNotNull(plugin.tts)
        engine.control(TtsControl(TtsControlOperation.PREPARE))
        engine.synthesize(TtsRequest("hello")).toList()
        val second = engine.info.voices.last().id
        engine.synthesize(TtsRequest("again", voiceId = second)).toList()
        assertEquals(1, models.size); assertEquals(listOf(0, 1), models.single().speakers)
        engine.control(TtsControl(TtsControlOperation.RELEASE)); assertEquals(1, models.single().closed)
        engine.synthesize(TtsRequest("next")).toList(); assertEquals(2, models.size)
        plugin.close()
    }
    @Test fun firstAudioArrivesBeforeRemainingSegmentsAndCancellationReusesModel() = runBlocking {
        val (plugin, _) = fixture(); val model = Model().also { it.gate = CompletableDeferred() }; var loads = 0
        plugin.modelFactory = { _, _ -> loads++; model }
        val first = CompletableDeferred<Unit>()
        val task = launch { plugin.tts.synthesize(TtsRequest("first sentence. " + "a".repeat(200))).collect {
            if (it is SynthesisEvent.Audio) first.complete(Unit)
        } }
        withTimeout(5000) { first.await() }
        assertFalse(task.isCompleted); task.cancelAndJoin()
        model.gate?.complete(Unit)
        plugin.tts.synthesize(TtsRequest("again")).toList()
        assertEquals(1, loads); assertEquals(0, model.closed)
        plugin.close()
    }
    @Test fun voiceRefreshAndChangedModelFingerprintInvalidateCachedModel() = runBlocking {
        val (plugin, storage) = fixture(); val models = mutableListOf<Model>()
        plugin.modelFactory = { _, _ -> Model().also { models += it } }
        plugin.tts.synthesize(TtsRequest("a")).toList()
        plugin.voiceManager.command(VoiceCommand(VoiceOperation.REFRESH))
        assertEquals(1, models.first().closed)
        plugin.tts.synthesize(TtsRequest("a")).toList()
        val pack = PiperVoices(storage).resolve(null).first
        pack.model.appendText("updated")
        plugin.tts.synthesize(TtsRequest("a")).toList()
        assertEquals(3, models.size); assertEquals(1, models[1].closed)
        plugin.close()
    }
}
