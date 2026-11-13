package com.sarvio.app.flow

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google sign-in for the Flow (YouTube) section through microG RE
 * (https://github.com/MorpheApp/MicroG-RE). Sarvio never sees the password: microG owns the
 * account, and hands over a one-time web sign-in link that sets the YouTube cookies.
 */
object MicroG {
    /** MicroG RE is built with base package "app.revanced". */
    const val PACKAGE = "app.revanced.android.gms"
    const val ACCOUNT_TYPE = "app.revanced"
    const val RELEASES_URL = "https://github.com/MorpheApp/MicroG-RE/releases/latest"
    const val YOUTUBE = "https://www.youtube.com"

    /** Fallback page when microG cannot hand over a web session (email filled in when known). */
    fun webSignInUrl(email: String?): String =
        "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F" +
            (email?.let { "&Email=" + Uri.encode(it) } ?: "")

    private const val WEB_LOGIN_SCOPE = "weblogin:service=youtube&continue=https://m.youtube.com/"
    /** Scope Chrome uses to turn the phone's Google account into browser cookies. */
    private const val OAUTH_LOGIN_SCOPE = "oauth2:https://www.google.com/accounts/OAuthLogin"
    private const val YT_PASSIVE_LOGIN =
        "https://accounts.google.com/ServiceLogin?service=youtube&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3D%252F"

    // microG's AccountAuthenticator option keys: ask for a token on behalf of another installed app.
    private const val KEY_OVERRIDE_PACKAGE = "overridePackage"
    private const val KEY_OVERRIDE_CERTIFICATE = "overrideCertificate"

    /**
     * Google only hands web sessions to its own apps, so microG is asked (with your one-time
     * approval in microG's own screen) to request them as Chrome or YouTube when installed.
     */
    private val IDENTITIES = listOf("com.android.chrome", "com.google.android.youtube")

    data class SignInResult(val ok: Boolean, val detail: String?)

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    }.getOrDefault(false)

    /** Opens the microG RE download page in the phone's browser. */
    fun openDownloadPage(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** System account picker limited to microG accounts; it also offers "Add account". */
    fun chooseAccountIntent(): Intent =
        AccountManager.newChooseAccountIntent(null, null, arrayOf(ACCOUNT_TYPE), null, null, null, null)

    /**
     * Signs the shared WebView cookie jar into YouTube with the microG account. Tries, in order:
     * Chrome's route (OAuthLogin token -> uberauth -> MergeSession) and the web sign-in link,
     * first as Chrome / YouTube, then as Sarvio itself.
     */
    suspend fun signIn(activity: Activity, accountName: String): SignInResult {
        val account = Account(accountName, ACCOUNT_TYPE)
        var lastError: String? = null
        val identities: List<Pair<String, ByteArray>?> =
            IDENTITIES.mapNotNull { pkg -> certificateOf(activity, pkg)?.let { pkg to it } } + listOf(null)
        for (identity in identities) {
            // 1) Chrome-style: an OAuthLogin access token is exchanged for browser cookies.
            val (oauthToken, oauthError) = token(activity, account, OAUTH_LOGIN_SCOPE, identity)
            if (oauthToken != null) {
                val (code, codeError) = withContext(Dispatchers.IO) { uberAuth(oauthToken) }
                if (code != null) {
                    val merge = "https://accounts.google.com/MergeSession?uberauth=" + Uri.encode(code) +
                        "&continue=" + Uri.encode("https://m.youtube.com/") + "&source=ChromiumBrowser"
                    if (applyWebLogin(activity, merge)) return SignInResult(true, null)
                    lastError = "Google accepted the account but YouTube didn't open a session"
                } else lastError = codeError
            } else if (oauthError != null) lastError = oauthError

            // 2) One-time web sign-in link.
            val (webLink, webError) = token(activity, account, WEB_LOGIN_SCOPE, identity)
            if (webLink != null && webLink.startsWith("https://")) {
                if (applyWebLogin(activity, webLink)) return SignInResult(true, null)
            } else if (webError != null) lastError = webError
        }
        return SignInResult(hasYoutubeSession(), lastError)
    }

    /** Returns (token, error). microG shows its own approval screens through [activity] when needed. */
    private suspend fun token(
        activity: Activity, account: Account, scope: String, identity: Pair<String, ByteArray>?,
    ): Pair<String?, String?> = suspendCancellableCoroutine { cont ->
        val options = Bundle()
        if (identity != null) {
            options.putString(KEY_OVERRIDE_PACKAGE, identity.first)
            options.putByteArray(KEY_OVERRIDE_CERTIFICATE, identity.second)
        }
        runCatching {
            AccountManager.get(activity).getAuthToken(account, scope, options, activity, { f ->
                val r = runCatching { f.result.getString(AccountManager.KEY_AUTHTOKEN) }
                val result: Pair<String?, String?> = r.getOrNull() to r.exceptionOrNull()?.let { describe(it) }
                if (cont.isActive) cont.resume(result)
            }, Handler(Looper.getMainLooper()))
        }.onFailure { if (cont.isActive) cont.resume(Pair<String?, String?>(null, describe(it))) }
    }

    private fun describe(t: Throwable): String = when (t) {
        is android.accounts.OperationCanceledException -> "Sign-in was cancelled in microG"
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    /** OAuthLogin access token -> one-time "uberauth" code (same call Chrome makes). Returns (code, error). */
    private fun uberAuth(accessToken: String): Pair<String?, String?> = try {
        val c = URL("https://accounts.google.com/OAuthLogin?source=ChromiumBrowser&issueuberauth=1")
            .openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $accessToken")
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        try {
            if (c.responseCode != 200) {
                Pair(null, "Google refused the token (HTTP ${c.responseCode})")
            } else {
                val body = c.inputStream.bufferedReader().use { it.readText() }.trim()
                if (body.isEmpty() || body.contains(' ') || body.contains('<')) Pair(null, "Unexpected reply from Google")
                else Pair(body, null)
            }
        } finally {
            c.disconnect()
        }
    } catch (t: Throwable) {
        Pair(null, t.message ?: "Network error")
    }

    /** Signing certificate of an installed app, or null when it isn't installed. */
    @Suppress("DEPRECATION")
    private fun certificateOf(context: Context, pkg: String): ByteArray? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            val sigs = when {
                info == null -> null
                info.hasMultipleSigners() -> info.apkContentsSigners
                else -> info.signingCertificateHistory
            }
            sigs?.firstOrNull()?.toByteArray()
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        }
    }.getOrNull()

    /**
     * Loads a sign-in link in an off-screen WebView so the shared cookie jar gets the session.
     * If Google is signed in but YouTube isn't yet, a passive YouTube sign-in finishes it.
     */
    suspend fun applyWebLogin(context: Context, url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val w = WebView(context)
        try {
            loadUntilYoutube(w, url)
            if (!hasYoutubeSession()) loadUntilYoutube(w, YT_PASSIVE_LOGIN)
        } finally {
            w.destroy()
            CookieManager.getInstance().flush()
        }
        return hasYoutubeSession()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadUntilYoutube(w: WebView, url: String) {
        withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String) {
                        val host = Uri.parse(u).host.orEmpty()
                        if (host.endsWith("youtube.com") && cont.isActive) cont.resume(Unit)
                    }
                }
                w.loadUrl(url)
            }
        }
    }

    fun hasYoutubeSession(): Boolean {
        val c = CookieManager.getInstance().getCookie(YOUTUBE).orEmpty()
        return c.contains("SAPISID") || c.contains("LOGIN_INFO") || c.contains("__Secure-3PSID")
    }

    /** Signs the Flow section out by expiring the YouTube and Google session cookies. */
    fun clearYoutubeSession() {
        val cm = CookieManager.getInstance()
        for ((url, domain) in listOf(YOUTUBE to ".youtube.com", "https://accounts.google.com" to ".google.com")) {
            cm.getCookie(url).orEmpty().split(";").map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.forEach { name ->
                cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$domain")
                cm.setCookie(url, "$name=; Max-Age=0; Path=/")
            }
        }
        cm.flush()
    }
}
