package com.movtery.zalithlauncher.feature.screenrec

import com.movtery.zalithlauncher.utils.path.PathManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingStore {
    const val EXTENSION = "mp4"

    @JvmStatic
    fun directory(): File {
        val dir = File(PathManager.DIR_GAME_HOME, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @JvmStatic
    fun newFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(directory(), "rec_$stamp.$EXTENSION")
    }

    @JvmStatic
    fun list(): List<File> {
        val files = directory().listFiles { file -> file.isFile && file.extension.equals(EXTENSION, true) }
        return files?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    @JvmStatic
    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
