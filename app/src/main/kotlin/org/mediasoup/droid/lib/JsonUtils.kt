package org.mediasoup.droid.lib

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

object JsonUtils {
    fun jsonPut(json: JSONObject, key: String, value: Any?) {
        try {
            json.put(key, value)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    fun toJsonObject(data: String): JSONObject {
        return try {
            JSONObject(data)
        } catch (e: JSONException) {
            e.printStackTrace()
            JSONObject()
        }
    }

    fun toJsonArray(data: String): JSONArray {
        return try {
            JSONArray(data)
        } catch (e: JSONException) {
            e.printStackTrace()
            JSONArray()
        }
    }
}

@OptIn(ExperimentalContracts::class)
fun String?.toJsonObject(): JSONObject {
    return this?.let { JsonUtils.toJsonObject(it) } ?: JSONObject()
}