package com.robertlangat.todoplanner.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.robertlangat.todoplanner.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google sign-in + authorization for the Drive app-data folder only
 * (scope drive.appdata: the app can see its own hidden folder, nothing else in the user's Drive).
 * Uses Google Identity Services' AuthorizationClient, which shows the account picker and consent
 * screen when needed. Requires an Android OAuth client for this package + signing SHA-1 in your
 * Google Cloud project (see README "Google Drive sync").
 */
class DriveAuth(private val context: Context, private val settings: SettingsRepository) {
    companion object {
        const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }

    sealed interface Outcome {
        data class Authorized(val accessToken: String) : Outcome
        data class NeedsUserConsent(val pendingIntent: PendingIntent) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE_APPDATA)))
        .build()

    /** Pass an Activity context when you can show UI; returns NeedsUserConsent if a picker is required. */
    suspend fun authorize(ctx: Context = context): Outcome = try {
        val result = Identity.getAuthorizationClient(ctx).authorize(request()).await()
        val pi = result.pendingIntent
        val token = result.accessToken
        when {
            result.hasResolution() && pi != null -> Outcome.NeedsUserConsent(pi)
            token != null -> Outcome.Authorized(token)
            else -> Outcome.Failed("Google didn't return an access token.")
        }
    } catch (e: Exception) {
        Outcome.Failed(e.message ?: "Google sign-in is unavailable on this device.")
    }

    /** Completes the flow after the consent screen returns. */
    fun tokenFromResult(data: Intent?): String? = runCatching {
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
    }.getOrNull()

    /** For background work: a token if the user already granted access, otherwise null. */
    suspend fun silentToken(): String? = (authorize() as? Outcome.Authorized)?.accessToken

    suspend fun signOut() = withContext(Dispatchers.IO) {
        silentToken()?.let { token ->
            runCatching {
                val conn = URL("https://oauth2.googleapis.com/revoke").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write("token=${URLEncoder.encode(token, "UTF-8")}".toByteArray()) }
                conn.responseCode
                conn.disconnect()
            }
        }
        settings.setDriveAccount(false, null)
    }
}
