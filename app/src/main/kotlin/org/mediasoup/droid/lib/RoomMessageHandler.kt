package org.mediasoup.droid.lib

import androidx.annotation.WorkerThread
import io.github.crow_misia.mediasoup.Consumer
import io.github.crow_misia.mediasoup.DataConsumer
import org.json.JSONException
import org.mediasoup.droid.lib.lv.RoomStore
import org.protoojs.droid.Message
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

open class RoomMessageHandler(
    // Stored Room States.
    protected val store: RoomStore
) {
    // mediasoup Consumers.
    internal val consumers = ConcurrentHashMap<String, ConsumerHolder>()

    // mediasoup DataConsumers.
    internal val dataConsumers = ConcurrentHashMap<String, DataConsumerHolder>()

    internal class ConsumerHolder(val peerId: String, val consumer: Consumer)
    internal class DataConsumerHolder(val peerId: String, val dataConsumer: DataConsumer)

    @WorkerThread
    @Throws(JSONException::class)
    fun handleNotification(notification: Message.Notification) {
        val data = notification.data
        when (notification.method) {
            "producerScore" -> {
                // {"producerId":"bdc2e83e-5294-451e-a986-a29c7d591d73","score":[{"score":10,"ssrc":196184265}]}
                val producerId = data.getString("producerId")
                val score = data.getJSONArray("score")
                store.setProducerScore(producerId, score)
            }
            "newPeer" -> {
                val id = data.getString("id")
                val displayName = data.optString("displayName")
                store.addPeer(id, data)
                store.addNotify("$displayName has joined the room")
            }
            "peerClosed" -> {
                val peerId = data.getString("peerId")
                store.removePeer(peerId)
            }
            "peerDisplayNameChanged" -> {
                val peerId = data.getString("peerId")
                val displayName = data.optString("displayName")
                val oldDisplayName = data.optString("oldDisplayName")
                store.setPeerDisplayName(peerId, displayName)
                store.addNotify("$oldDisplayName is now $displayName")
            }
            "consumerClosed" -> {
                val consumerId = data.getString("consumerId")
                consumers.remove(consumerId)?.also {
                    it.consumer.close()
                    store.removeConsumer(it.peerId, it.consumer.id)
                }
            }
            "consumerPaused" -> {
                val consumerId = data.getString("consumerId")
                consumers[consumerId]?.also {
                    store.setConsumerPaused(it.consumer.id, "remote")
                }
            }
            "consumerResumed" -> {
                val consumerId = data.getString("consumerId")
                consumers[consumerId]?.also {
                    store.setConsumerResumed(it.consumer.id, "remote")
                }
            }
            "consumerLayersChanged" -> {
                val consumerId = data.getString("consumerId")
                val spatialLayer = data.optInt("spatialLayer")
                val temporalLayer = data.optInt("temporalLayer")
                consumers[consumerId]?.also {
                    store.setConsumerCurrentLayers(consumerId, spatialLayer, temporalLayer)
                }
            }
            "consumerScore" -> {
                val consumerId = data.getString("consumerId")
                val score = data.optJSONArray("score")
                consumers[consumerId]?.also {
                    store.setConsumerScore(consumerId, score)
                }
            }
            "dataConsumerClosed" -> {
                val dataConsumerId = data.getString("dataConsumerId")
                dataConsumers.remove(dataConsumerId)?.also {
                    it.dataConsumer.close()
                    store.removeDataConsumer(it.peerId, it.dataConsumer.id)
                }
            }
            "activeSpeaker" -> {
                val peerId = data.getString("peerId")
                store.setRoomActiveSpeaker(peerId)
            }
            "downlinkBwe" -> {
            }
            else -> Timber.e("unknown protoo notification.method %s", notification.method)
        }
    }

    companion object {
        const val TAG = "RoomClient"
    }
}