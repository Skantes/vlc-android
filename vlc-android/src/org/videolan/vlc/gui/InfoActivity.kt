package org.videolan.vlc.gui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.*
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.Extensions
import org.videolan.medialibrary.Tools
import org.videolan.medialibrary.media.Artist
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.medialibrary.media.MediaWrapper
import org.videolan.vlc.R
import org.videolan.vlc.VLCApplication
import org.videolan.vlc.databinding.InfoActivityBinding
import org.videolan.vlc.gui.helpers.AudioUtil
import org.videolan.vlc.gui.helpers.FloatingActionButtonBehavior
import org.videolan.vlc.gui.video.MediaInfoAdapter
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.util.*
import java.io.File
import java.lang.Runnable
import java.util.*

private const val TAG = "VLC/InfoActivity"
private const val TAG_FAB_VISIBILITY = "FAB"

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
class InfoActivity : AudioPlayerContainerActivity(), View.OnClickListener {

    private lateinit var item: MediaLibraryItem
    private lateinit var adapter: MediaInfoAdapter
    private lateinit var model : InfoModel

    internal lateinit var binding: InfoActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.info_activity)

        initAudioPlayerContainerActivity()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val item = if (savedInstanceState != null)
            savedInstanceState.getParcelable<Parcelable>(TAG_ITEM) as MediaLibraryItem?
        else
            intent.getParcelableExtra<Parcelable>(TAG_ITEM) as MediaLibraryItem
        if (item == null) {
            finish()
            return
        }
        this.item = item
        if (item.id == 0L) {
            val libraryItem = VLCApplication.mlInstance.getMedia((item as MediaWrapper).uri)
            if (libraryItem != null)
                this.item = libraryItem
        }
        binding.item = item
        val fabVisibility = savedInstanceState?.getInt(TAG_FAB_VISIBILITY) ?: -1

        model = getModel()

        binding.fab.setOnClickListener(this)
        if (item is MediaWrapper) {
            adapter = MediaInfoAdapter()
            binding.list.layoutManager = LinearLayoutManager(binding.root.context)
            binding.list.adapter = adapter
            if (model.sizeText.value === null) model.checkFile(item)
            if (model.mediaTracks.value === null) model.parseTracks(this, item)
        }
        model.hasSubs.observe(this, Observer { if (it) binding.infoSubtitles.visibility = View.VISIBLE })
        model.mediaTracks.observe(this, Observer { adapter.setTracks(it) })
        model.sizeText.observe(this, Observer { binding.sizeValueText = it })
        model.cover.observe(this, Observer {
            if (it != null) {
                binding.cover = BitmapDrawable(this@InfoActivity.resources, it)
                runOnMainThread(Runnable {
                    ViewCompat.setNestedScrollingEnabled(binding.container, true)
                    binding.appbar.setExpanded(true, true)
                    if (fabVisibility != -1) binding.fab.visibility = fabVisibility
                })
            } else noCoverFallback()
        })
        if (model.cover.value === null) model.getCover(item.artworkMrl, getScreenWidth())
        AppScope.launch { updateMeta() }
    }

    private fun updateMeta() {
        var length = 0L
        val tracks = item.tracks
        val nbTracks = tracks?.size ?: 0
        if (nbTracks > 0) for (media in tracks!!) length += media.length
        if (length > 0)
            binding.length = Tools.millisToText(length)

        when {
            item.itemType == MediaLibraryItem.TYPE_MEDIA -> {
                val media = item as MediaWrapper?
                binding.path = Uri.decode(media!!.uri.path)
                binding.progress = if (media.length == 0L) 0 else (100.toLong() * media.time / length).toInt()
                binding.sizeTitleText = getString(R.string.file_size)
            }
            item.itemType == MediaLibraryItem.TYPE_ARTIST -> {
                val albums = (item as Artist).albums
                val nbAlbums = albums?.size ?: 0
                binding.sizeTitleText = getString(R.string.albums)
                binding.sizeValueText = nbAlbums.toString()
                binding.extraTitleText = getString(R.string.tracks)
                binding.extraValueText = nbTracks.toString()
            }
            else -> {
                binding.sizeTitleText = getString(R.string.tracks)
                binding.sizeValueText = nbTracks.toString()
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        fragmentContainer = binding.container
        super.onPostCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(TAG_ITEM, item)
        outState.putInt(TAG_FAB_VISIBILITY, binding.fab.visibility)
    }

    private fun noCoverFallback() {
        binding.appbar.setExpanded(false)
        ViewCompat.setNestedScrollingEnabled(binding.list, false)
        val lp = binding.fab.layoutParams as CoordinatorLayout.LayoutParams
        lp.anchorId = binding.container.id
        lp.anchorGravity = Gravity.BOTTOM or Gravity.END
        lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.default_margin)
        lp.behavior = FloatingActionButtonBehavior(this@InfoActivity, null)
        binding.fab.layoutParams = lp
        binding.fab.show()
    }

    override fun onClick(v: View) {
        MediaUtils.playTracks(this, item, 0)
        finish()
    }

    override fun onPlayerStateChanged(bottomSheet: View, newState: Int) {
        val visibility = binding.fab.visibility
        if (visibility == View.VISIBLE && newState != BottomSheetBehavior.STATE_COLLAPSED && newState != BottomSheetBehavior.STATE_HIDDEN)
            binding.fab.hide()
        else if (visibility == View.INVISIBLE && (newState == BottomSheetBehavior.STATE_COLLAPSED || newState == BottomSheetBehavior.STATE_HIDDEN))
            binding.fab.show()
    }
}

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
class InfoModel : ViewModel(), CoroutineScope by MainScope() {

    internal val hasSubs = MutableLiveData<Boolean>()
    internal val mediaTracks = MutableLiveData<List<Media.Track>>()
    internal val sizeText = MutableLiveData<String>()
    internal val cover = MutableLiveData<Bitmap>()

    internal fun getCover(mrl: String?, width: Int) = launch {
        cover.value = mrl?.let { withContext(Dispatchers.IO) { AudioUtil.readCoverBitmap(Uri.decode(it), width) } }
    }

    internal fun parseTracks(context: Context, mw: MediaWrapper) = launch {
        val media = withContext(Dispatchers.IO) {
            val libVlc = VLCInstance[context]
            Media(libVlc, mw.uri).apply { parse() }
        }
        if (!isActive) return@launch
        var subs = false
        val trackCount = media.trackCount
        val tracks = LinkedList<Media.Track>()
        for (i in 0 until trackCount) {
            val track = media.getTrack(i)
            tracks.add(track)
            subs = subs or (track.type == Media.Track.Type.Text)
        }
        media.release()
        hasSubs.value = subs
        mediaTracks.value = tracks.toList()
    }

    internal fun checkFile(mw: MediaWrapper) = launch {
        val itemFile = withContext(Dispatchers.IO) { File(Uri.decode(mw.location.substring(5))) }

        if (!withContext(Dispatchers.IO) { itemFile.exists() } || !isActive) return@launch
        if (mw.type == MediaWrapper.TYPE_VIDEO) checkSubtitles(itemFile)
        sizeText.value = itemFile.length().readableFileSize()
    }

    private suspend fun checkSubtitles(itemFile: File) = withContext(Dispatchers.IO) {
        var extension: String
        var filename: String
        var videoName = Uri.decode(itemFile.name)
        val parentPath = Uri.decode(itemFile.parent)
        videoName = videoName.substring(0, videoName.lastIndexOf('.'))
        val subFolders = arrayOf("/Subtitles", "/subtitles", "/Subs", "/subs")
        var files: Array<String>? = itemFile.parentFile.list()
        var filesLength = files?.size ?: 0
        for (subFolderName in subFolders) {
            val subFolder = File(parentPath + subFolderName)
            if (!subFolder.exists()) continue
            val subFiles = subFolder.list()
            var subFilesLength = 0
            var newFiles = arrayOfNulls<String>(0)
            if (subFiles != null) {
                subFilesLength = subFiles.size
                newFiles = arrayOfNulls(filesLength + subFilesLength)
                System.arraycopy(subFiles, 0, newFiles, 0, subFilesLength)
            }
            files?.let { System.arraycopy(it, 0, newFiles, subFilesLength, filesLength) }
            files = newFiles.filterNotNull().toTypedArray()
            filesLength = files.size
        }
        if (files != null) for (i in 0 until filesLength) {
            filename = Uri.decode(files[i])
            val index = filename.lastIndexOf('.')
            if (index <= 0) continue
            extension = filename.substring(index)
            if (!Extensions.SUBTITLES.contains(extension)) continue
            if (!isActive) return@withContext
            if (filename.startsWith(videoName)) {
                hasSubs.postValue(true)
                return@withContext
            }
        }
    }

    override fun onCleared() {
        cancel()
    }
}
