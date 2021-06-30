package org.mediasoup.droid.lib.model

import io.github.crow_misia.mediasoup.Consumer
import io.github.crow_misia.mediasoup.DataConsumer
import org.json.JSONObject
import timber.log.Timber
import java.util.*

class Peers {
    private val peersInfo = Collections.synchronizedMap(LinkedHashMap<String, Peer>())

    fun addPeer(peerId: String, peerInfo: JSONObject) {
        peersInfo[peerId] = Peer(peerInfo)
    }

    fun removePeer(peerId: String) {
        peersInfo.remove(peerId)
    }

    fun setPeerDisplayName(peerId: String, displayName: String) {
        val peer = peersInfo[peerId] ?: run {
            Timber.e("no Peer found")
            return
        }
        peer.displayName = displayName
    }

    fun addConsumer(peerId: String, consumer: Consumer) {
        val peer = getPeer(peerId) ?: run {
            Timber.e("no Peer found for new Consumer")
            return
        }
        peer.consumers.add(consumer.id)
    }

    fun removeConsumer(peerId: String, consumerId: String) {
        val peer = getPeer(peerId) ?: return
        peer.consumers.remove(consumerId)
    }

    fun addDataConsumer(peerId: String, dataConsumer: DataConsumer) {
        val peer = getPeer(peerId) ?: run {
            Timber.e("no Peer found for new DataConsumer")
            return
        }
        peer.dataConsumers.add(dataConsumer.id)
    }

    fun removeDataConsumer(peerId: String, dataConsumerId: String) {
        val peer = getPeer(peerId) ?: return
        peer.dataConsumers.remove(dataConsumerId)
    }

    fun getPeer(peerId: String): Peer? {
        return peersInfo[peerId]
    }

    val allPeers: List<Peer>
        get() = peersInfo.values.toList()

    fun clear() {
        peersInfo.clear()
    }
}