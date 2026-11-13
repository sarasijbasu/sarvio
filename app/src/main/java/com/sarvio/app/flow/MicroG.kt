package com.sarvio.app.flow

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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

    /** Fallback page when microG cannot hand over a web session. */
    const val WEB_SIGN_IN = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F"

    private const val WEB_LOGIN_SCOPE = "weblogin:service=youtube&continue=https://m.youtube.com/"

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
     * Asks microG for a web sign-in link for [accountName]. microG shows its own
     * "Allow Sarvio to use your account?" screen the first time. Returns null when it refuses.
     */
    suspend fun webLoginUrl(activity: Activity, accountName: String): String? =
        suspendCancellableCoroutine { cont ->
            val am = AccountManager.get(activity)
            runCatching {
                am.getAuthToken(Account(accountName, ACCOUNT_TYPE), WEB_LOGIN_SCOPE, null, activity, { f ->
                    val token = runCatching { f.result.getString(AccountManager.KEY_AUTHTOKEN) }.getOrNull()
                    if (cont.isActive) cont.resume(token)
                }, Handler(Looper.getMainLooper()))
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }

    /** Loads the sign-in link in an off-screen WebView so the shared cookie jar gets the session. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun applyWebLogin(context: Context, url: String): Boolean {
        if (!url.startsWith("https://")) return false
        var view: WebView? = null
        withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                val w = WebView(context)
                view = w
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
        view?.destroy()
        CookieManager.getInstance().flush()
        return hasYoutubeSession()
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
