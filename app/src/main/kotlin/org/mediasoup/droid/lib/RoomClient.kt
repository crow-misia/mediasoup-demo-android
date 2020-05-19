package org.mediasoup.droid.lib

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.WorkerThread
import com.squareup.moshi.Moshi
import io.github.zncmn.mediasoup.*
import io.github.zncmn.mediasoup.model.*
import io.github.zncmn.webrtc.RTCComponentFactory
import io.github.zncmn.webrtc.option.MediaConstraintsOption
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import io.reactivex.rxjava3.kotlin.subscribeBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.mediasoup.droid.lib.UrlFactory.getInvitationLink
import org.mediasoup.droid.lib.UrlFactory.getProtooUrl
import org.mediasoup.droid.lib.lv.RoomStore
import org.mediasoup.droid.lib.socket.CreateWebRtcTransportResponse
import org.mediasoup.droid.lib.socket.NewConsumerResponse
import org.mediasoup.droid.lib.socket.WebSocketTransport
import org.protoojs.droid.Message
import org.protoojs.droid.Peer
import org.protoojs.droid.Peer.ServerRequestHandler
import org.protoojs.droid.ProtooException
import org.webrtc.CameraVideoCapturer.CameraSwitchHandler
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoCapturer
import timber.log.Timber

class RoomClient(context: Context,
                 store: RoomStore,
                 roomId: String,
                 peerId: String,
                 private var displayName: String,
                 forceH264: Boolean = false,
                 forceVP9: Boolean = false,
                 private val camCapturer: VideoCapturer?,
                 private val options: RoomOptions = RoomOptions(),
                 private val mediaConstraintsOption: MediaConstraintsOption
) : RoomMessageHandler(store) {
    private val moshi = Moshi.Builder().build()

    // Closed flag.
    @Volatile
    private var closed: Boolean = false

    // Android context.
    private val appContext: Context = context.applicationContext

    private val componentFactory = RTCComponentFactory(mediaConstraintsOption)

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        componentFactory.createPeerConnectionFactory(context) { }
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
    private val shareProducer: Producer? = null

    // TODO(Haiyangwu): Local chat DataProducer.
    private val chatDataProducer: Producer? = null

    // TODO(Haiyangwu): Local bot DataProducer.
    private val botDataProducer: Producer? = null

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
        workHandler.post { runBlocking {
            enableMicImpl()
        } }
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
            runBlocking {
                enableCamImpl()
            }
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
                    }?.let { moshi.adapter(IceParameters::class.java).fromJson(it) }?.also {
                        runBlocking {
                            transport.restartIce(it)
                        }
                    }
                }
                recvTransport?.also { transport ->
                    val iceParameters = protoo?.syncRequest("restartIce") { req ->
                        JsonUtils.jsonPut(req, "transportId", transport.id)
                    }?.let { moshi.adapter(IceParameters::class.java).fromJson(it) }?.also {
                        runBlocking {
                            transport.restartIce(it)
                        }
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
                logError("restartIce() | failed:", e)
                store.addNotify("error", "ICE restart failed: " + e.message)
            }
        }
    }

    @Async
    fun enableChatDataProducer() {
        Timber.d("enableChatDataProducer()")
        // TODO(feature): data channel
    }

    @Async
    fun enableBotDataProducer() {
        Timber.d("enableBotDataProducer()")
        // TODO(feature): data channel
    }

    @Async
    fun sendChatMessage(txt: String?) {
        Timber.d("sendChatMessage()")
        // TODO(feature): data channel
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
            // Close mProtoo Protoo
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
                workHandler.post {
                    runBlocking {
                        joinImpl()
                    }
                }
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
                            "newConsumer" -> runBlocking { onNewConsumer(request, handler) }
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
    private suspend fun joinImpl() {
        Timber.d("joinImpl()")
        try {
            val device = mediasoupDevice
            withContext(Dispatchers.IO) {
                protoo?.syncRequest("getRouterRtpCapabilities")?.also {
                    val rtpCapabilities = requireNotNull(moshi.adapter(RtpCapabilities::class.java).fromJson(it))
                    device.load(rtpCapabilities)
                }
            }
            val rtpCapabilities = device.recvRtpCapabilities

            // Create mediasoup Transport for sending (unless we don't want to produce).
            if (options.isProduce) {
                createSendTransport()
            }

            // Create mediasoup Transport for sending (unless we don't want to consume).
            if (options.isConsume) {
                createRecvTransport()
            }

            // Join now into the room.
            // TODO(HaiyangWu): Don't send our RTP capabilities if we don't want to consume.
            val joinResponse = protoo?.syncRequest("join") { req ->
                JsonUtils.jsonPut(req, "displayName", displayName)
                JsonUtils.jsonPut(req, "device", options.device.toJSONObject())
                JsonUtils.jsonPut(req, "rtpCapabilities", JSONObject(moshi.adapter(RtpCapabilities::class.java).toJson(rtpCapabilities)))
                // TODO (HaiyangWu): add sctpCapabilities
                JsonUtils.jsonPut(req, "sctpCapabilities", "")
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
    private suspend fun enableMicImpl() {
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
                producerListener = object : Producer.Listener {
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
            localAudioManager.enabled = false
        }
    }

    @WorkerThread
    private fun disableMicImpl() {
        Timber.d("disableMicImpl()")
        val micProducer = micProducer ?: return
        this.micProducer = null
        micProducer.close()
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
    private suspend fun enableCamImpl() {
        Timber.d("enableCamImpl()")
        if (camProducer != null) {
            return
        }
        try {
            val mediasoupDevice = mediasoupDevice ?: return
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
                producerListener = object : Producer.Listener {
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
    private suspend fun createSendTransport() {
        val info = withContext(Dispatchers.IO) {
            Timber.d("createSendTransport()")
            protoo?.syncRequest("createWebRtcTransport") { req ->
                JsonUtils.jsonPut(req, "forceTcp", options.isForceTcp)
                JsonUtils.jsonPut(req, "producing", true)
                JsonUtils.jsonPut(req, "consuming", false)
                // TODO: sctpCapabilities
                JsonUtils.jsonPut(req, "sctpCapabilities", "")
            }?.let {
                moshi.adapter(CreateWebRtcTransportResponse::class.java).fromJson(it)
            }
        } ?: run {
            Timber.d("createWebRtcTransport failed: response not found")
            return
        }

        Timber.d("device#createSendTransport() $info")
        sendTransport = mediasoupDevice.createSendTransport(
            listener = sendTransportListener,
            id = info.id,
            iceParameters = info.iceParameters,
            iceCandidates = info.iceCandidates,
            dtlsParameters = info.dtlsParameters,
            sctpParameters = info.sctpParameters,
            appData = info.appData,
            rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        )
    }

    @WorkerThread
    @Throws(ProtooException::class, JSONException::class, MediasoupException::class)
    private suspend fun createRecvTransport() {
        val info = withContext(Dispatchers.IO) {
            Timber.d("createRecvTransport()")
            protoo?.syncRequest("createWebRtcTransport") { req ->
                JsonUtils.jsonPut(req, "forceTcp", options.isForceTcp)
                JsonUtils.jsonPut(req, "producing", false)
                JsonUtils.jsonPut(req, "consuming", true)
                // TODO (HaiyangWu): add sctpCapabilities
                JsonUtils.jsonPut(req, "sctpCapabilities", "")
            }?.let {
                moshi.adapter(CreateWebRtcTransportResponse::class.java).fromJson(it)
            }
        } ?: run {
            Timber.d("createWebRtcTransport failed: response not found")
            return
        }

        Timber.d("device#createRecvTransport() $info")
        recvTransport = mediasoupDevice.createRecvTransport(
            listener = recvTransportListener,
            id = info.id,
            iceParameters = info.iceParameters,
            iceCandidates = info.iceCandidates,
            dtlsParameters = info.dtlsParameters,
            sctpParameters = info.sctpParameters,
            appData = info.appData,
            rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        )
    }

    private val sendTransportListener: SendTransport.Listener = object : SendTransport.Listener {
        private val listenerTAG = TAG + "_SendTrans"
        override fun onProduce(transport: Transport, kind: MediaKind, rtpParameters: RtpParameters, appData: Map<String, Any>?): String {
            if (closed) {
                return ""
            }
            Timber.tag(listenerTAG).d("onProduce() ")
            val producerId = fetchProduceId { req ->
                JsonUtils.jsonPut(req, "transportId", transport.id)
                JsonUtils.jsonPut(req, "kind", kind)
                JsonUtils.jsonPut(req, "rtpParameters", JSONObject(moshi.adapter(RtpParameters::class.java).toJson(rtpParameters)))
                JsonUtils.jsonPut(req, "appData", appData)
            }
            Timber.tag(listenerTAG).d("producerId: %s", producerId)
            return producerId
        }

        override fun onConnect(transport: Transport, dtlsParameters: DtlsParameters) {
            if (closed) {
                return
            }
            Timber.tag(listenerTAG).d("onConnect()")
            protoo?.request("connectWebRtcTransport") { req: JSONObject ->
                JsonUtils.jsonPut(req, "transportId", transport.id)
                JsonUtils.jsonPut(req, "dtlsParameters", JSONObject(moshi.adapter(DtlsParameters::class.java).toJson(dtlsParameters)))
            }?.subscribeBy(
                onNext = { d: String? -> Timber.tag(listenerTAG).d("connectWebRtcTransport res: %s", d) },
                onError = { t: Throwable -> logError("connectWebRtcTransport for mSendTransport failed", t) }
            )?.addTo(compositeDisposable)
        }

        override fun onConnectionStateChange(transport: Transport, newState: PeerConnection.IceConnectionState) {
            Timber.tag(listenerTAG).d("onConnectionStateChange: %s", newState)
        }
    }

    private val recvTransportListener: RecvTransport.Listener = object : RecvTransport.Listener {
        private val listenerTAG = TAG + "_RecvTrans"
        override fun onConnect(transport: Transport, dtlsParameters: DtlsParameters) {
            if (closed) {
                return
            }
            Timber.tag(listenerTAG).d("onConnect()")
            protoo?.request("connectWebRtcTransport") { req ->
                JsonUtils.jsonPut(req, "transportId", transport.id)
                JsonUtils.jsonPut(req, "dtlsParameters", JSONObject(moshi.adapter(DtlsParameters::class.java).toJson(dtlsParameters)))
            }?.subscribeBy(
                onNext = { d: String? -> Timber.tag(listenerTAG).d("connectWebRtcTransport res: %s", d) },
                onError = { t: Throwable -> logError("connectWebRtcTransport for mRecvTransport failed", t) }
            )?.addTo(compositeDisposable)
        }

        override fun onConnectionStateChange(transport: Transport, newState: PeerConnection.IceConnectionState) {
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

    private fun logError(message: String, throwable: Throwable) {
        Timber.e(throwable, message)
    }

    private suspend fun onNewConsumer(request: Message.Request, handler: ServerRequestHandler) {
        if (!options.isConsume) {
            handler.reject(403, "I do not want to consume")
            return
        }
        try {
            val requestJson = request.data.toString()
            val data = withContext(Dispatchers.IO) {
                moshi.adapter(NewConsumerResponse::class.java).fromJson(requestJson)
            } ?: run {
                Timber.e("\"newConsumer\" request failed: can't parse request: \n%s", requestJson)
                store.addNotify("error", "Error creating a Consumer: can't parse request")
                return
            }

            val consumer = recvTransport?.consume(
                consumerListener = object : Consumer.Listener {
                    override fun onTransportClose(consumer: Consumer) {
                        consumers.remove(consumer.id)
                        Timber.w("onTransportClose for consume")
                    }
                },
                id = data.id,
                producerId = data.producerId,
                kind = data.kind,
                rtpParameters = data.rtpParameters,
                appData = data.appData
            ) ?: return

            consumers[consumer.id] = ConsumerHolder(data.peerId, consumer)
            store.addConsumer(data.peerId, data.type, consumer, data.producerPaused)

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
        handler.reject(403, "I do not want to data consume")
        // TODO(HaiyangWu): support data consume
    }

    @WorkerThread
    private fun pauseConsumer(consumer: Consumer) {
        Timber.d("pauseConsumer() %s", consumer.id)
        if (consumer.isPaused) {
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
        if (!consumer.isPaused) {
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