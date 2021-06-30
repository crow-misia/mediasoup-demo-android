package org.mediasoup.droid.lib.model

import io.github.crow_misia.mediasoup.Consumer
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

class Consumers {
    class ConsumerWrapper internal constructor(
        val type: String,
        var isRemotelyPaused: Boolean,
        val consumer: Consumer
    ) {
        var isLocallyPaused = false
        var spatialLayer: Int = -1
        var temporalLayer: Int = -1
        var score: JSONArray? = null
        var preferredSpatialLayer: Int = -1
        var preferredTemporalLayer: Int = -1
    }

    private val consumers = ConcurrentHashMap<String, ConsumerWrapper>()

    fun addConsumer(type: String, consumer: Consumer, remotelyPaused: Boolean) {
        consumers[consumer.id] = ConsumerWrapper(type, remotelyPaused, consumer)
    }

    fun removeConsumer(consumerId: String) {
        consumers.remove(consumerId)
    }

    fun setConsumerPaused(consumerId: String, originator: String) {
        consumers[consumerId]?.also {
            if ("local" == originator) {
                it.isLocallyPaused = true
            } else {
                it.isRemotelyPaused = true
            }
        }
    }

    fun setConsumerResumed(consumerId: String, originator: String) {
        consumers[consumerId]?.also {
            if ("local" == originator) {
                it.isLocallyPaused = false
            } else {
                it.isRemotelyPaused = false
            }
        }
    }

    fun setConsumerCurrentLayers(consumerId: String, spatialLayer: Int, temporalLayer: Int) {
        consumers[consumerId]?.also {
            it.spatialLayer = spatialLayer
            it.temporalLayer = temporalLayer
        }
    }

    fun setConsumerScore(consumerId: String, score: JSONArray?) {
        consumers[consumerId]?.score = score
    }

    fun getConsumer(consumerId: String): ConsumerWrapper? {
        return consumers[consumerId]
    }

    fun clear() {
        consumers.clear()
    }
}