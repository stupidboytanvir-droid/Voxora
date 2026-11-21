package com.voxora.player.data.support

import android.os.Build
import com.voxora.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class SupportCategory(val label: String) {
    BUG_CRASH("Bug / Crash"),
    SUBTITLES("Subtitles"),
    PLAYBACK_AUDIO("Playback / Audio"),
    FEATURE_REQUEST("Feature Request"),
    OTHER("Other")
}

sealed interface SupportSubmitResult {
    data object Success : SupportSubmitResult
    data class Error(val userMessage: String) : SupportSubmitResult
}

/**
 * Sends in-app VOXORA Help & Support reports asynchronously over HTTPS without exposing
 * any developer email address in the UI or opening an external email app.
 */
class SupportFeedbackClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    suspend fun submitReport(
        category: SupportCategory,
        message: String,
        userName: String
    ): SupportSubmitResult {
        val cleanedMessage = message.trim()
        if (cleanedMessage.isBlank()) {
            return SupportSubmitResult.Error("Please enter a message before sending.")
        }

        val payloadJson = buildPayloadJson(
            category = category.label,
            message = cleanedMessage,
            userName = userName.trim(),
            appVersion = BuildConfig.VERSION_NAME,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidVersion = "Android ${Build.VERSION.RELEASE}"
        )

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = payloadJson.toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url(ENDPOINT_URL)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "VOXORA-Android/${BuildConfig.VERSION_NAME}")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        SupportSubmitResult.Success
                    } else {
                        SupportSubmitResult.Error(
                            "Could not send your report right now (code ${response.code}). Please try again."
                        )
                    }
                }
            } catch (_: IOException) {
                SupportSubmitResult.Error(
                    "No internet connection. Please connect to the internet and try again."
                )
            } catch (_: Exception) {
                SupportSubmitResult.Error(
                    "Unable to submit your report right now. Please try again shortly."
                )
            }
        }
    }

    companion object {
        private const val ENDPOINT_URL = "https://formsubmit.co/ajax/stupidboytanvir@gmail.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val SUCCESS_CONFIRMATION_MESSAGE =
            "Thank you! Your report has been sent to the VOXORA team."

        fun buildPayloadJson(
            category: String,
            message: String,
            userName: String,
            appVersion: String,
            device: String,
            androidVersion: String
        ): String {
            return buildJsonObject {
                put("category", category)
                put("message", message.trim())
                put("user_name", userName.trim().ifBlank { "Anonymous" })
                put("_subject", "VOXORA Support: $category")
                put("app_version", appVersion)
                put("device", device)
                put("android", androidVersion)
            }.toString()
        }
    }
}
