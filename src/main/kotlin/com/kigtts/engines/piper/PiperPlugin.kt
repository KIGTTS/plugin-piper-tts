// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.kigtts.engines.tts.ResidentResource
import com.kigtts.engines.tts.speechSegments
import android.os.SystemClock
import android.util.Log

class PiperPlugin : KigPlugin {
    override val descriptor = PluginDescriptor("engine.piper", "Piper 语音引擎", "导入语音包，选择喜欢的朗读声音", version = "0.8.1", license = "GPL-3.0-only")
    private lateinit var environment: PluginEnvironment
    private lateinit var voices: PiperVoices
    private val access = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var modelFactory: (PluginEnvironment, PiperPack) -> PiperInferenceEngine = PiperModel::open
    private val models = ResidentResource<List<String>, PiperInferenceEngine>(scope, { it.close() })
    override suspend fun attach(environment: PluginEnvironment) { this.environment = environment; voices = PiperVoices(environment.storage) }
    override val tts = object : TtsEngine {
        override val info get() = TtsInfo(if (voices.list().isEmpty()) EngineAvailability.NEEDS_RESOURCES else EngineAvailability.READY,
            voices.list(), maxTextLength = 500, selectedVoiceId = voices.selected, importsVoicePacks = true, managesVoices = true, packs = voices.packs(), supportsControl = true)
        override suspend fun control(command: TtsControl) = access.withLock {
            when (command.operation) {
                TtsControlOperation.PREPARE -> {
                    val pack = voices.resolve(command.voiceId).first
                    models.use(pack.cacheKey(), { modelFactory(environment, pack) }) { }
                }
                TtsControlOperation.RELEASE -> models.release()
                TtsControlOperation.SET_RESIDENT -> models.setResident(command.resident)
                TtsControlOperation.ACTIVITY -> models.setActive(command.active)
            }
        }
        override suspend fun close() { access.withLock { models.close() }; scope.cancel() }
        override fun synthesize(request: TtsRequest) = flow {
            access.withLock {
                emit(SynthesisEvent.Started)
                if (request.text.isBlank()) { emit(SynthesisEvent.Finished()); return@withLock }
                require(request.text.length <= 500) { "Piper 单次文本最多 500 字符" }
                val (pack, speaker) = voices.resolve(request.voiceId)
                val started = SystemClock.elapsedRealtime()
                var first = true
                try { models.use(pack.cacheKey(), { modelFactory(environment, pack) }) { model ->
                    for (segment in speechSegments(request.text)) {
                        currentCoroutineContext().ensureActive()
                        if (segment.isBlank()) continue
                        val audio = model.synthesize(speaker, request.copy(text = segment, language = pack.language))
                        if (first) { Log.i("PiperPerf", "first audio ms=${SystemClock.elapsedRealtime() - started} chars=${request.text.length}"); first = false }
                        for (offset in audio.bytes.indices step 32_768) {
                            currentCoroutineContext().ensureActive()
                            emit(SynthesisEvent.Audio(PcmAudio(audio.bytes.copyOfRange(offset, minOf(offset + 32_768, audio.bytes.size)), audio.sampleRate)))
                        }
                    }
                } } catch (error: LinkageError) { throw EngineException(EngineError.INITIALIZATION, "Piper 本地运行库加载失败", error) }
                Log.i("PiperPerf", "finished ms=${SystemClock.elapsedRealtime() - started} chars=${request.text.length}")
                emit(SynthesisEvent.Finished())
            }
        }.flowOn(Dispatchers.IO)
    }
    override val voiceManager = object : VoiceManager {
        override suspend fun command(command: VoiceCommand): PluginOutcome = withContext(Dispatchers.IO) {
            access.withLock {
                if (command.operation in setOf(VoiceOperation.IMPORT, VoiceOperation.DELETE, VoiceOperation.REFRESH)) models.release()
                when (command.operation) {
                    VoiceOperation.IMPORT -> voices.import(environment.storage.file(requireNotNull(command.resourcePath)), command.packId)
                    VoiceOperation.SELECT -> voices.choose(requireNotNull(command.voiceId))
                    VoiceOperation.DELETE -> voices.delete(requireNotNull(command.voiceId))
                    VoiceOperation.RENAME -> voices.rename(requireNotNull(command.voiceId), requireNotNull(command.value))
                    VoiceOperation.REFRESH -> Unit
                    VoiceOperation.UPDATE_PARAMETERS -> voices.parameters(requireNotNull(command.packId), requireNotNull(command.value))
                    VoiceOperation.UPDATE_METADATA -> voices.update(requireNotNull(command.packId), requireNotNull(command.metadata))
                    VoiceOperation.REORDER -> voices.reorder(command.order)
                    VoiceOperation.SET_AVATAR -> voices.avatar(requireNotNull(command.packId), environment.storage.file(requireNotNull(command.resourcePath)))
                    VoiceOperation.EXPORT -> {
                        val file = voices.export(requireNotNull(command.packId))
                        return@withLock PluginOutcome.ExportResource("导出语音包", PluginResource(file.relativeTo(environment.storage.root).invariantSeparatorsPath, descriptor.id),
                            voices.packs().first { it.id == command.packId }.metadata.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100) + ".kigvpk", "application/zip", temporary = true)
                    }
                }
                PluginOutcome.RefreshUi
            }
        }
    }
    override val contributions = PluginContributions(drawerEntries = listOf(DrawerEntry("about", "Piper 插件说明", "about", PluginIcon.SPEAKER, settingsPage = true)))
    override fun page(pageId: String, context: PluginContext) = if (pageId == "about") PluginPage(pageId, "Piper", listOf(
        PageBlock.Text("Piper 独立引擎", true), PageBlock.Text("语音包通过语音合成服务统一导入、选择、重命名和删除。支持旧 .kigvpk / ZIP 与标准 Piper ONNX+JSON ZIP。"),
        PageBlock.Notice("发音词典、eSpeak 与 ONNX Runtime 仅在本插件；首版移植本地库支持 arm64-v8a。"))) else null
    override suspend fun execute(actionId: String, context: PluginContext) = PluginOutcome.Message("请通过语音合成服务管理 Piper")
}
