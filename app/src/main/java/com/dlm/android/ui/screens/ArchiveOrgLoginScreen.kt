package com.dlm.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dlm.android.ui.QueueViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveOrgLoginScreen(vm: QueueViewModel, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val repo = vm.repository()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var access by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Decrypting the auth status touches the keystore/disk; keep it off the main thread.
    LaunchedEffect(Unit) { status = withContext(Dispatchers.IO) { repo.authStatus() } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("archive.org") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val signedIn = status.contains("signed in", true) || status.contains("S3") || status.contains("cookie")
            Text(
                if (signedIn) "You’re signed in." else "Signing in is optional — most downloads work without it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sign in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        password, { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = email.isNotBlank() && password.isNotBlank() && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val err = withContext(Dispatchers.IO) { repo.loginPassword(email.trim(), password) }
                                message = err?.let { "Sign-in failed: $it" }
                                status = repo.authStatus()
                                if (err == null) password = ""
                                busy = false
                            }
                        },
                    ) { Text(if (busy) "Signing in…" else "Sign in") }
                }
            }

            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Hide advanced options" else "Advanced options")
            }
            AnimatedVisibility(visible = advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("API keys", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("From archive.org/account/s3.php", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(access, { access = it }, label = { Text("Access key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                secret, { secret = it }, label = { Text("Secret key") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                enabled = access.isNotBlank() && secret.isNotBlank() && !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { repo.loginS3(access.trim(), secret.trim()) }
                                        message = if (ok) null else "Couldn’t save the keys"
                                        status = withContext(Dispatchers.IO) { repo.authStatus() }
                                        if (ok) { access = ""; secret = "" }
                                        busy = false
                                    }
                                },
                            ) { Text("Save keys") }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Login cookie", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                cookie, { cookie = it }, label = { Text("Paste cookie") },
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                enabled = cookie.isNotBlank() && !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { repo.loginCookie(cookie.trim()) }
                                        message = if (ok) null else "Couldn’t save the cookie"
                                        status = withContext(Dispatchers.IO) { repo.authStatus() }
                                        if (ok) cookie = ""
                                        busy = false
                                    }
                                },
                            ) { Text("Save cookie") }
                        }
                    }
                }
            }

            OutlinedButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { repo.logout() }
                    status = withContext(Dispatchers.IO) { repo.authStatus() }
                    message = null
                }
            }) { Text("Sign out") }
        }
    }
}
