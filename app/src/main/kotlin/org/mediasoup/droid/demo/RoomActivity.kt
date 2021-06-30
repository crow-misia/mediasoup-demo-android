package org.mediasoup.droid.demo

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.get
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.crow_misia.webrtc.camera.CameraCapturerFactory
import io.github.crow_misia.webrtc.option.MediaConstraintsOption
import org.mediasoup.droid.demo.adapter.PeerAdapter
import org.mediasoup.droid.demo.databinding.ActivityRoomBinding
import org.mediasoup.droid.demo.utils.ClipboardCopy
import org.mediasoup.droid.demo.vm.EdiasProps
import org.mediasoup.droid.demo.vm.MeProps
import org.mediasoup.droid.demo.vm.RoomProps
import org.mediasoup.droid.lib.PeerConnectionUtils
import org.mediasoup.droid.lib.RoomClient
import org.mediasoup.droid.lib.RoomOptions
import org.mediasoup.droid.lib.Utils
import org.mediasoup.droid.lib.lv.RoomStore
import permissions.dispatcher.*
import timber.log.Timber

@RuntimePermissions
class RoomActivity : AppCompatActivity() {
    private val preferences: SharedPreferences by lazy {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
    }

    private var roomId: String? = null
    private var peerId: String? = null
    private var displayName: String? = null
    private var forceH264 = false
    private var forceVP9 = false
    private var cameraName = ""
    private lateinit var options: RoomOptions
    private lateinit var roomStore: RoomStore
    private var roomClient: RoomClient? = null
    private lateinit var binding: ActivityRoomBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_room)
        createRoom()
    }

    private fun createRoom() {
        options = RoomOptions()
        loadRoomConfig()
        roomStore = RoomStore()
        initRoomClient()
        viewModelStore.clear()
        initViewModel()

        joinRoomWithPermissionCheck()
    }

    @NeedsPermission(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    fun joinRoom() {
        Timber.d("permission granted")
        roomClient?.join()
    }

    @OnShowRationale(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    fun showRationale(request: PermissionRequest) {
        showRationaleDialog(R.string.permission_rationale, request)
    }

    @OnPermissionDenied(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    fun onPermissionDenied() {
        Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
    }

    @OnNeverAskAgain(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    fun onNeverAskAgain() {
        Toast.makeText(this, R.string.permission_never_ask_again, Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // NOTE: delegate the permission handling to generated function
        onRequestPermissionsResult(requestCode, grantResults)
    }

    private fun showRationaleDialog(@StringRes messageResId: Int, request: PermissionRequest) {
        AlertDialog.Builder(this)
            .setPositiveButton(R.string.button_allow) { _, _ -> request.proceed() }
            .setNegativeButton(R.string.button_deny) { _, _ -> request.cancel() }
            .setCancelable(false)
            .setMessage(messageResId)
            .show()
    }

    private fun loadRoomConfig() {
        // Room initial config.
        roomId = preferences.getString("roomId", "")
        peerId = preferences.getString("peerId", "")
        displayName = preferences.getString("displayName", "")
        forceH264 = preferences.getBoolean("forceH264", false)
        forceVP9 = preferences.getBoolean("forceVP9", false)
        if (TextUtils.isEmpty(roomId)) {
            roomId = Utils.getRandomString(8)
            preferences.edit().putString("roomId", roomId).apply()
        }
        if (TextUtils.isEmpty(peerId)) {
            peerId = Utils.getRandomString(8)
            preferences.edit().putString("peerId", peerId).apply()
        }
        if (TextUtils.isEmpty(displayName)) {
            displayName = Utils.getRandomString(8)
            preferences.edit().putString("displayName", displayName).apply()
        }

        // Room action config.
        options.isProduce = preferences.getBoolean("produce", true)
        options.isConsume = preferences.getBoolean("consume", true)
        options.isForceTcp = preferences.getBoolean("forceTcp", false)
        options.isUseDataChannel = preferences.getBoolean("useDataChannel", true)

        // Device config.
        cameraName = preferences.getString("camera", "front") ?: ""
    }

    private fun initRoomClient() {
        val roomId = roomId
        val peerId = peerId
        val displayName = displayName

        if (roomId.isNullOrEmpty() || peerId.isNullOrEmpty() || displayName.isNullOrEmpty()) {
            finish()
            return
        }

        val camCapturer = if (options.isProduce) {
            CameraCapturerFactory.create(
                this,
                fixedResolution = false,
                preferenceFrontCamera = "front" == cameraName
            )
        } else null

        roomClient = RoomClient(
            context = this,
            store = roomStore,
            roomId = roomId,
            peerId = peerId,
            displayName = displayName,
            forceH264 = forceH264,
            forceVP9 = forceVP9,
            options = options,
            camCapturer = camCapturer,
            mediaConstraintsOption = MediaConstraintsOption().also {
                it.enableAudioDownstream()
                it.enableAudioUpstream()
                it.enableVideoDownstream(PeerConnectionUtils.eglContext)
                camCapturer?.also { capturer ->
                    it.enableVideoUpstream(capturer, PeerConnectionUtils.eglContext)
                }
            }
        )
    }

    private fun initViewModel() {
        val client = requireNotNull(roomClient)
        val factory = EdiasProps.Factory(application, roomStore)

        // Room.
        val roomProps: RoomProps = ViewModelProvider(this, factory).get()
        roomProps.connect(this)
        binding.invitationLink.setOnClickListener {
            val linkUrl = roomProps.invitationLink.get()
            ClipboardCopy.clipboardCopy(application, linkUrl, R.string.invite_link_copied)
        }
        binding.roomProps = roomProps

        // Me.
        val meProps: MeProps = ViewModelProvider(this, factory).get()
        meProps.connect(this)
        binding.me.setProps(meProps, client)
        binding.hideVideos.setOnClickListener {
            meProps.me.get()?.also {
                if (it.isAudioOnly) {
                    roomClient?.disableAudioOnly()
                } else {
                    roomClient?.enableAudioOnly()
                }
            }
        }
        binding.muteAudio.setOnClickListener {
            meProps.me.get()?.also {
                if (it.isAudioMuted) {
                    roomClient?.unmuteAudio()
                } else {
                    roomClient?.muteAudio()
                }
            }
        }
        binding.chatInput.setOnEditorActionListener(
            TextView.OnEditorActionListener { textView: TextView, actionId: Int, _: KeyEvent? ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    roomClient?.sendChatMessage(textView.text.toString())
                    textView.text = ""
                    return@OnEditorActionListener true
                }
                false
            }
        )
        binding.restartIce.setOnClickListener { client.restartIce() }

        // Peers.
        val peerAdapter = PeerAdapter(roomStore, this, client)
        binding.remotePeers.layoutManager = LinearLayoutManager(this)
        binding.remotePeers.adapter = peerAdapter
        roomStore.peers.observe(this) {
            val peersList = it.allPeers
            if (peersList.isEmpty()) {
                binding.remotePeers.visibility = View.GONE
                binding.roomState.visibility = View.VISIBLE
            } else {
                binding.remotePeers.visibility = View.VISIBLE
                binding.roomState.visibility = View.GONE
            }
            peerAdapter.replacePeers(peersList)
        }

        // Notify
        roomStore.notify.observe(this) {
            val text = SpannableStringBuilder.valueOf(it.text)
            if ("error" == it.type) {
                text.setSpan(ForegroundColorSpan(Color.RED), 0, text.length, 0)
            }
            Toast.makeText(this, text, it.timeout).show()
        }
    }

    private fun destroyRoom() {
        roomClient?.close()
        roomClient = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.room_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle item selection
        return if (item.itemId == R.id.setting) {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivityForResult(intent, REQUEST_CODE_SETTING)
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_SETTING) {
            Timber.d("request config done")
            // close, dispose room related and clear store.
            destroyRoom()
            // local config and reCreate room related.
            createRoom()
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        destroyRoom()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CODE_SETTING = 1
    }
}