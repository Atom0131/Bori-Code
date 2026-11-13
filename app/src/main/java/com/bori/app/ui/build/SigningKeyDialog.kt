package com.bori.app.ui.build

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bori.core.build.steps.KeystoreCreator
import com.bori.theme.util.BoriAlertDialog
import java.io.File

/** Google's page on app signing and upload keys, for "Learn more". */
const val APP_SIGNING_HELP_URL = "https://developer.android.com/studio/publish/app-signing"

/**
 * Makes a release signing key on the phone (Settings → Release signing, or the Build sheet when a
 * release has none). [onCreate] does the work off the main thread and reports the saved file. After
 * that the dialog asks the user to back the key up: without it they can't update their app.
 */
@Composable
fun CreateSigningKeyDialog(
    onCreate: (name: String, password: String, owner: KeystoreCreator.Owner, done: (Result<File>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("upload") }
    var owner by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<File?>(null) }

    val problem = when {
        name.isBlank() -> "Give the key a name."
        owner.isBlank() -> "Put your name (or your organization's) on the key."
        password.length < KeystoreCreator.MIN_PASSWORD -> "The password needs at least ${KeystoreCreator.MIN_PASSWORD} characters."
        password != again -> "The passwords don't match."
        else -> null
    }

    val file = created
    if (file != null) {
        BoriAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Back up your key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your release key is made and set up: release builds are signed with it now.")
                    Text(
                        "Save a copy somewhere safe (Drive, a computer) and keep the password with it. " +
                            "Every update of your app has to be signed with this same key. If it's lost, " +
                            "you can't update the app, and uninstalling Bori Code deletes it.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { shareBuildOutput(context, file) }) { Text("Back up now") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
        return
    }

    BoriAlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Create signing key") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Release builds and Google Play bundles need your own key. Bori Code makes one here " +
                        "(RSA 4096, valid ${KeystoreCreator.VALID_YEARS} years), the same kind Android Studio makes. " +
                        "On Google Play it's your upload key.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Key name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),  enabled = !working, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(owner, { owner = it; error = null }, label = { Text("Your name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),  enabled = !working, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(organization, { organization = it }, label = { Text("Organization (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),  enabled = !working, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it; error = null }, label = { Text("Password") }, singleLine = true, enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    again, { again = it; error = null }, label = { Text("Password again") }, singleLine = true, enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                (error ?: problem?.takeIf { password.isNotEmpty() || again.isNotEmpty() })?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (working) {
                    CircularProgressIndicator()
                    Text("Making the key… (a few seconds)", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APP_SIGNING_HELP_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Learn more about signing keys") }
            }
        },
        confirmButton = {
            Button(
                enabled = problem == null && !working,
                onClick = {
                    working = true
                    onCreate(name, password, KeystoreCreator.Owner(owner, organization)) { result ->
                        working = false
                        result.onSuccess { created = it }.onFailure { error = it.message ?: it.toString() }
                    }
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } },
    )
}
