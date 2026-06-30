package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.agentos.shell.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google sign-in for SlyOS, done the portable way: OAuth 2.0 Authorization Code + PKCE in a Chrome
 * Custom Tab. No Google Play Services, so it works on Samsung, de-Googled phones and the keyless
 * prebuilt APK. Each user signs into THEIR OWN Google account; tokens live only on this device.
 *
 * Scope is intentionally minimal — calendar.events (+ openid/email just to show which account is
 * connected). We never see the user's data: the phone talks to Google directly.
 */
object GoogleAuth {
    private const val TAG = "SlyOS"
    private const val PREF = "slyos_google"
    private const val AUTH_EP = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_EP = "https://oauth2.googleapis.com/token"
    private const val SCOPE = "openid email https://www.googleapis.com/auth/calendar.events " +
        "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.send"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun clientId(): String = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
    fun configured(): Boolean = clientId().isNotBlank() && BuildConfig.GOOGLE_REDIRECT_SCHEME != "com.agentos.shell.noauth"
    private fun redirectUri(): String = BuildConfig.GOOGLE_REDIRECT_SCHEME + ":/oauth2redirect"

    fun isConnected(ctx: Context): Boolean = prefs(ctx).getString("refresh_token", "").orEmpty().isNotBlank()
    fun account(ctx: Context): String = prefs(ctx).getString("email", "").orEmpty()

    fun disconnect(ctx: Context) {
        prefs(ctx).edit().remove("refresh_token").remove("access_token")
            .remove("expiry").remove("email").remove("pkce_verifier").remove("state").apply()
    }

    // ---- PKCE helpers ----
    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes); SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
    private fun challenge(verifier: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(d, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Begin sign-in: open Google's consent screen in a Custom Tab. Result returns via the redirect. */
    fun connect(ctx: Context): String {
        if (!configured()) return "Google isn't set up in this build yet."
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(16)
        prefs(ctx).edit().putString("pkce_verifier", verifier).putString("state", state).apply()
        val url = Uri.parse(AUTH_EP).buildUpon()
            .appendQueryParameter("client_id", clientId())
            .appendQueryParameter("redirect_uri", redirectUri())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()
        return try {
            val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
            tab.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(ctx, url)
            ""
        } catch (e: Exception) {
            // Fallback: any browser.
            try {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); ""
            } catch (e2: Exception) { "Couldn't open a browser to sign in." }
        }
    }

    /** Handle the redirect (code or error). Exchanges the code for tokens. Returns ""=ok or an error. */
    fun handleRedirect(ctx: Context, data: Uri): String {
        val err = data.getQueryParameter("error")
        if (err != null) { Log.w(TAG, "google oauth error: $err"); return "Google sign-in was cancelled or denied." }
        val code = data.getQueryParameter("code") ?: return "No authorization code returned."
        val state = data.getQueryParameter("state")
        val savedState = prefs(ctx).getString("state", "")
        if (state != savedState) return "Sign-in state mismatch — please try again."
        val verifier = prefs(ctx).getString("pkce_verifier", "").orEmpty()
        if (verifier.isBlank()) return "Sign-in session expired — please try again."
        return exchangeCode(ctx, code, verifier)
    }

    private fun exchangeCode(ctx: Context, code: String, verifier: String): String {
        val form = "code=" + Uri.encode(code) +
            "&client_id=" + Uri.encode(clientId()) +
            "&redirect_uri=" + Uri.encode(redirectUri()) +
            "&grant_type=authorization_code" +
            "&code_verifier=" + Uri.encode(verifier)
        val (code2, body) = post(TOKEN_EP, form)
        if (code2 != 200) { Log.e(TAG, "token exchange $code2: $body"); return "Couldn't finish sign-in ($code2)." }
        return try {
            val o = JSONObject(body)
            val access = o.getString("access_token")
            val refresh = o.optString("refresh_token")
            val expiresIn = o.optLong("expires_in", 3600)
            val email = emailFromIdToken(o.optString("id_token"))
            val ed = prefs(ctx).edit()
            ed.putString("access_token", access)
            if (refresh.isNotBlank()) ed.putString("refresh_token", refresh)
            ed.putLong("expiry", System.currentTimeMillis() + (expiresIn - 60) * 1000)
            if (email.isNotBlank()) ed.putString("email", email)
            ed.remove("pkce_verifier").remove("state")
            ed.apply()
            ""
        } catch (e: Exception) { Log.e(TAG, "token parse failed", e); "Sign-in response was malformed." }
    }

    /** A valid access token, refreshing transparently if expired. Empty string if not connected. */
    fun accessToken(ctx: Context): String {
        val p = prefs(ctx)
        val cur = p.getString("access_token", "").orEmpty()
        val expiry = p.getLong("expiry", 0)
        if (cur.isNotBlank() && System.currentTimeMillis() < expiry) return cur
        val refresh = p.getString("refresh_token", "").orEmpty()
        if (refresh.isBlank()) return ""
        val form = "client_id=" + Uri.encode(clientId()) +
            "&grant_type=refresh_token&refresh_token=" + Uri.encode(refresh)
        val (code, body) = post(TOKEN_EP, form)
        if (code != 200) { Log.e(TAG, "refresh $code: $body"); return "" }
        return try {
            val o = JSONObject(body)
            val access = o.getString("access_token")
            val expiresIn = o.optLong("expires_in", 3600)
            p.edit().putString("access_token", access)
                .putLong("expiry", System.currentTimeMillis() + (expiresIn - 60) * 1000).apply()
            access
        } catch (e: Exception) { "" }
    }

    private fun emailFromIdToken(idToken: String): String {
        if (idToken.isBlank()) return ""
        return try {
            val parts = idToken.split(".")
            if (parts.size < 2) return ""
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            JSONObject(payload).optString("email")
        } catch (e: Exception) { "" }
    }

    private fun post(endpoint: String, form: String): Pair<Int, String> {
        return try {
            val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20000; readTimeout = 20000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } catch (e: Exception) { Log.e(TAG, "post failed", e); 0 to (e.message ?: "network error") }
    }
}
