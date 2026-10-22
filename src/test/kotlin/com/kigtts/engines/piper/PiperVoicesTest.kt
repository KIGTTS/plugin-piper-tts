package com.kigtts.engines.piper

import com.kigtts.plugin.api.PluginStorage
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
class PiperVoicesTest {
    @get:Rule val files = TemporaryFolder()
    private val config = """{"audio":{"sample_rate":16000},"phoneme_type":"espeak","phoneme_id_map":{"_":[0],"^":[1],"$":[2],"a":[3]},"espeak":{"voice":"en-us"},"language":{"code":"en_US"},"speaker_id_map":{"first":0,"second":1}}"""
    private fun zip(vararg entries: Pair<String, String>): File = files.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }
    private fun archive() = zip("nested/model.onnx" to "model", "nested/model.onnx.json" to config)
    @Test fun standardPackImportSelectRenameAndDeletePreserveScopedData() {
        val storage = PluginStorage(files.newFolder())
        val voices = PiperVoices(storage)
        val first = voices.import(archive())
        assertTrue(first.name.startsWith("Piper 语音 1"))
        assertEquals(2, voices.list().size)
        assertEquals(first.id, voices.selected)
        val second = voices.list().first { it.id != first.id }
        voices.choose(second.id)
        assertEquals(second.id, PiperVoices(storage).selected)
        assertEquals(1, voices.resolve(second.id).second)
        assertEquals(16000, voices.resolve(first.id).first.sampleRate)
        voices.rename(first.id, "新语音包")
        assertTrue(voices.list().all { it.name.startsWith("新语音包") })
        voices.delete(second.id)
        assertTrue(voices.list().isEmpty())
        assertNull(PiperVoices(storage).selected)
    }
    @Test fun legacyManifestAndMetadataAreRetained() {
        val voices = PiperVoices(PluginStorage(files.newFolder()))
        val imported = voices.import(zip("manifest.json" to """{"files":{"model":"voice.onnx","config":"config.json"},"sample_rate":22050}""",
            "meta.json" to """{"name":"旧声音","remark":"旧备注"}""", "voice.onnx" to "model", "config.json" to config))
        assertTrue(imported.name.startsWith("旧声音"))
        assertEquals("旧备注", imported.description)
        assertEquals(22050, voices.resolve(imported.id).first.sampleRate)
    }
    @Test fun invalidArchiveCannotEscapeOrReplaceInstalledVoice() {
        val storage = PluginStorage(files.newFolder())
        val voices = PiperVoices(storage)
        val good = voices.import(archive())
        for (bad in listOf(zip("../outside" to "bad"), zip("voice.onnx" to "bad", "voice.onnx.json" to "{}"),
            zip("./voice.onnx" to "model", "voice.onnx" to "duplicate", "voice.onnx.json" to config))) {
            assertTrue(runCatching { voices.import(bad) }.isFailure)
            assertEquals(2, voices.list().size)
            assertEquals(good.id, voices.selected)
            assertTrue(storage.temporary.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun manifestModelPathCannotEscapeVoicePack() {
        val root = files.newFolder()
        File(root, "manifest.json").writeText("""{"files":{"model":"../outside.onnx","config":"config.json"}}""")
        assertTrue(runCatching { PiperPack.read(root) }.isFailure)
    }
    @Test fun actualLegacyVoicepackJsonPreservesPinOrderRemarksAndAvatarAndExportsBack() {
        val storage = PluginStorage(files.newFolder()); val voices = PiperVoices(storage)
        val imported = voices.import(zip("manifest.json" to """{"files":{"model":"tts\\voice.onnx","config":"tts/config.json","phonemizer":"tts/phones.dict"},"sample_rate":22050}""",
            "voicepack.json" to """{"name":"旧包","remark":"旧备注","avatar":"portrait.png","pinned":true,"order":42,"custom":"keep"}""",
            "portrait.png" to "preserved image", "preview.wav" to "preview", "tts/voice.onnx" to "model", "tts/config.json" to config.replace("\"phoneme_type\":\"espeak\",", ""), "tts/phones.dict" to "你 a"))
        val pack = voices.packs().single()
        assertTrue(pack.metadata.pinned); assertEquals(42L, pack.metadata.order); assertNotNull(pack.avatarPath)
        assertEquals("text", voices.resolve(imported.id).first.phonemeType)
        voices.update(pack.id, pack.metadata.copy(name = "修改名称", remark = "多行\n备注"))
        val exported = voices.export(pack.id)
        val restored = PiperVoices(PluginStorage(files.newFolder())).also { it.import(exported) }
        assertEquals("修改名称", restored.packs().single().metadata.name)
        assertEquals("多行\n备注", restored.packs().single().metadata.remark)
        java.util.zip.ZipFile(exported).use { zip ->
            assertNotNull(zip.getEntry("voicepack.json")); assertNotNull(zip.getEntry("preview.wav")); assertNotNull(zip.getEntry("portrait.png"))
            val json = org.json.JSONObject(zip.getInputStream(zip.getEntry("voicepack.json")).reader().readText())
            assertEquals("keep", json.getString("custom"))
        }
    }
    @Test fun standardExportCreatesLegacyRequiredManifestDictionaryAndExplicitPhonemeType() {
        val voices = PiperVoices(PluginStorage(files.newFolder())); val imported = voices.import(archive())
        val exported = voices.export(imported.packId!!)
        java.util.zip.ZipFile(exported).use { zip ->
            val manifest = org.json.JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
            assertNotNull(zip.getEntry(manifest.getJSONObject("files").getString("phonemizer")))
        }
        val restored = PiperVoices(PluginStorage(files.newFolder())).also { it.import(exported) }
        assertEquals("espeak", restored.resolve(null).first.phonemeType)
    }
    @Test fun avatarUpdateWritesARealImageInsidePackWithoutChangingModel() {
        val storage = PluginStorage(files.newFolder()); val voices = PiperVoices(storage); val voice = voices.import(archive())
        val model = voices.resolve(voice.id).first.model.readBytes()
        val image = java.io.File(storage.temporary, "source.png")
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        voices.avatar(voice.packId!!, image)
        val avatar = storage.file(voices.packs().single().avatarPath!!)
        assertEquals(android.graphics.Color.RED, android.graphics.BitmapFactory.decodeFile(avatar.path).getPixel(0, 0))
        assertArrayEquals(model, voices.resolve(voice.id).first.model.readBytes())
    }
    @Test fun updatingPackKeepsIdentityMetadataAndSelectionWhileInvalidUpdateRollsBack() {
        val storage = PluginStorage(files.newFolder()); val voices = PiperVoices(storage); val original = voices.import(archive())
        val pack = voices.packs().single(); voices.update(pack.id, pack.metadata.copy(name = "我的包", remark = "保留", pinned = true, order = 9))
        val avatar = java.io.File(voices.directory(pack.id), "avatar.png").apply { writeText("my avatar") }
        voices.import(zip("model.onnx" to "updated model", "model.onnx.json" to config), replacing = pack.id)
        assertEquals(original.id, voices.selected); assertEquals(pack.id, voices.packs().single().id)
        assertEquals("我的包", voices.packs().single().metadata.name); assertTrue(voices.packs().single().metadata.pinned)
        assertEquals("my avatar", avatar.readText()); assertEquals("updated model", voices.resolve(original.id).first.model.readText())
        assertTrue(runCatching { voices.import(zip("model.onnx" to "bad", "model.onnx.json" to "{}"), replacing = pack.id) }.isFailure)
        assertEquals("updated model", voices.resolve(original.id).first.model.readText())
    }
    @Test fun api3NameMigrationAndLegacyMetadataBackupSurviveReorderingAndUpdates() {
        val voices = PiperVoices(PluginStorage(files.newFolder())); val first = voices.import(archive()); val second = voices.import(archive())
        val dir = voices.directory(first.packId!!)
        java.io.File(dir, "voice-meta.json").writeText("""{"name":"API3 名称","remark":"API3 备注"}""")
        java.io.File(dir, "voicepack.json").writeText("invalid")
        java.io.File(dir, "voicepack.json.bak").writeText("""{"name":"旧名称","pinned":true,"order":99,"avatar":"avatar.png"}""")
        assertEquals("API3 名称", voices.packs().first { it.id == first.packId }.metadata.name)
        assertTrue(voices.packs().first { it.id == first.packId }.metadata.pinned)
        voices.reorder(listOf(second.packId!!, first.packId!!))
        assertEquals(0L, voices.packs().first { it.id == second.packId }.metadata.order)
    }
    @Test fun settingsArePerPackAndSurviveRenameReplacementExportAndRestart() {
        val storage = PluginStorage(files.newFolder()); val voices = PiperVoices(storage)
        val first = voices.import(archive()); val second = voices.import(archive())
        assertEquals("en-US", first.language)
        voices.parameters(first.packId!!, """{"noise_scale":0.3,"noise_w":1.2,"volume":0.7}""")
        fun values(id: String) = PiperVoices(storage).packs().first { it.id == id }.parameters.associate { it.id to it.value }
        assertEquals(.3f, values(first.packId!!).getValue("noise_scale"), .001f)
        assertEquals(.667f, values(second.packId!!).getValue("noise_scale"), .001f)
        voices.rename(first.id, "保留参数")
        voices.import(archive(), first.packId)
        assertEquals(.7f, values(first.packId!!).getValue("volume"), .001f)
        val restored = PiperVoices(PluginStorage(files.newFolder())).also { it.import(voices.export(first.packId!!)) }
        assertEquals(values(first.packId!!), restored.packs().single().parameters.associate { it.id to it.value })
        val pack = voices.resolve(first.id).first
        assertArrayEquals(floatArrayOf(.3f, .5f, 1.2f), PiperAudioSettings.scales(pack, 2f), .001f)
        for (bad in listOf("""{"volume":5}""", """{"noise_scale":0.9,"unknown":1}""")) {
            assertTrue(runCatching { voices.parameters(first.packId!!, bad) }.isFailure)
            assertEquals(.3f, values(first.packId!!).getValue("noise_scale"), .001f)
        }
    }
}
