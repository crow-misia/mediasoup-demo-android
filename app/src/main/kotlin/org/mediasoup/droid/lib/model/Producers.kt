package org.mediasoup.droid.lib.model

import io.github.crow_misia.mediasoup.Producer
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

class Producers {
    class ProducersWrapper internal constructor(val producer: Producer) {
        var score: JSONArray? = null
        var type: String? = null

        companion object {
            const val TYPE_CAM = "cam"
            const val TYPE_SHARE = "share"
        }
    }

    private val producers = ConcurrentHashMap<String, ProducersWrapper>()

    fun addProducer(producer: Producer) {
        producers[producer.id] = ProducersWrapper(producer)
    }

    fun removeProducer(producerId: String) {
        producers.remove(producerId)
    }

    fun setProducerPaused(producerId: String) {
        producers[producerId]?.producer?.pause()
    }

    fun setProducerResumed(producerId: String) {
        producers[producerId]?.producer?.resume()
    }

    fun setProducerScore(producerId: String, score: JSONArray?) {
        producers[producerId]?.score = score
    }

    fun filter(kind: String): ProducersWrapper? {
        return producers.values.find { kind == it.producer.track?.kind() }
    }

    fun clear() {
        producers.clear()
    }
}