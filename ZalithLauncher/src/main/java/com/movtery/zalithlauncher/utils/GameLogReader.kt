package com.movtery.zalithlauncher.utils

import com.movtery.zalithlauncher.utils.path.PathManager
import java.io.File
import java.io.RandomAccessFile

object GameLogReader {
    private const val MAX_BYTES = 200_000L

    @JvmStatic
    fun logFile() = File(PathManager.DIR_GAME_HOME, "latestlog.txt")

    @JvmStatic
    fun readTail(): String? {
        val file = logFile()
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { access ->
                val length = access.length()
                val start = maxOf(0L, length - MAX_BYTES)
                access.seek(start)
                val bytes = ByteArray((length - start).toInt())
                access.readFully(bytes)
                var text = String(bytes, Charsets.UTF_8)
                if (start > 0) {
                    val firstBreak = text.indexOf('\n')
                    if (firstBreak >= 0) text = text.substring(firstBreak + 1)
                }
                text.trimEnd()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun preview(text: String, lines: Int = 8): String {
        val all = text.lines()
        return all.takeLast(lines).joinToString("\n")
    }
}
