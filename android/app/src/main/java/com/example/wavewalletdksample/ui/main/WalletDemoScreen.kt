package com.example.wavewalletdksample.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import engineering.lightning.wavewalletdk.client.WalletClient
import engineering.lightning.wavewalletdk.client.WalletConfig
import engineering.lightning.wavewalletdk.client.WalletState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// A real app asks for the password and keeps it in the Android Keystore.
private const val DEMO_PASSWORD = "wavelength-mobile-demo-password"

private enum class DemoForm { DESCRIPTORS, WATCH_ONLY }

// WalletDemoScreen drives the embedded wallet through the idiomatic WalletClient
// wrapper (suspend + Flow), rather than the raw gomobile bindings. It boots on
// signet, creates or opens a wallet, watches the chain sync, and streams live
// activity. A wallet can be fresh, restored from private output descriptors, or
// watch-only with its keys on a remote signer.
//
// Secrets stay out of the log: it only ever shows counts and public keys.
//
// Endpoints default to Lightning Labs' public signet deployment (operator
// arkd-signet..., Esplora mempool.space/signet). Override here as needed.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WalletDemoScreen(dataDir: File, signerDir: File, modifier: Modifier = Modifier) {
  val client = remember { WalletClient() }
  val scope = rememberCoroutineScope()
  val signerStore = remember(signerDir) { SignerStore(signerDir) }
  // Start and stop must not overlap, including across a quick background and
  // return.
  val daemonLock = remember { Mutex() }

  var log by remember { mutableStateOf("Tap Start to boot the embedded wallet.\n") }
  var running by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var walletReady by remember { mutableStateOf(false) }
  var reopenOnReturn by remember { mutableStateOf(false) }
  var form by remember { mutableStateOf<DemoForm?>(null) }

  var descriptorLines by remember { mutableStateOf("") }
  var birthday by remember { mutableStateOf("") }
  var signerHost by remember { mutableStateOf("") }
  var macaroonHex by remember { mutableStateOf("") }
  var tlsCert by remember { mutableStateOf("") }
  var bundle by remember { mutableStateOf("") }
  var manifest by remember { mutableStateOf("") }
  var authKeys by remember { mutableStateOf("") }

  fun append(line: String) { log += line + "\n" }

  fun walletConfig(signer: RemoteSigner?): WalletConfig {
    val signet = WalletConfig.signet(dataDir = "${dataDir.absolutePath}/wavewalletdk")
    return if (signer == null) {
      signet
    } else {
      signet.copy(
        remoteSignerRpcHost = signer.host,
        remoteSignerMacaroonPath = signer.macaroonPath,
        remoteSignerTlsCertPath = signer.tlsCertPath,
      )
    }
  }

  suspend fun startDaemon(signer: RemoteSigner?) {
    daemonLock.withLock { client.start(walletConfig(signer)) }
    running = true
  }

  suspend fun logWallet() {
    runCatching { client.getInfo() }.onSuccess { info ->
      append(
        "identity=${info.identityPubKey} " +
          "manifestHash=${info.manifestHash.ifEmpty { "(none)" }}",
      )
    }
  }

  // A signer problem is not a wrong password, and the way out is to fix the
  // signer and retry.
  fun explainOpenFailure(action: String, retry: String, e: Throwable): String {
    val message = e.message.orEmpty()
    return if (message.contains("signer", ignoreCase = true)) {
      "Signer problem: $message. Check the signer, then tap $retry to retry."
    } else {
      "$action failed: $message"
    }
  }

  suspend fun unlock() {
    append("Unlocking… a signer that is down takes about 30s to fail.")
    runCatching { client.unlockWallet(DEMO_PASSWORD.toByteArray()) }
      .onSuccess {
        walletReady = true
        append("wallet unlocked; now syncing from Esplora.")
        logWallet()
      }
      .onFailure { append(explainOpenFailure("unlock", "Unlock", it)) }
  }

  fun restoreFromDescriptors() {
    busy = true
    append("Restoring from descriptors…")
    scope.launch {
      runCatching {
        val lines = descriptorLines.lines().map(String::trim).filter(String::isNotEmpty)
        client.createWallet(
          walletPassword = DEMO_PASSWORD.toByteArray(),
          descriptors = lines,
          birthdayUnixSeconds = parseBirthday(birthday),
        )
      }
        .onSuccess { res ->
          walletReady = true
          descriptorLines = ""
          form = null
          append("wallet restored; fingerprint=${res.rootFingerprint}")
          res.descriptors.forEach(::append)
          logWallet()
        }
        .onFailure { append("restore failed: ${it.message}") }
      busy = false
    }
  }

  // The first open of a watch-only wallet. The signer settings must be in the
  // config before the daemon starts, so this restarts it. An unreachable signer
  // stores nothing, so retrying creates the wallet again, but a signer holding
  // other keys leaves a locked wallet behind, so unlock that one instead. Later
  // opens need only Start and Unlock: the database holds the public keys, so
  // the sample does not keep the bundle.
  fun openWatchOnly() {
    busy = true
    append("Opening watch-only wallet…")
    scope.launch {
      runCatching {
        val signer = signerStore.save(signerHost, macaroonHex, tlsCert)
        if (running) {
          daemonLock.withLock { client.stop() }
          running = false
        }
        startDaemon(signer)
        if (client.getInfo().walletState == WalletState.LOCKED) {
          append("A wallet already exists; unlocking it with these signer settings.")
          unlock()
          return@runCatching
        }
        append("Signer settings saved; asking the signer to prove its keys…")
        val res = client.createWallet(
          walletPassword = DEMO_PASSWORD.toByteArray(),
          birthdayUnixSeconds = parseBirthday(birthday),
          watchOnlyBundle = bundle.ifBlank { null },
          watchOnlyManifest = manifest.ifBlank { null },
          authKeys = authKeys.ifBlank { null },
        )
        walletReady = true
        append("watch-only wallet open; ${res.descriptors.size} public descriptors.")
        logWallet()
      }
        .onSuccess {
          if (walletReady) {
            bundle = ""
            manifest = ""
            authKeys = ""
            macaroonHex = ""
            form = null
          }
        }
        .onFailure { append(explainOpenFailure("watch-only open", "Open watch-only wallet", it)) }
      busy = false
    }
  }

  // A daemon left running while the process is suspended keeps connections
  // frozen on their old network path, so stop on the way out and reopen on the
  // way back. A watch-only wallet needs its signer again at that point.
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
    if (!running) return@LifecycleEventEffect
    reopenOnReturn = walletReady
    running = false
    walletReady = false
    append("App in background: stopping the daemon.")
    scope.launch {
      daemonLock.withLock { runCatching { client.stop() } }
    }
  }

  LifecycleEventEffect(Lifecycle.Event.ON_START) {
    if (!reopenOnReturn) return@LifecycleEventEffect
    reopenOnReturn = false
    busy = true
    append("App back in foreground: reopening the wallet.")
    scope.launch {
      runCatching { startDaemon(signerStore.load()) }
        .onSuccess { unlock() }
        .onFailure { append("Start failed: ${it.message}") }
      busy = false
    }
  }

  // While running, poll readiness every 5s so the block height and server
  // connection are visible as the wallet syncs.
  LaunchedEffect(running) {
    while (running) {
      delay(5_000)
      runCatching { client.getInfo() }.onSuccess { info ->
        append(
          "sync: height=${info.blockHeight} state=${info.walletState} " +
            "operator=${if (info.serverConnected) "connected" else "…"}",
        )
      }
    }
  }

  // Once a wallet exists, stream live activity entries through the Flow.
  LaunchedEffect(walletReady) {
    if (!walletReady) return@LaunchedEffect
    runCatching {
      client.activity(includeExisting = true).collect { e ->
        append("activity: ${e.kind} ${e.amountSat}sat ${e.status} ${e.counterparty}")
      }
    }.onFailure { append("activity stream ended: ${it.message}") }
  }

  Column(
    modifier = modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("wavewalletdk signet demo", fontFamily = FontFamily.Monospace)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        enabled = !running && !busy,
        onClick = {
          busy = true
          append("Starting embedded daemon (signet)…")
          scope.launch {
            runCatching { startDaemon(signerStore.load()) }
              .onSuccess {
                append("gRPC serving.")
                runCatching { client.getInfo() }.onSuccess { info ->
                  append(
                    if (info.walletState == WalletState.LOCKED) {
                      "A wallet exists: tap Unlock."
                    } else {
                      "Create or restore a wallet to sync."
                    },
                  )
                }
              }
              .onFailure { append("Start failed: ${it.message}") }
            busy = false
          }
        },
      ) { Text("Start") }

      Button(
        enabled = running && !walletReady && !busy,
        onClick = {
          busy = true
          append("Creating wallet…")
          scope.launch {
            runCatching { client.createWallet(DEMO_PASSWORD.toByteArray()) }
              .onSuccess { res ->
                walletReady = true
                append("wallet created; identity=${res.identityPubKey.take(16)}…")
                append("seed words: ${res.mnemonic.size}; now syncing from Esplora.")
              }
              .onFailure { append("createWallet failed: ${it.message}") }
            busy = false
          }
        },
      ) { Text("Create Wallet") }

      Button(
        enabled = running && !walletReady && !busy,
        onClick = {
          busy = true
          scope.launch {
            unlock()
            busy = false
          }
        },
      ) { Text("Unlock") }

      Button(
        enabled = running && !walletReady && !busy,
        onClick = { form = if (form == DemoForm.DESCRIPTORS) null else DemoForm.DESCRIPTORS },
      ) { Text("Restore…") }

      Button(
        enabled = !walletReady && !busy,
        onClick = { form = if (form == DemoForm.WATCH_ONLY) null else DemoForm.WATCH_ONLY },
      ) { Text("Watch-only…") }

      Button(
        enabled = walletReady,
        onClick = {
          scope.launch {
            runCatching { client.listWalletDescriptors() }
              .onSuccess { res ->
                append("public descriptors; fingerprint=${res.rootFingerprint}")
                res.descriptors.forEach(::append)
              }
              .onFailure { append("export failed: ${it.message}") }
          }
        },
      ) { Text("Export") }

      Button(
        onClick = {
          scope.launch {
            runCatching { client.balance() }
              .onSuccess { append("balance: confirmed=${it.confirmedSat} pendingIn=${it.pendingInSat}") }
              .onFailure { append("balance failed: ${it.message}") }
          }
        },
      ) { Text("Balance") }

      Button(
        onClick = {
          scope.launch {
            runCatching { client.status() }
              .onSuccess { append("status: ready=${it.ready} unlocked=${it.unlocked} pending=${it.pendingCount}") }
              .onFailure { append("status failed: ${it.message}") }
          }
        },
      ) { Text("Status") }

      Button(
        onClick = {
          scope.launch {
            daemonLock.withLock {
              runCatching { client.stop() }
                .onSuccess { running = false; walletReady = false; append("Stopped.") }
                .onFailure { append("stop failed: ${it.message}") }
            }
          }
        },
      ) { Text("Stop") }
    }

    if (form != null) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Column(
          modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          when (form) {
            DemoForm.DESCRIPTORS -> {
              SecretField("Private descriptors, one per line", descriptorLines) { descriptorLines = it }
              PlainField("Birthday (YYYY-MM-DD, optional)", birthday) { birthday = it }
            }
            DemoForm.WATCH_ONLY -> {
              PlainField("Signer host:port (10.0.2.2:10019 from an emulator)", signerHost) { signerHost = it }
              SecretField("Signer macaroon (hex, from xxd -p -c0)", macaroonHex) { macaroonHex = it }
              PlainField("Signer TLS certificate (PEM)", tlsCert) { tlsCert = it }
              SecretField("Bundle (or leave empty and use a manifest)", bundle) { bundle = it }
              PlainField("Manifest (optional)", manifest) { manifest = it }
              SecretField("Authentication keys (with a manifest)", authKeys) { authKeys = it }
              PlainField("Birthday (YYYY-MM-DD, optional)", birthday) { birthday = it }
            }
            null -> Unit
          }
        }
        // Outside the scrolling fields, so it stays in view however much was
        // pasted.
        if (form == DemoForm.DESCRIPTORS) {
          Button(enabled = !busy, onClick = { restoreFromDescriptors() }) {
            Text("Restore from descriptors")
          }
        } else {
          Button(enabled = !busy, onClick = { openWatchOnly() }) {
            Text("Open watch-only wallet")
          }
        }
      }
    }

    Text(
      text = log,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
    )
  }
}

// Masked and kept away from the keyboard's learning: private descriptors, a
// bundle's authentication keys and the macaroon can all spend or impersonate.
@Composable
private fun SecretField(label: String, value: String, onValueChange: (String) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    maxLines = 3,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(
      autoCorrectEnabled = false,
      keyboardType = KeyboardType.Password,
    ),
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun PlainField(label: String, value: String, onValueChange: (String) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    maxLines = 3,
    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    modifier = Modifier.fillMaxWidth(),
  )
}

// A blank birthday scans from genesis. java.time needs API 26, above minSdk.
private fun parseBirthday(text: String): Long? {
  if (text.isBlank()) return null
  val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
  format.timeZone = TimeZone.getTimeZone("UTC")
  format.isLenient = false
  val date = runCatching { format.parse(text.trim()) }.getOrNull()
  return requireNotNull(date) { "the birthday must look like 2026-01-01" }.time / 1000
}
