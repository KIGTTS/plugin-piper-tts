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
    override val descriptor = PluginDescriptor("engine.piper", "Piper 语音引擎", "导入语音包，选择喜欢的朗读声音", version = "0.8.5", license = "GPL-3.0-only")
    override val localization = PluginLocalization("zh-CN", listOf("zh-CN", "en", "ko", "ja"))
    private var strings = PluginStrings(emptyMap())
    private lateinit var environment: PluginEnvironment
    private lateinit var voices: PiperVoices
    private val access = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var modelFactory: (PluginEnvironment, PiperPack) -> PiperInferenceEngine = PiperModel::open
    private val models = ResidentResource<List<String>, PiperInferenceEngine>(scope, { it.close() })
    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment
        strings = PluginStrings.fromAssets(environment.assets)
        voices = PiperVoices(environment.storage) { key, arguments, fallback ->
            strings.text(key, environment.locale, arguments, fallback)
        }
    }

    private fun text(key: String, fallback: String) = strings.text(key, environment.locale, fallback = fallback)

    private fun openModel(pack: PiperPack): PiperInferenceEngine = try {
        modelFactory(environment, pack)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: LinkageError) {
        throw EngineException(EngineError.INITIALIZATION, text("engine.library", "无法加载 Piper 本地运行库"), error)
    } catch (error: Exception) {
        throw EngineException(EngineError.INITIALIZATION, text("engine.initialization", "无法加载 Piper 语音模型"), error)
    }

    private suspend fun synthesize(model: PiperInferenceEngine, speaker: Int, request: TtsRequest): PcmAudio = try {
        model.synthesize(speaker, request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: LinkageError) {
        throw EngineException(EngineError.INITIALIZATION, text("engine.library", "无法加载 Piper 本地运行库"), error)
    } catch (error: Exception) {
        throw EngineException(EngineError.INFERENCE, text("engine.inference", "Piper 无法合成这段文字"), error)
    }
    override val tts = object : TtsEngine {
        override val info get() = TtsInfo(if (voices.list().isEmpty()) EngineAvailability.NEEDS_RESOURCES else EngineAvailability.READY,
            voices.list(), maxTextLength = 500, selectedVoiceId = voices.selected, importsVoicePacks = true, managesVoices = true, packs = voices.packs(), supportsControl = true)
        override suspend fun control(command: TtsControl) = access.withLock {
            when (command.operation) {
                TtsControlOperation.PREPARE -> {
                    val pack = voices.resolve(command.voiceId).first
                    models.use(pack.cacheKey(), { openModel(pack) }) { }
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
                require(request.text.length <= 500) { text("engine.text_limit", "Piper 单次最多朗读 500 个字符") }
                val (pack, speaker) = voices.resolve(request.voiceId)
                val started = SystemClock.elapsedRealtime()
                var first = true
                models.use(pack.cacheKey(), { openModel(pack) }) { model ->
                    for (segment in speechSegments(request.text)) {
                        currentCoroutineContext().ensureActive()
                        if (segment.isBlank()) continue
                        val audio = synthesize(model, speaker, request.copy(text = segment, language = pack.language))
                        if (first) { Log.i("PiperPerf", "first audio ms=${SystemClock.elapsedRealtime() - started} chars=${request.text.length}"); first = false }
                        for (offset in audio.bytes.indices step 32_768) {
                            currentCoroutineContext().ensureActive()
                            emit(SynthesisEvent.Audio(PcmAudio(audio.bytes.copyOfRange(offset, minOf(offset + 32_768, audio.bytes.size)), audio.sampleRate)))
                        }
                    }
                }
                Log.i("PiperPerf", "finished ms=${SystemClock.elapsedRealtime() - started} chars=${request.text.length}")
                emit(SynthesisEvent.Finished())
            }
        }.flowOn(Dispatchers.IO)
    }
    override val voiceManager = object : VoiceManager {
        override suspend fun command(command: VoiceCommand): PluginOutcome = withContext(Dispatchers.IO) {
            try {
                runCommand(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = when (command.operation) {
                    VoiceOperation.IMPORT -> text("voice.import_failed", "无法导入 Piper 语音包，请检查模型和配置文件")
                    VoiceOperation.UPDATE_PARAMETERS -> text("voice.invalid_parameter", "语音参数无效或超出范围")
                    else -> text("voice.action_failed", "无法完成 Piper 语音包操作")
                }
                throw IllegalArgumentException(message, error)
            }
        }

        private suspend fun runCommand(command: VoiceCommand): PluginOutcome {
            return access.withLock {
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
                        return@withLock PluginOutcome.ExportResource(strings.text("voice.export", environment.locale, fallback = "导出语音包"), PluginResource(file.relativeTo(environment.storage.root).invariantSeparatorsPath, descriptor.id),
                            voices.packs().first { it.id == command.packId }.metadata.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100) + ".kigvpk", "application/zip", temporary = true)
                    }
                }
                PluginOutcome.RefreshUi
            }
        }
    }
    override val contributions get() = contributions(PluginContext("", ""))
    override fun contributions(context: PluginContext): PluginContributions {
        fun copy(key: String, fallback: String) = strings.text(key, context.locale, fallback = fallback)
        return PluginContributions(drawerEntries = listOf(DrawerEntry("about", copy("label.about", "Piper 插件说明"), "about", PluginIcon.SPEAKER, settingsPage = true)))
    }

    override fun page(pageId: String, context: PluginContext): PluginPage? {
        fun copy(key: String, fallback: String) = strings.text(key, context.locale, fallback = fallback)
        return if (pageId == "about") PluginPage(pageId, "Piper", listOf(
            PageBlock.Text(copy("page.title", "Piper 语音引擎"), true), PageBlock.Text(copy("page.body", "在语音合成设置中添加和选择声音，也可管理已有语音包。支持 .kigvpk，以及包含 Piper 模型和配置的 ZIP 文件。")),
            PageBlock.Notice(copy("page.requirements", "需要搭配语音合成服务使用。目前支持 64 位 ARM 设备。")))) else null
    }

    override suspend fun execute(actionId: String, context: PluginContext) =
        PluginOutcome.Message(strings.text("page.manage", context.locale, fallback = "请在语音合成设置中管理 Piper 声音"))
}
