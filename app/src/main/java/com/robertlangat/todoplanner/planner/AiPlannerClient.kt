package com.robertlangat.todoplanner.planner

import com.robertlangat.todoplanner.BuildConfig
import com.robertlangat.todoplanner.core.json.Json
import com.robertlangat.todoplanner.core.planner.AiPlanValidator
import com.robertlangat.todoplanner.core.planner.BusyBlock
import com.robertlangat.todoplanner.core.planner.PlanDraft
import com.robertlangat.todoplanner.core.planner.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/**
 * Talks to the planner backend in /backend. The app never holds a model-provider API key; the
 * backend does. Only the payload built by [buildPayload] is sent, and the UI shows that payload to
 * the user for approval before calling [plan].
 */
class AiPlannerClient {
    sealed interface Outcome {
        data class Ok(val draft: PlanDraft) : Outcome
        data class Error(val message: String) : Outcome
    }

    /** Exactly what leaves the device. Busy blocks are included only if the user opted in. */
    fun buildPayload(
        input: String, date: LocalDate, availability: List<TimeWindow>, busy: List<BusyBlock>, includeBusyTitles: Boolean,
    ): Map<String, Any?> = mapOf(
        "input" to input.take(4000),
        "date" to date.toString(),
        "timezone" to ZoneId.systemDefault().id,
        "availability" to availability.map { mapOf("start" to it.start.toString(), "end" to it.end.toString()) },
        "busy" to busy.map {
            mapOf("start" to it.window.start.toString(), "end" to it.window.end.toString(), "title" to if (includeBusyTitles) it.title else "Busy")
        },
    )

    suspend fun plan(
        backendUrl: String, payload: Map<String, Any?>, date: LocalDate, busy: List<BusyBlock>, availability: List<TimeWindow>,
    ): Outcome = withContext(Dispatchers.IO) {
        val base = backendUrl.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext Outcome.Error("No AI backend is configured. Set one in Settings → AI planner, or use the offline planner.")
        if (!base.startsWith("https://") && !base.startsWith("http://10.0.2.2") && !base.startsWith("http://localhost")) {
            return@withContext Outcome.Error("The AI backend URL must use https://.")
        }
        val conn = try { URL("$base/v1/plan").openConnection() as HttpURLConnection } catch (e: Exception) {
            return@withContext Outcome.Error("The AI backend URL isn't valid.")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (BuildConfig.AI_APP_TOKEN.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.AI_APP_TOKEN}")
            conn.outputStream.use { it.write(Json.stringify(payload).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                code == 429 -> Outcome.Error("The AI planner is busy. Try again shortly, or use the offline planner.")
                code !in 200..299 -> Outcome.Error("The AI planner is unavailable right now ($code). Your tasks are unchanged.")
                else -> Outcome.Ok(AiPlanValidator.validate(body, date, busy, availability))
            }
        } catch (e: SocketTimeoutException) {
            Outcome.Error("The AI planner took too long to answer. Try again or use the offline planner.")
        } catch (e: IOException) {
            Outcome.Error("Couldn't reach the AI planner. Check your connection or use the offline planner.")
        } catch (e: AiPlanValidator.InvalidResponse) {
            Outcome.Error(e.message ?: "The AI planner returned an unusable plan.")
        } finally {
            conn.disconnect()
        }
    }
}
