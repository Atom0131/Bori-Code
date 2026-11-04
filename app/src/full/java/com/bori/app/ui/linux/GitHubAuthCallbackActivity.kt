package com.bori.app.ui.linux

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives GitHub's redirect to `bori-code://github-auth`, hands it to [GitHubAuth] and brings
 * Bori Code back to the front. No UI of its own.
 */
@AndroidEntryPoint
class GitHubAuthCallbackActivity : ComponentActivity() {

    @Inject lateinit var auth: GitHubAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(auth::onRedirect)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
        finish()
    }
}
