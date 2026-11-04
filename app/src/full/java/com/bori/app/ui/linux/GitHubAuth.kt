package com.bori.app.ui.linux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.bori.app.BuildConfig
import com.bori.core.linux.DistroInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** Where the GitHub browser sign-in is. */
sealed interface GitHubAuthState {
    data object Idle : GitHubAuthState
    /** The approve page is open in the browser. */
    data class Waiting(val distroId: String) : GitHubAuthState
    /** GitHub sent the user back; swapping the code for a token and handing it to Linux. */
    data class Finishing(val distroId: String) : GitHubAuthState
    data class Failed(val distroId: String, val message: String) : GitHubAuthState
}

/**
 * GitHub sign-in the way VS Code and GitHub Desktop do it: GitHub's own authorize page in the
 * browser, which sends the user back to `bori-code://github-auth` ([GitHubAuthCallbackActivity]).
 * The code is swapped for a token (PKCE + the app's client secret, which GitHub treats as not
 * confidential for installed apps) and the token goes straight to gh inside Linux. Bori Code
 * never stores it.
 */
@Singleton
class GitHubAuth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: DistroInstaller,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
    private val random = SecureRandom()

    private val _state = MutableStateFlow<GitHubAuthState>(GitHubAuthState.Idle)
    val state: StateFlow<GitHubAuthState> = _state.asStateFlow()

    /** One sign-in at a time; replaced by a new [start]. Memory only. */
    private var pending: Pending? = null

    private class Pending(val distroId: String, val state: String, val verifier: String)

    /** False in builds made without github-oauth.properties: they use gh's code sign-in. */
    val available: Boolean get() = BuildConfig.GITHUB_CLIENT_ID.isNotEmpty() && BuildConfig.GITHUB_CLIENT_SECRET.isNotEmpty()

    /** Opens GitHub's authorize page. [switchAccount] asks GitHub to show its account picker. */
    fun start(distroId: String, switchAccount: Boolean = false) {
        val p = Pending(distroId, randomToken(), randomToken())
        pending = p
        val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.GITHUB_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", p.state)
            .appendQueryParameter("code_challenge", challenge(p.verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .apply { if (switchAccount) appendQueryParameter("prompt", "select_account") }
            .build()
        _state.value = GitHubAuthState.Waiting(distroId)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            pending = null
            _state.value = GitHubAuthState.Failed(distroId, "No browser to open GitHub's sign-in page.")
        }
    }

    /** The browser came back to Bori Code without finishing (Back, closed tab): stop waiting. */
    fun cancelIfWaiting() {
        if (_state.value is GitHubAuthState.Waiting) {
            pending = null
            _state.value = GitHubAuthState.Idle
        }
    }

    fun dismiss() {
        if (_state.value is GitHubAuthState.Failed) _state.value = GitHubAuthState.Idle
    }

    /** GitHub's redirect to [REDIRECT_URI]. Anything that doesn't match the pending sign-in is ignored. */
    fun onRedirect(uri: Uri) {
        val p = pending ?: return
        if (uri.getQueryParameter("state") != p.state) {
            Log.w(TAG, "Ignored a GitHub redirect with the wrong state")
            return
        }
        pending = null
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        if (error != null || code == null) {
            _state.value = if (error == "access_denied") GitHubAuthState.Idle
            else GitHubAuthState.Failed(p.distroId, uri.getQueryParameter("error_description") ?: "GitHub didn't sign you in.")
            return
        }
        _state.value = GitHubAuthState.Finishing(p.distroId)
        scope.launch {
            val token = runCatching { exchange(code, p.verifier) }
                .onFailure { Log.w(TAG, "GitHub token exchange failed: ${it.message}") }
                .getOrNull()
            if (token == null) {
                _state.value = GitHubAuthState.Failed(p.distroId, "Couldn't finish the GitHub sign-in. Check your connection and try again.")
                return@launch
            }
            val login = installer.signInGitHub(p.distroId, token)
            _state.value = if (login != null) GitHubAuthState.Idle
            else GitHubAuthState.Failed(p.distroId, "Linux couldn't save the GitHub sign-in. Try \"Use a code instead\".")
        }
    }

    /** Swaps [code] for an access token. Never logs the response body. */
    private fun exchange(code: String, verifier: String): String {
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.GITHUB_CLIENT_ID)
            .add("client_secret", BuildConfig.GITHUB_CLIENT_SECRET)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()
        val request = Request.Builder().url(TOKEN_URL).header("Accept", "application/json").post(body).build()
        http.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string().orEmpty())
            json.optString("error").takeIf { it.isNotEmpty() }?.let { error(it) }
            return json.getString("access_token")
        }
    }

    private fun randomToken(): String =
        ByteArray(32).also(random::nextBytes).let { Base64.encodeToString(it, BASE64_URL) }

    private fun challenge(verifier: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), BASE64_URL)

    companion object {
        private const val TAG = "GitHubAuth"
        const val REDIRECT_URI = "bori-code://github-auth"
        private const val AUTHORIZE_URL = "https://github.com/login/oauth/authorize"
        private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        // gh needs repo + read:org to accept a token; workflow lets agents push CI files.
        private const val SCOPES = "repo workflow read:org gist"
        private const val BASE64_URL = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    }
}
