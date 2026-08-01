package com.fnmusic.tv.core.data.api

import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiEnvelope<T>(val code: Int, val msg: String = "", val data: T? = null)

object ApiDecoder {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    inline fun <reified T> decode(body: String): T {
        val envelope = decodeEnvelope<T>(body)
        return envelope.data ?: throw AppException(AppError.Empty)
    }

    inline fun <reified T> decodeNullable(body: String): T? {
        val envelope = try {
            json.decodeFromString<ApiEnvelope<T>>(body)
        } catch (cause: Exception) {
            throw AppException(AppError.Unknown("invalid_json"), cause)
        }
        if (envelope.code != 0) throw AppException(mapCode(envelope.code))
        return envelope.data
    }

    fun decodeUnit(body: String) {
        decodeEnvelope<JsonElement>(body)
    }

    @PublishedApi
    internal inline fun <reified T> decodeEnvelope(body: String): ApiEnvelope<T> {
        val envelope = try {
            json.decodeFromString<ApiEnvelope<T>>(body)
        } catch (cause: Exception) {
            throw AppException(AppError.Unknown("invalid_json"), cause)
        }
        if (envelope.code != 0) throw AppException(mapCode(envelope.code))
        return envelope
    }

    fun mapCode(code: Int): AppError = when (code) {
        99999, 120001 -> AppError.Unauthenticated
        120002 -> AppError.AccountDisabled
        100004 -> AppError.UnavailableTrack
        100005 -> AppError.NotFound
        else -> AppError.Unknown("api_$code")
    }
}
