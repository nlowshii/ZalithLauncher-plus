package com.movtery.zalithlauncher.ui.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.DialogGameLogBinding

class GameLogDialog(private val context: Context, private val content: String) {
    fun show() {
        val binding = DialogGameLogBinding.inflate(LayoutInflater.from(context))
        binding.logText.text = content

        val dialog = AlertDialog.Builder(context, R.style.CustomAlertDialogTheme)
            .setTitle(R.string.last_game_log_title)
            .setView(binding.root)
            .setNegativeButton(R.string.generic_close, null)
            .setPositiveButton(R.string.generic_copy, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("game_log", content))
            Toast.makeText(context, R.string.generic_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
