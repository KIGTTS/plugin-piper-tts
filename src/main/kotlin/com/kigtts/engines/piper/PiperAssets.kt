// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.piper

import com.kigtts.plugin.api.PluginEnvironment
import java.io.File
import java.util.zip.ZipFile

internal fun ensureEspeakData(environment: PluginEnvironment): File {
    val target = environment.storage.directory("resources/espeak-ng-data")
    if (File(target, "phondata").isFile) return target
    ZipFile(File(environment.assets, "espeak-ng-data.zip")).use { zip ->
        zip.entries().toList().forEach { entry ->
            val name = entry.name.removeSuffix("/")
            require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\\') && name.split('/').none { it == ".." })
            val file = File(target, name)
            if (entry.isDirectory) file.mkdirs() else {
                file.parentFile!!.mkdirs()
                zip.getInputStream(entry).use { source -> file.outputStream().use { source.copyTo(it) } }
            }
        }
    }
    require(File(target, "phondata").isFile) { "缺少 eSpeak 发音数据" }
    return target
}
