package io.github.xrisium.makudon.data

import java.io.File

/** Installation preference, deliberately outside every library and restore generation. Caller uses IO. */
class LanguagePreference(private val file: File, private val disk: AtomicDisk = AtomicDisk()) {
    fun read(): String = if (!file.exists()) "" else disk.read(file, 128).toString(Charsets.UTF_8).let {
        if (it == "system") "" else it
    }

    fun write(tag: String) = disk.write(file, tag.ifEmpty { "system" }.toByteArray(Charsets.UTF_8))
}
