package com.m3u.features.live

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.m3u.core.architecture.Logger
import com.m3u.core.architecture.configuration.Configuration
import com.m3u.core.architecture.viewmodel.BaseViewModel
import com.m3u.data.repository.FeedRepository
import com.m3u.data.repository.LiveRepository
import com.m3u.data.service.PlayerManager
import com.m3u.dlna.DLNACastManager
import com.m3u.dlna.OnDeviceRegistryListener
import com.m3u.dlna.control.DeviceControl
import com.m3u.dlna.control.OnDeviceControlListener
import com.m3u.dlna.control.ServiceActionCallback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.fourthline.cling.model.meta.Device
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val liveRepository: LiveRepository,
    private val feedRepository: FeedRepository,
    application: Application,
    configuration: Configuration,
    private val playerManager: PlayerManager,
    private val logger: Logger,
    @Logger.Ui private val uiLogger: Logger
) : BaseViewModel<LiveState, LiveEvent>(
    application = application,
    emptyState = LiveState(
        configuration = configuration
    )
), OnDeviceRegistryListener, OnDeviceControlListener {
    private val _devices = MutableStateFlow<List<Device<*, *, *>>>(emptyList())
    val devices = _devices.asStateFlow()

    init {
        playerManager
            .observe()
            .onEach { player ->
                writable.update {
                    it.copy(
                        player = player,
                        muted = player?.volume == 0f
                    )
                }
            }
            .launchIn(viewModelScope)

        playerManager.initialize()

        combine(
            playerManager.playbackState,
            playerManager.videoSize,
            playerManager.playerError
        ) { playback, videoSize, playerError ->
            LiveState.PlayerState(
                playback = playback,
                videoSize = videoSize,
                playerError = playerError
            )
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = LiveState.PlayerState()
            )
            .onEach { playerState ->
                writable.value = readable.copy(
                    playerState = playerState
                )
            }
            .launchIn(viewModelScope)
    }

    override fun onEvent(event: LiveEvent) {
        when (event) {
            is LiveEvent.InitSpecial -> initSpecial(event.liveId)
            is LiveEvent.InitPlayList -> initPlaylist(event.ids, event.initialIndex)
            LiveEvent.OpenDlnaDevices -> openDlnaDevices()
            LiveEvent.CloseDlnaDevices -> closeDlnaDevices()
            is LiveEvent.ConnectDlnaDevice -> connectDlnaDevice(event.device)
            is LiveEvent.DisconnectDlnaDevice -> disconnectDlnaDevice(event.device)
            LiveEvent.Record -> record()
            is LiveEvent.OnFavourite -> setFavourite(event.url)
            is LiveEvent.InstallMedia -> {
                val url = event.url
                if (url.isEmpty()) return
                playerManager.install(url)
            }

            LiveEvent.UninstallMedia -> {
                playerManager.uninstall()
            }

            LiveEvent.OnMuted -> muted()
        }
    }

    private var initJob: Job? = null
    private fun initSpecial(id: Int) {
        initJob?.cancel()
        initJob = viewModelScope.launch {
            liveRepository.observe(id)
                .onEach { live ->
                    if (live != null) {
                        val feed = feedRepository.get(live.feedUrl)
                        writable.update {
                            it.copy(
                                init = LiveState.InitSingle(
                                    live = live,
                                    feed = feed
                                )
                            )
                        }
                    }
                }
                .launchIn(this)
        }
    }

    private fun initPlaylist(ids: List<Int>, initialIndex: Int) {
        initJob?.cancel()
        initJob = viewModelScope.launch {
            val lives = when (val init = readable.init) {
                is LiveState.InitPlayList -> init.lives
                is LiveState.InitSingle -> init.live?.let(::listOf) ?: emptyList()
            }.toMutableList()
            ids.forEach { id ->
                val live = liveRepository.get(id)
                if (live != null) {
                    lives.add(live)
                }
            }
            writable.update { readable ->
                readable.copy(
                    init = LiveState.InitPlayList(
                        lives = lives,
                        initialIndex = initialIndex
                    ),
                )
            }
        }
    }

    private val _isDevicesVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isDevicesVisible = _isDevicesVisible.asStateFlow()

    private val _searching: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val searching = _searching.asStateFlow()

    private fun openDlnaDevices() {
        DLNACastManager.bindCastService(getApplication())
        DLNACastManager.registerDeviceListener(this)
        viewModelScope.launch {
            delay(800.milliseconds)
            _searching.value = true
        }
        _isDevicesVisible.value = true
    }

    private fun closeDlnaDevices() {
        _searching.value = false
        _isDevicesVisible.value = false
        _devices.value = emptyList()
        DLNACastManager.unbindCastService(getApplication())
        DLNACastManager.unregisterListener(this)
    }

    private var controlPoint: DeviceControl? = null

    private fun connectDlnaDevice(device: Device<*, *, *>) {
        controlPoint = DLNACastManager.connectDevice(device, this)
    }

    private fun disconnectDlnaDevice(device: Device<*, *, *>) {
        controlPoint?.stop()
        DLNACastManager.disconnectDevice(device)
    }

    private fun record() {
        writable.update {
            it.copy(
                recording = !readable.recording
            )
        }
    }

    private fun setFavourite(url: String) {
        viewModelScope.launch {
            val live = liveRepository.getByUrl(url) ?: return@launch
            val id = live.id
            val target = !live.favourite
            liveRepository.setFavourite(id, target)
        }
    }

    private fun muted() {
        val target = !readable.muted
        val volume = if (target) 0f else 1f
        readable.player?.volume = volume
        controlPoint?.setMute(target)
        writable.update {
            it.copy(
                muted = target
            )
        }
    }

    override fun onDeviceAdded(device: Device<*, *, *>) {
        _devices.update { it + device }
    }

    override fun onDeviceRemoved(device: Device<*, *, *>) {
        _devices.update { it - device }
    }

    override fun onConnected(device: Device<*, *, *>) {
        writable.update { it.copy(connected = device) }
        val url = when (val init = readable.init) {
            is LiveState.InitSingle -> init.live?.url ?: return
            is LiveState.InitPlayList -> {
                unsupportedInScrollMode()
                return
            }
        }
        val title = when (val init = readable.init) {
            is LiveState.InitSingle -> init.live?.title ?: return
            is LiveState.InitPlayList -> {
                unsupportedInScrollMode()
                return
            }
        }

        controlPoint?.setAVTransportURI(
            uri = url,
            title = title,
            callback = object : ServiceActionCallback<Unit> {
                override fun onSuccess(result: Unit) {
                    logger.log("ok: url=$url, title=$title")
                    controlPoint?.play()
                }

                override fun onFailure(msg: String) {
                    logger.log(msg)
                }
            }
        )
    }

    override fun onDisconnected(device: Device<*, *, *>) {
        writable.update { it.copy(connected = null) }
        controlPoint = null
    }

    private fun unsupportedInScrollMode() {
        uiLogger.log("this feature is unsupported when scroll mode is on.")
    }
}