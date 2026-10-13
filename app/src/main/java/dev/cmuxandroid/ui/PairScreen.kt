package dev.cmuxandroid.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.cmuxandroid.CmuxApplication
import dev.cmuxandroid.net.PairingClient
import dev.cmuxandroid.net.PairingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(app: CmuxApplication, initialUrl: String, initialCode: String, onBack: () -> Unit, onPaired: (String) -> Unit) {
    var address by remember { mutableStateOf(initialUrl) }
    var code by remember { mutableStateOf(initialCode) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val host = PairingClient.normalizeRelayUrl(address)?.host

    fun pair() {
        working = true
        error = null
        scope.launch {
            try {
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val (url, response) = app.pairing.pair(address, code, deviceName)
                app.store.add(response.deviceId, response.macName, url, response.token, notificationSeq = 0)
                onPaired(response.deviceId)
            } catch (failure: PairingException) {
                error = failure.message
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = "Pairing failed on this phone: ${failure.message ?: failure::class.simpleName}"
            } finally {
                working = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair a Mac") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Run `cmux-relay pair` on your Mac and copy the address and code it shows. Only pair with a Mac you own: it can show you anything, and what you type goes to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Mac address") },
                placeholder = { Text("https://my-mac.tailnet.ts.net:8765") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(9) },
                label = { Text("Pairing code") },
                placeholder = { Text("ABCD-EFGH") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            if (host != null) {
                Text("You are pairing with $host", style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = ::pair,
                enabled = !working && host != null && code.replace("-", "").length == 8,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (working) CircularProgressIndicator(Modifier.padding(2.dp)) else Text("Pair")
            }
        }
    }
}
