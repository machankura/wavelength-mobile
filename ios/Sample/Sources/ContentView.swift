import SwiftUI
import UIKit
import WalletKit

// A real app asks for the password and keeps it in the Keychain.
private let demoPassword = Data("wavelength-mobile-demo-password".utf8)

private enum DemoForm { case descriptors, watchOnly }

// ContentView drives the embedded wallet through WalletKit's WalletClient
// (async/throws + AsyncThrowingStream). It boots on signet, creates or opens a
// wallet, polls readiness as the chain syncs, and streams live activity. A wallet
// can be fresh, restored from private output descriptors, or watch-only with its
// keys on a remote signer. It mirrors the Android sample so the two platforms
// exercise the same wrapper surface.
//
// Secrets stay out of the log: it only ever shows counts and public keys.
struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var client = WalletClient()
    @State private var log = "Tap Start to boot the embedded wallet.\n"
    @State private var running = false
    @State private var busy = false
    @State private var walletReady = false
    @State private var reopenOnReturn = false
    // Start and stop must not overlap, including across a quick background and
    // return, so each waits for the one before it.
    @State private var daemonTask: Task<Void, Never>?
    @State private var form: DemoForm?

    @State private var descriptorLines = ""
    @State private var birthday = ""
    @State private var signerHost = ""
    @State private var macaroonHex = ""
    @State private var tlsCert = ""
    @State private var bundle = ""
    @State private var manifest = ""
    @State private var authKeys = ""

    private var dataDir: String {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
            .first!.appendingPathComponent("wavewalletdk").path
    }

    private var signerStore: SignerStore {
        SignerStore(
            dir: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
                .first!.appendingPathComponent("signer")
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("wavewalletdk signet demo").font(.system(.headline, design: .monospaced))

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 104))], spacing: 8) {
                Button("Start") { start() }.disabled(running || busy)
                Button("Create Wallet") { createWallet() }.disabled(!running || walletReady || busy)
                Button("Unlock") { unlockTapped() }.disabled(!running || walletReady || busy)
                Button("Restore…") { toggle(.descriptors) }.disabled(!running || walletReady || busy)
                Button("Watch-only…") { toggle(.watchOnly) }.disabled(walletReady || busy)
                Button("Export") { exportDescriptors() }.disabled(!walletReady)
                Button("Balance") { showBalance() }.disabled(!running)
                Button("Stop") { stop() }.disabled(!running)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)

            if let form {
                formView(form)
            }

            ScrollView {
                Text(log)
                    .font(.system(.caption, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding()
        .task {
            // Headless driver for CLI / CI runs: set WAVEWALLETDK_AUTOSTART to boot
            // and create a wallet without tapping (the simulator has no CLI tap).
            if ProcessInfo.processInfo.environment["WAVEWALLETDK_AUTOSTART"] != nil {
                await autoStart()
            }
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .background: enterBackground()
            case .active: returnToForeground()
            default: break
            }
        }
    }

    // The button sits outside the scrolling fields, so it stays in view.
    @ViewBuilder private func formView(_ form: DemoForm) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                switch form {
                case .descriptors:
                    PasteField(label: "Private descriptors, one per line", value: $descriptorLines)
                    birthdayField
                case .watchOnly:
                    TextField("Signer host:port (localhost:10019 on the simulator)", text: $signerHost)
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    PasteField(label: "Signer macaroon (hex, from xxd -p -c0)", value: $macaroonHex)
                    PasteField(label: "Signer TLS certificate (PEM)", value: $tlsCert)
                    PasteField(label: "Bundle (or leave empty and use a manifest)", value: $bundle)
                    PasteField(label: "Manifest (optional)", value: $manifest)
                    PasteField(label: "Authentication keys (with a manifest)", value: $authKeys)
                    birthdayField
                }
            }
        }
        switch form {
        case .descriptors:
            Button("Restore from descriptors") { restoreFromDescriptors() }
                .buttonStyle(.borderedProminent)
                .disabled(busy)
        case .watchOnly:
            Button("Open watch-only wallet") { openWatchOnly() }
                .buttonStyle(.borderedProminent)
                .disabled(busy)
        }
    }

    private var birthdayField: some View {
        TextField("Birthday (YYYY-MM-DD, optional)", text: $birthday)
            .textFieldStyle(.roundedBorder)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
    }

    private func toggle(_ target: DemoForm) {
        form = form == target ? nil : target
    }

    private func autoStart() async {
        busy = true
        append("Auto-start (signet)…")
        do {
            try await client.start(.signet(dataDir: dataDir))
            running = true
            append("gRPC serving. Creating wallet…")
            pollSync()
            let res = try await client.createWallet(walletPassword: demoPassword)
            walletReady = true
            append("wallet created; identity=\(res.identityPubKey.prefix(16))…")
            streamActivity()
        } catch {
            append("autostart failed: \(error)")
        }
        busy = false
    }

    @MainActor private func append(_ line: String) { log += line + "\n" }

    private func walletConfig(_ signer: RemoteSigner?) -> WalletConfig {
        var config = WalletConfig.signet(dataDir: dataDir)
        if let signer {
            config.remoteSignerRPCHost = signer.host
            config.remoteSignerMacaroonPath = signer.macaroonPath
            config.remoteSignerTLSCertPath = signer.tlsCertPath
        }
        return config
    }

    // Runs after any start or stop already in flight, and gives callers the
    // same ordering by waiting for the result.
    private func inDaemonOrder(_ body: @escaping () async throws -> Void) async throws {
        let previous = daemonTask
        let task = Task { () -> Result<Void, Error> in
            await previous?.value
            do {
                try await body()
                return .success(())
            } catch {
                return .failure(error)
            }
        }
        daemonTask = Task { _ = await task.value }
        try await task.value.get()
    }

    private func startDaemon(_ signer: RemoteSigner?) async throws {
        let config = walletConfig(signer)
        try await inDaemonOrder { try await client.start(config) }
        running = true
        pollSync()
    }

    private func stopDaemon() async throws {
        try await inDaemonOrder { try await client.stop() }
        running = false
        walletReady = false
    }

    private func message(_ error: Error) -> String {
        (error as? WalletError)?.message ?? error.localizedDescription
    }

    private func logWallet() async {
        if let info = try? await client.getInfo() {
            let hash = info.manifestHash.isEmpty ? "(none)" : info.manifestHash
            append("identity=\(info.identityPubKey) manifestHash=\(hash)")
        }
    }

    // A signer problem is not a wrong password, and the way out is to fix the
    // signer and retry.
    private func explainOpenFailure(_ action: String, retry: String, _ error: Error) -> String {
        let text = message(error)
        if text.lowercased().contains("signer") {
            return "Signer problem: \(text). Check the signer, then tap \(retry) to retry."
        }
        return "\(action) failed: \(text)"
    }

    private func start() {
        busy = true
        append("Starting embedded daemon (signet)…")
        Task {
            do {
                try await startDaemon(signerStore.load())
                append("gRPC serving.")
                if let info = try? await client.getInfo() {
                    append(
                        info.walletState == .locked
                            ? "A wallet exists: tap Unlock."
                            : "Create or restore a wallet to sync."
                    )
                }
            } catch {
                append("Start failed: \(error)")
            }
            busy = false
        }
    }

    private func createWallet() {
        busy = true
        append("Creating wallet…")
        Task {
            do {
                let res = try await client.createWallet(walletPassword: demoPassword)
                walletReady = true
                append("wallet created; identity=\(res.identityPubKey.prefix(16))…")
                append("seed words: \(res.mnemonic.count); now syncing from Esplora.")
                streamActivity()
            } catch {
                append("createWallet failed: \(error)")
            }
            busy = false
        }
    }

    private func unlock() async {
        append("Unlocking… a signer that is down takes about 30s to fail.")
        do {
            _ = try await client.unlockWallet(walletPassword: demoPassword)
            walletReady = true
            append("wallet unlocked; now syncing from Esplora.")
            await logWallet()
            streamActivity()
        } catch {
            append(explainOpenFailure("unlock", retry: "Unlock", error))
        }
    }

    private func unlockTapped() {
        busy = true
        Task {
            await unlock()
            busy = false
        }
    }

    private func restoreFromDescriptors() {
        busy = true
        append("Restoring from descriptors…")
        Task {
            do {
                let lines = descriptorLines
                    .split(whereSeparator: \.isNewline)
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty }
                let res = try await client.createWallet(
                    walletPassword: demoPassword,
                    descriptors: lines,
                    birthday: try parseBirthday(birthday)
                )
                walletReady = true
                descriptorLines = ""
                form = nil
                append("wallet restored; fingerprint=\(res.rootFingerprint)")
                res.descriptors.forEach { append($0) }
                await logWallet()
                streamActivity()
            } catch {
                append("restore failed: \(message(error))")
            }
            busy = false
        }
    }

    // The first open of a watch-only wallet. The signer settings must be in the
    // config before the daemon starts, so this restarts it. An unreachable signer
    // stores nothing, so retrying creates the wallet again, but a signer holding
    // other keys leaves a locked wallet behind, so unlock that one instead. Later
    // opens need only Start and Unlock: the database holds the public keys, so
    // the sample does not keep the bundle.
    private func openWatchOnly() {
        busy = true
        append("Opening watch-only wallet…")
        Task {
            do {
                let signer = try signerStore.save(
                    host: signerHost, macaroonHex: macaroonHex, tlsCert: tlsCert
                )
                if running {
                    try await stopDaemon()
                }
                try await startDaemon(signer)
                if try await client.getInfo().walletState == .locked {
                    append("A wallet already exists; unlocking it with these signer settings.")
                    await unlock()
                } else {
                    append("Signer settings saved; asking the signer to prove its keys…")
                    let res = try await client.createWallet(
                        walletPassword: demoPassword,
                        birthday: try parseBirthday(birthday),
                        watchOnlyBundle: bundle.isEmpty ? nil : bundle,
                        watchOnlyManifest: manifest.isEmpty ? nil : manifest,
                        authKeys: authKeys.isEmpty ? nil : authKeys
                    )
                    walletReady = true
                    append("watch-only wallet open; \(res.descriptors.count) public descriptors.")
                    await logWallet()
                    streamActivity()
                }
                if walletReady {
                    bundle = ""
                    manifest = ""
                    authKeys = ""
                    macaroonHex = ""
                    form = nil
                }
            } catch {
                append(explainOpenFailure("watch-only open", retry: "Open watch-only wallet", error))
            }
            busy = false
        }
    }

    // A daemon left running while the process is suspended keeps connections
    // frozen on their old network path, so stop on the way out and reopen on the
    // way back. A watch-only wallet needs its signer again at that point.
    private func enterBackground() {
        guard running else { return }
        reopenOnReturn = walletReady
        append("App in background: stopping the daemon.")
        Task { try? await stopDaemon() }
    }

    private func returnToForeground() {
        guard reopenOnReturn else { return }
        reopenOnReturn = false
        busy = true
        append("App back in foreground: reopening the wallet.")
        Task {
            do {
                try await startDaemon(signerStore.load())
                await unlock()
            } catch {
                append("Start failed: \(error)")
            }
            busy = false
        }
    }

    private func exportDescriptors() {
        Task {
            do {
                let res = try await client.listWalletDescriptors()
                append("public descriptors; fingerprint=\(res.rootFingerprint)")
                res.descriptors.forEach { append($0) }
            } catch {
                append("export failed: \(message(error))")
            }
        }
    }

    private func showBalance() {
        Task {
            do {
                let b = try await client.balance()
                append("balance: confirmed=\(b.confirmedSat) pendingIn=\(b.pendingInSat)")
            } catch {
                append("balance failed: \(error)")
            }
        }
    }

    private func stop() {
        Task {
            do {
                try await stopDaemon()
                append("Stopped.")
            } catch {
                append("stop failed: \(error)")
            }
        }
    }

    // Poll readiness every 5s while running so the block height and operator
    // connection are visible as the wallet syncs.
    private func pollSync() {
        Task {
            while running {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if let info = try? await client.getInfo() {
                    append(
                        "sync: height=\(info.blockHeight) state=\(info.walletState) " +
                        "operator=\(info.serverConnected ? "connected" : "…")"
                    )
                }
            }
        }
    }

    // Stream live activity entries through the AsyncThrowingStream.
    private func streamActivity() {
        Task {
            do {
                for try await e in client.activity(includeExisting: true) {
                    append("activity: \(e.kind) \(e.amountSat)sat \(e.status)")
                }
            } catch {
                append("activity stream ended: \(error)")
            }
        }
    }
}

// A secure text field is single-line, and would mangle a multi-line bundle, so
// this pastes instead. The value is never shown or seen by the keyboard, and the
// clipboard is cleared because private descriptors, a bundle's authentication
// keys and the macaroon can all spend or impersonate.
private struct PasteField: View {
    let label: String
    @Binding var value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption)
            HStack {
                Button("Paste") {
                    value = UIPasteboard.general.string ?? ""
                    UIPasteboard.general.items = []
                }
                .buttonStyle(.bordered)
                Button("Clear") { value = "" }
                    .buttonStyle(.bordered)
                    .disabled(value.isEmpty)
                Text(value.isEmpty ? "empty" : "\(value.count) characters")
                    .font(.caption)
            }
        }
    }
}

// A blank birthday scans from genesis.
private func parseBirthday(_ text: String) throws -> Date? {
    let trimmed = text.trimmingCharacters(in: .whitespaces)
    if trimmed.isEmpty { return nil }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = TimeZone(identifier: "UTC")
    formatter.dateFormat = "yyyy-MM-dd"
    formatter.isLenient = false
    guard let date = formatter.date(from: trimmed) else {
        throw SignerStoreError("the birthday must look like 2026-01-01")
    }
    return date
}
