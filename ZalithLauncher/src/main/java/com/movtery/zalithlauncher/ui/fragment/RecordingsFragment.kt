package com.movtery.zalithlauncher.ui.fragment

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.FragmentRecordingsBinding
import com.movtery.zalithlauncher.databinding.ItemRecordingBinding
import com.movtery.zalithlauncher.feature.screenrec.RecordingStore
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.utils.file.FileTools
import java.io.File
import java.util.Date

class RecordingsFragment : FragmentWithAnim(R.layout.fragment_recordings) {
    companion object {
        const val TAG: String = "RecordingsFragment"
    }

    private lateinit var binding: FragmentRecordingsBinding
    private val items = ArrayList<File>()
    private val adapter = RecordingsAdapter()
    private val thumbnails = HashMap<String, Bitmap?>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentRecordingsBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recordingsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@RecordingsFragment.adapter
            layoutAnimation = LayoutAnimationController(AnimationUtils.loadAnimation(requireContext(), R.anim.fade_downwards), 0.2f)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        TaskExecutors.getDefault().execute {
            val files = RecordingStore.list()
            TaskExecutors.runInUIThread {
                if (!isAdded) return@runInUIThread
                items.clear()
                items.addAll(files)
                adapter.notifyDataSetChanged()
                binding.emptyLayout.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                binding.recordingsRecycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                binding.recordingsRecycler.scheduleLayoutAnimation()
            }
        }
    }

    private fun play(context: Context, file: File) {
        val uri = DocumentsContract.buildDocumentUri(context.getString(R.string.storageProviderAuthorities), file.absolutePath)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.recordings_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(file: File) {
        TipDialog.Builder(requireActivity())
            .setTitle(R.string.recordings_delete_title)
            .setMessage(getString(R.string.recordings_delete_message, file.name))
            .setConfirmClickListener {
                file.delete()
                thumbnails.remove(file.absolutePath)
                refresh()
            }
            .showDialog()
    }

    private fun loadThumbnail(file: File, target: ItemRecordingBinding) {
        val key = file.absolutePath
        target.thumbnail.tag = key
        if (thumbnails.containsKey(key)) {
            target.thumbnail.setImageBitmap(thumbnails[key])
            return
        }
        target.thumbnail.setImageDrawable(null)
        TaskExecutors.getDefault().execute {
            val bitmap = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.getFrameAtTime(500_000)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
            TaskExecutors.runInUIThread {
                thumbnails[key] = bitmap
                if (target.thumbnail.tag == key) target.thumbnail.setImageBitmap(bitmap)
            }
        }
    }

    private fun describe(file: File): String {
        val duration = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
        val date = DateFormat.format("yyyy-MM-dd HH:mm", Date(file.lastModified()))
        return "${RecordingStore.formatDuration(duration)}  |  ${FileTools.formatFileSize(file.length())}  |  $date"
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.recordingsRecycler, Animations.BounceInDown))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.recordingsRecycler, Animations.FadeOutUp))
    }

    private inner class RecordingsAdapter : RecyclerView.Adapter<RecordingsAdapter.Holder>() {
        inner class Holder(val item: ItemRecordingBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = items[position]
            val binding = holder.item
            binding.recordingName.text = file.nameWithoutExtension
            binding.recordingInfo.text = FileTools.formatFileSize(file.length())
            loadThumbnail(file, binding)
            TaskExecutors.getDefault().execute {
                val info = describe(file)
                TaskExecutors.runInUIThread {
                    if (holder.bindingAdapterPosition == position) binding.recordingInfo.text = info
                }
            }
            binding.root.setOnClickListener { play(it.context, file) }
            binding.playButton.setOnClickListener { play(it.context, file) }
            binding.shareButton.setOnClickListener { FileTools.shareFile(it.context, file) }
            binding.deleteButton.setOnClickListener { confirmDelete(file) }
        }
    }
}
