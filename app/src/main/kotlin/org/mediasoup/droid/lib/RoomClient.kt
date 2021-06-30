package org.mediasoup.droid.lib

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.WorkerThread
import io.github.crow_misia.mediasoup.*
import io.github.crow_misia.webrtc.RTCComponentFactory
import io.github.crow_misia.webrtc.option.MediaConstraintsOption
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.json.JSONException
import org.json.JSONObject
import org.mediasoup.droid.lib.UrlFactory.getInvitationLink
import org.mediasoup.droid.lib.UrlFactory.getProtooUrl
import org.mediasoup.droid.lib.lv.RoomStore
import org.mediasoup.droid.lib.socket.WebSocketTransport
import org.protoojs.droid.Message
import org.protoojs.droid.Peer
import org.protoojs.droid.Peer.ServerRequestHandler
import org.protoojs.droid.ProtooException
import org.webrtc.CameraVideoCapturer.CameraSwitchHandler
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoCapturer
import timber.log.Timber
import java.nio.ByteBuffer


class RoomClient(
    context: Context,
    store: RoomStore,
    roomId: String,
    peerId: String,
    private var displayName: String,
    forceH264: Boolean = false,
    forceVP9: Boolean = false,
    private val camCapturer: VideoCapturer?,
    private val options: RoomOptions,
    private val mediaConstraintsOption: MediaConstraintsOption
) : RoomMessageHandler(store) {
    enum class ConnectionState {
        // initial state.
        NEW,  // connecting or reconnecting.
        CONNECTING,  // connected.
        CONNECTED,  // mClosed.
        CLOSED
    }

    // Closed flag.
    @Volatile
    private var closed: Boolean = false

    // Android context.
    private val appContext: Context = context.applicationContext

    private val componentFactory = RTCComponentFactory(mediaConstraintsOption)

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        componentFactory.createPeerConnectionFactory(context) { _, _ -> }
    }

    private val localAudioManager = componentFactory.createAudioManager()
    private val localVideoManager = componentFactory.createVideoManager()

    // TODO(Haiyangwu):Next expected dataChannel test number.
    private val nextDataChannelTestNumber: Long = 0

    // Protoo URL.
    private val protooUrl: String = getProtooUrl(roomId, peerId, forceH264, forceVP9)

    // mProtoo-client Protoo instance.
    private var protoo: Protoo? = null

    // mediasoup-client Device instance.
    private var mediasoupDevice: Device = Device(peerConnectionFactory)

    // mediasoup Transport for sending.
    private var sendTransport: SendTransport? = null

    // mediasoup Transport for receiving.
    private var recvTransport: RecvTransport? = null

    // Local mic mediasoup Producer.
    private var micProducer: Producer? = null

    // Local cam mediasoup Producer.
    private var camProducer: Producer? = null

    // TODO(Haiyangwu): Local share mediasoup Producer.
    private var shareProducer: Producer? = null

    // Local chat DataProducer.
    private var chatDataProducer: DataProducer? = null

    // TODO(Haiyangwu): Local bot DataProducer.
    private var botDataProducer: Producer? = null

    // jobs worker handler.
    private lateinit var workHandler: Handler

    // main looper handler.
    private val mainHandler: Handler

    // Disposable Composite. used to cancel running
    private val compositeDisposable = CompositeDisposable()

    // Share preferences
    private val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

    @Async
    fun join() {
        Timber.d("join() %s", protooUrl)
        store.setRoomState(ConnectionState.CONNECTING)
        workHandler.post {
            val transport = WebSocketTransport(protooUrl)
            protoo = Protoo(transport, peerListener)
        }
    }

    @Async
    fun enableMic() {
        Timber.d("enableMic()")
        workHandler.post { enableMicImpl() }
    }

    @Async
    fun disableMic() {
        Timber.d("disableMic()")
        workHandler.post { disableMicImpl() }
    }

    @Async
    fun muteMic() {
        Timber.d("muteMic()")
        workHandler.post { muteMicImpl() }
    }

    @Async
    fun unmuteMic() {
        Timber.d("unmuteMic()")
        workHandler.post { unmuteMicImpl() }
    }

    @Async
    fun enableCam() {
        Timber.d("enableCam()")
        store.setCamInProgress(true)
        workHandler.post {
            enableCamImpl()
            store.setCamInProgress(false)
        }
    }

    @Async
    fun disableCam() {
        Timber.d("disableCam()")
        workHandler.post { disableCamImpl() }
    }

    @Async
    fun changeCam() {
        Timber.d("changeCam()")
        store.setCamInProgress(true)
        workHandler.post {
            localVideoManager.switchCamera(
                object : CameraSwitchHandler {
                    override fun onCameraSwitchDone(b: Boolean) {
                        store.setCamInProgress(false)
                    }

                    override fun onCameraSwitchError(s: String) {
                        Timber.w("changeCam() | failed: %s", s)
                        store.addNotify("error", "Could not change cam: $s")
                        store.setCamInProgress(false)
                    }
                })
        }
    }

    @Async
    fun disableShare() {
        Timber.d("disableShare()")
        // TODO(feature): share
    }

    @Async
    fun enableShare() {
        Timber.d("enableShare()")
        // TODO(feature): share
    }

    @Async
    fun enableAudioOnly() {
        Timber.d("enableAudioOnly()")
        store.setAudioOnlyInProgress(true)
        disableCam()
        workHandler.post {
            for (holder in consumers.values) {
                if ("video" != holder.consumer.kind) {
                    continue
                }
                pauseConsumer(holder.consumer)
            }
            store.setAudioOnlyState(true)
            store.setAudioOnlyInProgress(false)
        }
    }

    @Async
    fun disableAudioOnly() {
        Timber.d("disableAudioOnly()")
        store.setAudioOnlyInProgress(true)
        if (camProducer == null && options.isProduce) {
            enableCam()
        }
        workHandler.post {
            for (holder in consumers.values) {
                if ("video" != holder.consumer.kind) {
                    continue
                }
                resumeConsumer(holder.consumer)
            }
            store.setAudioOnlyState(false)
            store.setAudioOnlyInProgress(false)
        }
    }

    @Async
    fun muteAudio() {
        Timber.d("muteAudio()")
        store.setAudioMutedState(true)
        workHandler.post {
            for (holder in consumers.values) {
                if ("audio" != holder.consumer.kind) {
                    continue
                }
                pauseConsumer(holder.consumer)
            }
        }
    }

    @Async
    fun unmuteAudio() {
        Timber.d("unmuteAudio()")
        store.setAudioMutedState(false)
        workHandler.post {
            for (holder in consumers.values) {
                if ("audio" != holder.consumer.kind) {
                    continue
                }
                resumeConsumer(holder.consumer)
            }
        }
    }

    @Async
    fun restartIce() {
        Timber.d("restartIce()")
        store.setRestartIceInProgress(true)
        workHandler.post {
            try {
                sendTransport?.also { transport ->
                    protoo?.syncRequest("restartIce") { req ->
                        JsonUtils.jsonPut(req, "transportId", transport.id)
                    }?.also {
                        transport.restartIce(it)
                    }
                }
                recvTransport?.also { transport ->
                    protoo?.syncRequest("restartIce") { req ->
                        JsonUtils.jsonPut(req, "transportId", transport.id)
                    }?.also {
                        transport.restartIce(it)
                    }
                }
            } catch (e: Exception) {
                logError("restartIce() | failed:", e)
                store.addNotify("error", "ICE restart failed: " + e.message)
            }
            store.setRestartIceInProgress(false)
        }
    }

    @Async
    fun setMaxSendingSpatialLayer() {
        Timber.d("setMaxSendingSpatialLayer()")
        // TODO(feature): layer
    }

    @Async
    fun setConsumerPreferredLayers(spatialLayer: String?) {
        Timber.d("setConsumerPreferredLayers()")
        // TODO(feature): layer
    }

    @Async
    fun setConsumerPreferredLayers(consumerId: String?, spatialLayer: String?, temporalLayer: String?) {
        Timber.d("setConsumerPreferredLayers()")
        // TODO: layer
    }

    @Async
    fun requestConsumerKeyFrame(consumerId: String?) {
        Timber.d("requestConsumerKeyFrame()")
        workHandler.post {
            try {
                protoo?.syncRequest("requestConsumerKeyFrame") { req ->
                    JsonUtils.jsonPut(req, "consumerId", "consumerId")
                }
                store.addNotify("Keyframe requested for video consumer")
            } catch (e: ProtooException) {
                logError("requestConsumerKeyFrame() | failed:", e)
                store.addNotify("error", "Request consumer key frame failed: " + e.message)
            }
        }
    }

    @Async
    fun enableChatDataProducer() {
        Timber.d("enableChatDataProducer()")
        if (!options.isUseDataChannel) {
            return
        }

        chatDataProducer = sendTransport?.produceData(
            listener = object : DataProducer.Listener {
                override fun onBufferedAmountChange(dataProducer: DataProducer, sentDataSize: Long) {
                    Timber.d("chat DataProducer \"bufferedamountlow\" event")
                }

                override fun onOpen(dataProducer: DataProducer) {
                    Timber.i("chat DataProducer \"open\" even")
                }

                override fun onClose(dataProducer: DataProducer) {
                    Timber.i("chat DataProducer \"close\" event")
                    chatDataProducer = null
                }

                override fun onTransportClose(dataProducer: DataProducer) {
                    chatDataProducer = null
                }
            },
            label = "chat",
            ordered = false,
            maxRetransmits = 1,
            appData = "{\"info\":\"my-chat-DataProducer\"}",
        )?.also {
            store.addDataProducer(it)
        }
    }

    @Async
    fun enableBotDataProducer() {
        Timber.d("enableBotDataProducer()")
        // TODO(feature): data channel
    }

    @Async
    fun sendChatMessage(message: String) {
        Timber.d("sendChatMessage()")

        if (chatDataProducer == null) {
            enableChatDataProducer()
        }
        chatDataProducer?.send(DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray()), false))
        Timber.d("Chat message sent: $message")
    }

    @Async
    fun sendBotMessage(txt: String?) {
        Timber.d("sendBotMessage()")
        // TODO(feature): data channel
    }

    @Async
    fun changeDisplayName(displayName: String) {
        Timber.d("changeDisplayName()")

        // Store in cookie.
        preferences.edit().putString("displayName", displayName).apply()
        workHandler.post {
            try {
                protoo?.syncRequest("changeDisplayName") { req ->
                    JsonUtils.jsonPut(req, "displayName", displayName)
                }
                this.displayName = displayName
                store.setDisplayName(displayName)
                store.addNotify("Display name change")
            } catch (e: ProtooException) {
                logError("changeDisplayName() | failed:", e)
                store.addNotify("error", "Could not change display name: " + e.message)

                // We need to refresh the component for it to render the previous
                // displayName again.
                store.setDisplayName(displayName)
            }
        }
    }

    // TODO(feature): stats
    @Async
    fun getSendTransportRemoteStats() {
        Timber.d("getSendTransportRemoteStats()")
        // TODO(feature): stats
    }

    // TODO(feature): stats
    @Async
    fun getRecvTransportRemoteStats() {
        Timber.d("getRecvTransportRemoteStats()")
        // TODO(feature): stats
    }

    // TODO(feature): stats
    @Async
    fun getAudioRemoteStats() {
        Timber.d("getAudioRemoteStats()")
        // TODO(feature): stats
    }

    // TODO(feature): stats
    @Async
    fun getVideoRemoteStats() {
        Timber.d("getVideoRemoteStats()")
        // TODO(feature): stats
    }

    @Async
    fun getConsumerRemoteStats(consumerId: String) {
        Timber.d("getConsumerRemoteStats()")
        // TODO(feature): stats
    }

    @Async
    fun getChatDataProducerRemoteStats(consumerId: String) {
        Timber.d("getChatDataProducerRemoteStats()")
        // TODO(feature): stats
    }

    // TODO(feature): stats
    @Async
    fun getBotDataProducerRemoteStats() {
        Timber.d("getBotDataProducerRemoteStats()")
        // TODO(feature): stats
    }

    @Async
    fun getDataConsumerRemoteStats(dataConsumerId: String) {
        Timber.d("getDataConsumerRemoteStats()")
        // TODO(feature): stats
    }

    // TODO(feature): stats
    @Async
    fun getSendTransportLocalStats() {
        Timber.d("getSendTransportLocalStats()")
        // TODO(feature): stats
    }

    /// TODO(feature): stats
    @Async
    fun getRecvTransportLocalStats() {
        Timber.d("getRecvTransportLocalStats()")
        /// TODO(feature): stats
    }

    // TODO(feature): stats
    @Async
    fun getAudioLocalStats() {
        Timber.d("getAudioLocalStats()")
        // TODO(feature): stats
    }

    // TODO(feature): stats
    @Async
    fun getVideoLocalStats() {
        Timber.d("getVideoLocalStats()")
        // TODO(feature): stats
    }

    @Async
    fun getConsumerLocalStats(consumerId: String) {
        Timber.d("getConsumerLocalStats()")
        // TODO(feature): stats
    }

    @Async
    fun applyNetworkThrottle(uplink: String?, downlink: String?, rtt: String?, secret: String?) {
        Timber.d("applyNetworkThrottle()")
        // TODO(feature): stats
    }

    @Async
    fun resetNetworkThrottle(silent: Boolean, secret: String?) {
        Timber.d("applyNetworkThrottle()")
        // TODO(feature): stats
    }

    @Async
    fun close() {
        if (closed) {
            return
        }
        closed = true
        Timber.d("close()")
        workHandler.post {
            // Close Protoo Client
            protoo?.close()
            protoo = null

            // dispose all transport and device.
            disposeTransportDevice()

            // dispose audio manager.
            localAudioManager.dispose()

            // dispose video manager.
            localVideoManager.dispose()

            // quit worker handler thread.
            workHandler.looper.quit()
        }

        // dispose request.
        compositeDisposable.dispose()

        // Set room state.
        store.setRoomState(ConnectionState.CLOSED)
    }

    @WorkerThread
    private fun disposeTransportDevice() {
        Timber.d("disposeTransportDevice()")
        // Close mediasoup Transports.
        sendTransport?.dispose()
        sendTransport = null

        recvTransport?.dispose()
        recvTransport = null
    }

    private val peerListener: Peer.Listener =
        object : Peer.Listener {
            override fun onOpen() {
                workHandler.post { joinImpl() }
            }

            override fun onFail() {
                workHandler.post {
                    store.addNotify("error", "WebSocket connection failed")
                    store.setRoomState(ConnectionState.CONNECTING)
                }
            }

            override fun onRequest(
                request: Message.Request, handler: ServerRequestHandler
            ) {
                Timber.d("onRequest() %s:%s", request.method, request.data)
                workHandler.post {
                    try {
                        when (request.method) {
                            "newConsumer" -> onNewConsumer(request, handler)
                            "newDataConsumer" -> onNewDataConsumer(request, handler)
                            else -> {
                                handler.reject(403, "unknown protoo request.method " + request.method)
                                Timber.w("unknown protoo request.method %s", request.method)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "handleRequestError.")
                    }
                }
            }

            override fun onNotification(notification: Message.Notification) {
                Timber.d("onNotification() %s, %s", notification.method, notification.data)
                workHandler.post {
                    try {
                        handleNotification(notification)
                    } catch (e: Exception) {
                        Timber.e(e, "handleNotification error.")
                    }
                }
            }

            override fun onDisconnected() {
                workHandler.post {
                    store.addNotify("error", "WebSocket disconnected")
                    store.setRoomState(ConnectionState.CONNECTING)

                    // Close All Transports created by device.
                    // All will reCreated After ReJoin.
                    disposeTransportDevice()
                }
            }

            override fun onClose() {
                if (closed) {
                    return
                }
                workHandler.post {
                    if (closed) {
                        return@post
                    }
                    close()
                }
            }
        }

    @WorkerThread
    private fun joinImpl() {
        Timber.d("joinImpl()")
        try {
            val device = mediasoupDevice
            protoo?.syncRequest("getRouterRtpCapabilities")?.also {
                Timber.d("getRouterRtpCapabilities() $it")
                if (!device.loaded) {
                    device.load(it)
                }
            }
            val rtpCapabilities = if (options.isConsume) {
                JsonUtils.toJsonObject(device.rtpCapabilities)
            } else null
            val sctpCapabilitiesForProducer = if (options.isUseDataChannel) {
                JsonUtils.toJsonObject(device.sctpCapabilities)
            } else null
            val sctpCapabilitiesForConsume = if (options.isUseDataChannel && options.isConsume) {
                sctpCapabilitiesForProducer
            } else null

            // Create mediasoup Transport for sending (unless we don't want to produce).
            if (options.isProduce) {
                createSendTransport(sctpCapabilitiesForProducer)
            }

            // Create mediasoup Transport for sending (unless we don't want to consume).
            if (options.isConsume) {
                createRecvTransport(sctpCapabilitiesForConsume)
            }

            // Join now into the room.
            val joinResponse = protoo?.syncRequest("join") { req ->
                JsonUtils.jsonPut(req, "displayName", displayName)
                JsonUtils.jsonPut(req, "device", options.device.toJSONObject())
                JsonUtils.jsonPut(req, "rtpCapabilities", rtpCapabilities)
                JsonUtils.jsonPut(req, "sctpCapabilities", sctpCapabilitiesForConsume)
            } ?: return
            store.setRoomState(ConnectionState.CONNECTED)
            store.addNotify("You are in the room!", 3000)
            val resObj = JsonUtils.toJsonObject(joinResponse)
            val peers = resObj.optJSONArray("peers")
            var i = 0
            while (peers != null && i < peers.length()) {
                val peer = peers.getJSONObject(i)
                store.addPeer(peer.optString("id"), peer)
                i++
            }

            // Enable mic/webcam.
            if (options.isProduce) {
                val canSendMic = mediasoupDevice.canProduce("audio")
                val canSendCam = mediasoupDevice.canProduce("video")
                store.setMediaCapabilities(canSendMic, canSendCam)
                mainHandler.post { enableMic() }
                mainHandler.post { enableCam() }
            }
        } catch (e: Exception) {
            logError("joinRoom() failed:", e)
            if (e.message.isNullOrEmpty()) {
                store.addNotify("error", "Could not join the room, internal error")
            } else {
                store.addNotify("error", "Could not join the room: " + e.message)
            }
            mainHandler.post { close() }
        }
    }

    @WorkerThread
    private fun enableMicImpl() {
        Timber.d("enableMicImpl()")
        if (micProducer != null) {
            return
        }
        try {
            val mediasoupDevice = mediasoupDevice
            if (!mediasoupDevice.loaded) {
                Timber.w("enableMic() | not loaded")
                return
            }
            if (!mediasoupDevice.canProduce("audio")) {
                Timber.w("enableMic() | cannot produce audio")
                return
            }
            val sendTransport = sendTransport ?: run {
                Timber.w("enableMic() | mSendTransport doesn't ready")
                return
            }
            localAudioManager.initTrack(peerConnectionFactory, mediaConstraintsOption)
            val track = localAudioManager.track ?: run {
                Timber.w("audio track null")
                return
            }
            val micProducer = sendTransport.produce(
                listener = object : Producer.Listener {
                    override fun onTransportClose(producer: Producer) {
                        Timber.e("onTransportClose(), micProducer")
                        micProducer?.also {
                            store.removeProducer(it.id)
                            micProducer = null
                        }
                    }
                },
                track = track,
                encodings = emptyList(),
                codecOptions = null
            )
            this.micProducer = micProducer
            store.addProducer(micProducer)
        } catch (e: MediasoupException) {
            logError("enableMic() | failed:", e)
            store.addNotify("error", "Error enabling microphone: " + e.message)
            localAudioManager.setEnabled(false)
        }
    }

    @WorkerThread
    private fun disableMicImpl() {
        Timber.d("disableMicImpl()")
        val micProducer = micProducer ?: return
        this.micProducer = null
        micProducer.close()
        micProducer.dispose()
        store.removeProducer(micProducer.id)
        try {
            protoo?.syncRequest("closeProducer") { req ->
                JsonUtils.jsonPut(req, "producerId", micProducer.id)
            }
        } catch (e: ProtooException) {
            store.addNotify("error", "Error closing server-side mic Producer: " + e.message)
        }
    }

    @WorkerThread
    private fun muteMicImpl() {
        Timber.d("muteMicImpl()")
        val micProducer = micProducer ?: return
        micProducer.pause()
        try {
            protoo?.syncRequest("pauseProducer") { req ->
                JsonUtils.jsonPut(req, "producerId", micProducer.id)
            }
            store.setProducerPaused(micProducer.id)
        } catch (e: ProtooException) {
            logError("muteMic() | failed:", e)
            store.addNotify("error", "Error pausing server-side mic Producer: " + e.message)
        }
    }

    @WorkerThread
    private fun unmuteMicImpl() {
        Timber.d("unmuteMicImpl()")
        val micProducer = micProducer ?: return
        micProducer.resume()
        try {
            protoo?.syncRequest("resumeProducer") { req ->
                JsonUtils.jsonPut(req, "producerId", micProducer.id)
            }
            store.setProducerResumed(micProducer.id)
        } catch (e: ProtooException) {
            logError("unmuteMic() | failed:", e)
            store.addNotify("error", "Error resuming server-side mic Producer: " + e.message)
        }
    }

    @WorkerThread
    private fun enableCamImpl() {
        Timber.d("enableCamImpl()")
        if (camProducer != null) {
            return
        }
        try {
            if (!mediasoupDevice.loaded) {
                Timber.w("enableCam() | not loaded")
                return
            }
            if (!mediasoupDevice.canProduce("video")) {
                Timber.w("enableCam() | cannot produce video")
                return
            }
            val sendTransport = sendTransport ?: run {
                Timber.w("enableCam() | mSendTransport doesn't ready")
                return
            }
            localVideoManager.initTrack(peerConnectionFactory, mediaConstraintsOption, appContext)
            camCapturer?.startCapture(640, 480, 30)
            val track = localVideoManager.track ?: run {
                Timber.w("video track null")
                return
            }
            val camProducer = sendTransport.produce(
                listener = object : Producer.Listener {
                    override fun onTransportClose(producer: Producer) {
                        Timber.e("onTransportClose(), camProducer")
                        camProducer?.also {
                            store.removeProducer(it.id)
                            camProducer = null
                        }
                    }
                },
                track = track,
                encodings = emptyList(),
                codecOptions = null
            )
            this.camProducer = camProducer
            store.addProducer(camProducer)
        } catch (e: MediasoupException) {
            logError("enableWebcam() | failed:", e)
            store.addNotify("error", "Error enabling webcam: " + e.message)
            localVideoManager.enabled = false
        }
    }

    @WorkerThread
    private fun disableCamImpl() {
        Timber.d("disableCamImpl()")
        val camProducer = camProducer ?: return
        this.camProducer = null
        camProducer.close()
        camProducer.dispose()
        camCapturer?.stopCapture()
        store.removeProducer(camProducer.id)
        try {
            protoo?.syncRequest("closeProducer") { req ->
                JsonUtils.jsonPut(req, "producerId", camProducer.id)
            }
        } catch (e: ProtooException) {
            store.addNotify("error", "Error closing server-side webcam Producer: " + e.message)
        }
    }

    @WorkerThread
    @Throws(ProtooException::class, JSONException::class, MediasoupException::class)
    private fun createSendTransport(sctpCapabilities: JSONObject? = null) {
        Timber.d("createSendTransport()")
        val info = protoo?.syncRequest("createWebRtcTransport") { req ->
            JsonUtils.jsonPut(req, "forceTcp", options.isForceTcp)
            JsonUtils.jsonPut(req, "producing", true)
            JsonUtils.jsonPut(req, "consuming", false)
            JsonUtils.jsonPut(req, "sctpCapabilities", sctpCapabilities)
        }?.let {
            JSONObject(it)
        } ?: run {
            Timber.d("createWebRtcTransport failed: response not found")
            return
        }

        Timber.d("device#createSendTransport() $info")
        val id = info.optString("id")
        val iceParameters = info.optString("iceParameters")
        val iceCandidates = info.optString("iceCandidates")
        val dtlsParameters = info.optString("dtlsParameters")
        val sctpParameters = info.optString("sctpParameters")

        sendTransport = mediasoupDevice.createSendTransport(
            listener = sendTransportListener,
            id = id,
            iceParameters = iceParameters,
            iceCandidates = iceCandidates,
            dtlsParameters = dtlsParameters,
            sctpParameters = sctpParameters,
            appData = null,
            rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        )
    }

    @WorkerThread
    @Throws(ProtooException::class, JSONException::class, MediasoupException::class)
    private fun createRecvTransport(sctpCapabilities: JSONObject? = null) {
        Timber.d("createRecvTransport()")
        val info = protoo?.syncRequest("createWebRtcTransport") { req ->
            JsonUtils.jsonPut(req, "forceTcp", options.isForceTcp)
            JsonUtils.jsonPut(req, "producing", false)
            JsonUtils.jsonPut(req, "consuming", true)
            JsonUtils.jsonPut(req, "sctpCapabilities", sctpCapabilities)
        }?.let {
            JSONObject(it)
        } ?: run {
            Timber.d("createWebRtcTransport failed: response not found")
            return
        }

        Timber.d("device#createRecvTransport() $info")
        val id = info.optString("id")
        val iceParameters = info.optString("iceParameters")
        val iceCandidates = info.optString("iceCandidates")
        val dtlsParameters = info.optString("dtlsParameters")
        val sctpParameters = info.optString("sctpParameters")

        recvTransport = mediasoupDevice.createRecvTransport(
            listener = recvTransportListener,
            id = id,
            iceParameters = iceParameters,
            iceCandidates = iceCandidates,
            dtlsParameters = dtlsParameters,
            sctpParameters = sctpParameters,
            rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        )
    }

    private val sendTransportListener: SendTransport.Listener = object : SendTransport.Listener {
        private val listenerTAG = TAG + "_SendTrans"
        override fun onProduce(transport: Transport, kind: String, rtpParameters: String, appData: String?): String {
            if (closed) {
                return ""
            }
            Timber.tag(listenerTAG).d("onProduce() ")
            val producerId = fetchProduceId { req ->
                JsonUtils.jsonPut(req, "transportId", transport.id)
                JsonUtils.jsonPut(req, "kind", kind)
                JsonUtils.jsonPut(req, "rtpParameters", JsonUtils.toJsonObject(rtpParameters))
                JsonUtils.jsonPut(req, "appData", appData.toJsonObject())
            }
            Timber.tag(listenerTAG).d("producerId: %s", producerId)
            return producerId
        }

        override fun onProduceData(transport: Transport, sctpStreamParameters: String, label: String, protocol: String, appData: String?): String {
            if (closed) {
                return ""
            }
            Timber.tag(listenerTAG).d("onProduceData() ")
            val dataProducerId = fetchDataProduceId { req ->
                JsonUtils.jsonPut(req, "transportId", transport.id)
                JsonUtils.jsonPut(req, "sctpStreamParameters", sctpStreamParameters.toJsonObject())
                JsonUtils.jsonPut(req, "label", label)
                JsonUtils.jsonPut(req, "protocol", protocol)
                JsonUtils.jsonPut(req, "appData", appData.toJsonObject())
            }
            Timber.tag(listenerTAG).d("dataProducerId: %s", dataProducerId)
            return dataProducerId
        }

        override fun onConnect(transport: Transport, dtlsParameters: String) {
            if (closed) {
                return
            }
            Timber.tag(listenerTAG).d("onConnect()")
            protoo?.request("connectWebRtcTransport") { req: JSONObject ->
                JsonUtils.jsonPut(req, "transportId", transport.id)
                JsonUtils.jsonPut(req, "dtlsParameters", JSONObject(dtlsParameters))
            }?.subscribeBy(
                onNext = { d: String? ->
                    Timber.tag(listenerTAG).d("connectWebRtcTransport res: %s", d)
                },
                onError = { t: Throwable ->
                    logError("connectWebRtcTransport for mSendTransport failed", t)
                }
            )?.addTo(compositeDisposable)
        }

        override fun onConnectionStateChange(transport: Transport, newState: String) {
            Timber.tag(listenerTAG).d("onConnectionStateChange: %s", newState)
        }
    }

    private val recvTransportListener: RecvTransport.Listener = object : RecvTransport.Listener {
        private val listenerTAG = TAG + "_RecvTrans"
        override fun onConnect(transport: Transport, dtlsParameters: String) {
            if (closed) {
                return
            }
            Timber.tag(listenerTAG).d("onConnect()")
            protoo?.request("connectWebRtcTransport") { req ->
                JsonUtils.jsonPut(req, "transportId", transport.id)
                JsonUtils.jsonPut(req, "dtlsParameters", JSONObject(dtlsParameters))
            }?.subscribeBy(
                onNext = { d: String? ->
                    Timber.tag(listenerTAG).d("connectWebRtcTransport res: %s", d)
                },
                onError = { t: Throwable ->
                    logError("connectWebRtcTransport for mRecvTransport failed", t)
                }
            )?.addTo(compositeDisposable)
        }

        override fun onConnectionStateChange(transport: Transport, newState: String) {
            Timber.tag(listenerTAG).d("onConnectionStateChange: %s", newState)
        }
    }

    private fun fetchProduceId(generator: (JSONObject) -> Unit): String {
        Timber.d("fetchProduceId:()")
        return try {
            val response = protoo?.syncRequest("produce", generator) ?: ""
            JSONObject(response).optString("id")
        } catch (e: ProtooException) {
            logError("send produce request failed", e)
            ""
        } catch (e: JSONException) {
            logError("send produce request failed", e)
            ""
        }
    }

    private fun fetchDataProduceId(generator: (JSONObject) -> Unit): String {
        Timber.d("fetchDataProduceId:()")
        return try {
            val response = protoo?.syncRequest("produceData", generator) ?: ""
            JSONObject(response).optString("id")
        } catch (e: ProtooException) {
            logError("send produceData request failed", e)
            ""
        } catch (e: JSONException) {
            logError("send produceData request failed", e)
            ""
        }
    }

    private fun logError(message: String, throwable: Throwable) {
        Timber.e(throwable, message)
    }

    private fun onNewConsumer(request: Message.Request, handler: ServerRequestHandler) {
        if (!options.isConsume) {
            handler.reject(403, "I do not want to consume")
            return
        }
        try {
            val data = request.data
            val peerId = data.optString("peerId")
            val producerId = data.optString("producerId")
            val id = data.optString("id")
            val kind = data.optString("kind")
            val rtpParameters = data.optString("rtpParameters")
            val type = data.optString("type")
            val appData = data.optString("appData")
            val producerPaused = data.optBoolean("producerPaused")

            val consumer = recvTransport?.consume(
                listener = object : Consumer.Listener {
                    override fun onTransportClose(consumer: Consumer) {
                        consumers.remove(consumer.id)?.also {
                            it.consumer.dispose()
                        }
                        Timber.w("onTransportClose for consume")
                    }
                },
                id = id,
                producerId = producerId,
                kind = kind,
                rtpParameters = rtpParameters,
                appData = appData
            ) ?: return

            consumers[consumer.id] = ConsumerHolder(peerId, consumer)
            store.addConsumer(peerId, type, consumer, producerPaused)

            // We are ready. Answer the protoo request so the server will
            // resume this Consumer (which was paused for now if video).
            handler.accept()

            // If audio-only mode is enabled, pause it.
            if ("video" == consumer.kind && store.me.value?.isAudioOnly == true) {
                pauseConsumer(consumer)
            }
        } catch (e: Exception) {
            logError("\"newConsumer\" request failed:", e)
            store.addNotify("error", "Error creating a Consumer: " + e.message)
        }
    }

    private fun onNewDataConsumer(request: Message.Request, handler: ServerRequestHandler) {
        if (!options.isConsume) {
            handler.reject(403, "I do not want to dataConsume")
            return
        }
        if (!options.isUseDataChannel)
        {
            handler.reject(403, "I do not want to DataChannels")
            return
        }

        try {
            val data = request.data
            val peerId = data.optString("peerId")
            val dataProducerId = data.optString("dataProducerId")
            val id = data.optString("id")
            val label = data.optString("label")
            val protocol = data.optString("protocol")
            val appData = data.optString("appData")

            val dataConsumer = recvTransport?.consumeData(
                listener = object : DataConsumer.Listener {
                    override fun onConnecting(dataConsumer: DataConsumer) {
                        Timber.i("onConnecting for dataConsume")
                    }

                    override fun onOpen(dataConsumer: DataConsumer) {
                        Timber.i("onOpen for dataConsume")
                    }

                    override fun onMessage(dataConsumer: DataConsumer, buffer: DataChannel.Buffer) {
                        val sctpStreamParameters = JsonUtils.toJsonObject(dataConsumer.sctpStreamParameters)
                        Timber.i("onMessage for dataConsume: [dataConsumerId:%s, streamId:%s]", dataConsumer.id, sctpStreamParameters.optString("streamId"))
                        val sendingPeer = store.peers.value?.allPeers?.firstOrNull { peer ->
                            peer.dataConsumers.contains(dataConsumer.id)
                        } ?: run {
                            Timber.i("DataConsumer \"message\" from unknown peer")
                            return
                        }

                        val byteArray = ByteArray(buffer.data.remaining())
                        buffer.data.get(byteArray)
                        val message = "${sendingPeer.displayName}: ${String(byteArray)}"

                        Timber.i("onMessage for dataConsume: %s", message)
                        store.addNotify(message)
                    }

                    override fun onClosing(dataConsumer: DataConsumer) {
                        Timber.i("onClosing for dataConsume")
                    }

                    override fun onClose(dataConsumer: DataConsumer) {
                        Timber.i("onCLose for dataConsume")
                    }

                    override fun onTransportClose(dataConsumer: DataConsumer) {
                        dataConsumers.remove(dataConsumer.id)?.also {
                            it.dataConsumer.dispose()
                        }
                        Timber.w("onTransportClose for dataConsume")
                    }
                },
                id = id,
                producerId = dataProducerId,
                label = label,
                protocol = protocol,
                appData = appData
            ) ?: return

            dataConsumers[dataConsumer.id] = DataConsumerHolder(peerId, dataConsumer)
            store.addDataConsumer(peerId, dataConsumer)

            // We are ready. Answer the protoo request so the server will
            // resume this DataConsumer (which was paused for now if video).
            handler.accept()
        } catch (e: Exception) {
            logError("\"newDataConsumer\" request failed:", e)
            store.addNotify("error", "Error creating a DataConsumer: " + e.message)
        }
    }

    @WorkerThread
    private fun pauseConsumer(consumer: Consumer) {
        Timber.d("pauseConsumer() %s", consumer.id)
        if (consumer.paused) {
            return
        }
        try {
            protoo?.syncRequest("pauseConsumer") { req ->
                JsonUtils.jsonPut(req, "consumerId", consumer.id)
            }
            consumer.pause()
            store.setConsumerPaused(consumer.id, "local")
        } catch (e: ProtooException) {
            logError("pauseConsumer() | failed:", e)
            store.addNotify("error", "Error pausing Consumer: " + e.message)
        }
    }

    @WorkerThread
    private fun resumeConsumer(consumer: Consumer) {
        Timber.d("resumeConsumer() %s", consumer.id)
        if (!consumer.paused) {
            return
        }
        try {
            protoo?.syncRequest("resumeConsumer") { req ->
                JsonUtils.jsonPut(req, "consumerId", consumer.id)
            }
            consumer.resume()
            store.setConsumerResumed(consumer.id, "local")
        } catch (e: Exception) {
            logError("resumeConsumer() | failed:", e)
            store.addNotify("error", "Error resuming Consumer: " + e.message)
        }
    }

    init {
        store.setMe(peerId, displayName, options.device)
        store.setRoomUrl(roomId, getInvitationLink(roomId, forceH264, forceVP9))

        // init worker handler.
        val handlerThread = HandlerThread("worker")
        handlerThread.start()
        workHandler = Handler(handlerThread.looper)
        mainHandler = Handler(Looper.getMainLooper())
    }
}