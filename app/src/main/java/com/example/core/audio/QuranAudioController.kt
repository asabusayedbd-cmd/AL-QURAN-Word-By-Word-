package com.example.core.audio

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.core.database.NoorDatabase
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AudioPlaybackState(
    val isVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val surahId: Int = 1,
    val surahName: String = "Al-Fatihah",
    val ayahNumber: Int = 1,
    val maxAyahInSurah: Int = 7,
    val activeWordPosition: Int? = null,
    val reciterId: Int = 7,
    val reciterName: String = "Mishary Rashid Alafasy",
    val currentIteration: Int = 1,
    val hifzConfig: HifzLoopConfig = HifzLoopConfig(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null
)

class QuranAudioController(
    private val context: Context,
    private val database: NoorDatabase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val quranDao = database.quranDao()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var wordPollingJob: Job? = null
    private var pendingPauseJob: Job? = null
    private var currentSegments: List<WordAudioSegment> = emptyList()

    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()

    private var lastLoadedAyahId: Int = -1

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying, isBuffering = false) }
            if (isPlaying) {
                startWordPositionPolling()
            } else {
                wordPollingJob?.cancel()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { it.copy(isBuffering = true) }
                Player.STATE_READY -> _state.update {
                    it.copy(
                        isBuffering = false,
                        durationMs = mediaController?.duration?.coerceAtLeast(0L) ?: 0L,
                        errorMessage = null
                    )
                }
                Player.STATE_ENDED -> handleAyahEnded()
                Player.STATE_IDLE -> _state.update { it.copy(isBuffering = false) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("QuranAudio", "Playback error", error)
            _state.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = error.localizedMessage ?: "Audio stream unavailable offline"
                )
            }
        }
    }

    private fun ensureControllerConnected(onConnected: (MediaController) -> Unit) {
        val existing = mediaController
        if (existing != null) {
            onConnected(existing)
            return
        }
        val future = controllerFuture ?: run {
            val token = SessionToken(context, ComponentName(context, QuranPlaybackService::class.java))
            MediaController.Builder(context, token).buildAsync().also { controllerFuture = it }
        }
        future.addListener({
            try {
                val controller = future.get()
                if (mediaController !== controller) {
                    mediaController = controller
                    controller.addListener(playerListener)
                }
                onConnected(controller)
            } catch (e: Exception) {
                Log.e("QuranAudio", "Failed connecting MediaController", e)
                _state.update { it.copy(errorMessage = "Audio service unavailable") }
            }
        }, MoreExecutors.directExecutor())
    }

    fun playAyah(
        surahId: Int,
        ayahNumber: Int,
        surahName: String,
        maxAyahInSurah: Int,
        reciterId: Int,
        reciterName: String,
        iteration: Int = 1
    ) {
        pendingPauseJob?.cancel()
        scope.launch {
            val verseKey = "$surahId:$ayahNumber"
            val ayah = quranDao.getAyahByKey(verseKey)
            val reciterSegment = if (ayah != null) quranDao.getAudioSegment(reciterId, ayah.id) else null
            val timingSegment = reciterSegment ?: if (ayah != null) quranDao.getAudioSegment(7, ayah.id) else null
            currentSegments = timingSegment?.wordTimestampSegmentsJson?.let { HifzLoopEngine.parseWordSegments(it) }
                ?: emptyList()
            lastLoadedAyahId = ayah?.id ?: -1

            val reciter = quranDao.getReciter(reciterId)
            val url = reciterSegment?.audioUrl?.takeIf { it.isNotBlank() }
                ?: buildReciterAudioUrl(reciter?.baseUrl, reciterId, surahId, ayahNumber)
            _state.update {
                it.copy(
                    isVisible = true,
                    isBuffering = true,
                    surahId = surahId,
                    surahName = surahName,
                    ayahNumber = ayahNumber,
                    maxAyahInSurah = maxAyahInSurah,
                    reciterId = reciterId,
                    reciterName = reciter?.name ?: reciterName,
                    currentIteration = iteration,
                    activeWordPosition = null,
                    errorMessage = null
                )
            }

            ensureControllerConnected { controller ->
                val metadata = MediaMetadata.Builder()
                    .setTitle("$surahName ($verseKey)")
                    .setArtist(reciter?.name ?: reciterName)
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(metadata)
                    .build()
                controller.playbackParameters = PlaybackParameters(_state.value.hifzConfig.playbackSpeed)
                controller.setMediaItem(mediaItem)
                controller.prepare()
                controller.play()
            }
        }
    }

    fun playSingleWord(
        wordAudioUrl: String?,
        fallbackSurahId: Int,
        fallbackAyah: Int,
        wordPosition: Int,
        speed: Float = 1.0f
    ) {
        val url = wordAudioUrl ?: run {
            val s = fallbackSurahId.toString().padStart(3, '0')
            val a = fallbackAyah.toString().padStart(3, '0')
            val p = wordPosition.toString().padStart(3, '0')
            "https://audio.qurancdn.com/wbw/${s}_${a}_${p}.mp3"
        }
        ensureControllerConnected { controller ->
            lastLoadedAyahId = -1
            currentSegments = emptyList()
            val item = MediaItem.fromUri(url)
            controller.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 1.5f))
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
        }
    }

    fun togglePlayPause() {
        val controller = mediaController
        if (controller == null) {
            val s = _state.value
            playAyah(s.surahId, s.ayahNumber, s.surahName, s.maxAyahInSurah, s.reciterId, s.reciterName)
            return
        }
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun skipToNextAyah() {
        val s = _state.value
        val next = (s.ayahNumber + 1).coerceAtMost(s.maxAyahInSurah)
        playAyah(s.surahId, next, s.surahName, s.maxAyahInSurah, s.reciterId, s.reciterName)
    }

    fun skipToPreviousAyah() {
        val s = _state.value
        val prev = (s.ayahNumber - 1).coerceAtLeast(1)
        playAyah(s.surahId, prev, s.surahName, s.maxAyahInSurah, s.reciterId, s.reciterName)
    }

    fun stopAndDismiss() {
        pendingPauseJob?.cancel()
        wordPollingJob?.cancel()
        mediaController?.stop()
        _state.update { it.copy(isVisible = false, isPlaying = false, activeWordPosition = null) }
    }

    fun updateHifzConfig(transform: (HifzLoopConfig) -> HifzLoopConfig) {
        _state.update { current ->
            val updated = transform(current.hifzConfig)
            mediaController?.playbackParameters = PlaybackParameters(updated.playbackSpeed)
            current.copy(hifzConfig = updated)
        }
    }

    fun updateReciter(reciterId: Int, reciterName: String) {
        val current = _state.value
        _state.update { it.copy(reciterId = reciterId, reciterName = reciterName) }
        if (current.isVisible) {
            playAyah(
                current.surahId,
                current.ayahNumber,
                current.surahName,
                current.maxAyahInSurah,
                reciterId,
                reciterName
            )
        }
    }

    private fun handleAyahEnded() {
        wordPollingJob?.cancel()
        _state.update { it.copy(isPlaying = false, activeWordPosition = null) }
        if (currentSegments.isEmpty() && lastLoadedAyahId == -1) return

        val s = _state.value
        when (
            val step = HifzLoopEngine.nextStep(
                currentAyah = s.ayahNumber,
                currentIteration = s.currentIteration,
                config = s.hifzConfig,
                maxAyahInSurah = s.maxAyahInSurah
            )
        ) {
            is LoopAdvanceResult.PlayAyah -> {
                pendingPauseJob = scope.launch {
                    if (step.delayMsBeforePlay > 0L) delay(step.delayMsBeforePlay)
                    playAyah(
                        surahId = s.surahId,
                        ayahNumber = step.ayahNumber,
                        surahName = s.surahName,
                        maxAyahInSurah = s.maxAyahInSurah,
                        reciterId = s.reciterId,
                        reciterName = s.reciterName,
                        iteration = step.iterationOfAyah
                    )
                }
            }
            LoopAdvanceResult.Completed -> {
                _state.update { it.copy(isPlaying = false, activeWordPosition = null, currentIteration = 1) }
            }
        }
    }

    private fun startWordPositionPolling() {
        wordPollingJob?.cancel()
        wordPollingJob = scope.launch {
            while (isActive) {
                val controller = mediaController
                if (controller != null && controller.isPlaying) {
                    val pos = controller.currentPosition
                    val duration = controller.duration.coerceAtLeast(0L)
                    val activeWord = HifzLoopEngine.findActiveWordPosition(currentSegments, pos)
                    _state.update {
                        it.copy(
                            positionMs = pos,
                            durationMs = duration,
                            activeWordPosition = activeWord
                        )
                    }
                }
                delay(90L)
            }
        }
    }

    private fun buildReciterAudioUrl(baseUrl: String?, reciterId: Int, surahId: Int, ayahNumber: Int): String {
        val resolvedBase = baseUrl?.takeIf { it.isNotBlank() } ?: when (reciterId) {
            1 -> "https://everyayah.com/data/Husary_128kbps/"
            9 -> "https://everyayah.com/data/Minshawy_Murattal_128kbps/"
            else -> "https://everyayah.com/data/Alafasy_128kbps/"
        }
        val normalizedBase = if (resolvedBase.endsWith("/")) resolvedBase else "$resolvedBase/"
        val s = surahId.toString().padStart(3, '0')
        val a = ayahNumber.toString().padStart(3, '0')
        return "${normalizedBase}${s}${a}.mp3"
    }

    fun release() {
        pendingPauseJob?.cancel()
        wordPollingJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
    }
}
