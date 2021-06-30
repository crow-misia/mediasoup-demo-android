package org.mediasoup.droid.lib.model

import io.github.crow_misia.mediasoup.DataProducer
import io.github.crow_misia.mediasoup.Producer
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

class DataProducers {
    class DataProducersWrapper internal constructor(val dataProducer: DataProducer)

    private val dataProducers = ConcurrentHashMap<String, DataProducersWrapper>()

    fun addDataProducer(dataProducer: DataProducer) {
        dataProducers[dataProducer.id] = DataProducersWrapper(dataProducer)
    }

    fun removeDataProducer(producerId: String) {
        dataProducers.remove(producerId)
    }

    fun clear() {
        dataProducers.clear()
    }
}