package org.mediasoup.droid.lib.model

import io.github.crow_misia.mediasoup.DataConsumer
import java.util.concurrent.ConcurrentHashMap

class DataConsumers {
    class DataConsumerWrapper internal constructor(val dataConsumer: DataConsumer)

    private val dataConsumers = ConcurrentHashMap<String, DataConsumerWrapper>()

    fun addDataConsumer(dataConsumer: DataConsumer) {
        dataConsumers[dataConsumer.id] = DataConsumerWrapper(dataConsumer)
    }

    fun removeDataConsumer(consumerId: String) {
        dataConsumers.remove(consumerId)
    }

    fun getDataConsumer(dataConsumerId: String): DataConsumerWrapper? {
        return dataConsumers[dataConsumerId]
    }

    fun clear() {
        dataConsumers.clear()
    }
}