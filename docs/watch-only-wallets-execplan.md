# Add watch-only wallets to WalletKit and finish descriptor wallets

This ExecPlan is a living document. The sections `Progress`, `Surprises &
Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must stay current
while the work proceeds. It follows the ExecPlan rules in `PLANS.md` at the root
of the sibling `wavelength` repository.


## Purpose / Big Picture

The `descriptors` branch here already lets a Kotlin or Swift app restore a wallet
from private output descriptors and export them. Nothing in the sample apps shows
this, nothing has run it on a device, and there is no way to open a wallet whose keys
live somewhere else.

After this work an app built on WalletKit can open a watch-only wallet, whose fund keys
stay on a remote signer such as `wavesigner`, so a lost phone cannot spend the funds.
Both sample apps demonstrate descriptor restore, descriptor export and watch-only
wallets, and all three have been seen working on a real runtime.

To see it working, run the Android sample on an emulator, choose the watch-only option,
paste a bundle exported by `wavesigner` on the host machine, and watch the wallet open
with the identity key `wavesigner` reports. Stop `wavesigner`, restart the app, and see
a clear signer error instead of a password error.


## Progress

- [x] (2026-11-04) Descriptor restore and export in both wrappers (commits
  `db61c3c`, `54159a7`), documented in `docs/api-guide.md` (`3fd5bd5`). Tested only by
  Android format tests; not run on a device.
- [ ] Milestone 0: build the bindings from the daemon's `descriptors` branch.
- [ ] Milestone 1: remote signer settings in `WalletConfig` on both platforms.
- [ ] Milestone 2: watch-only `createWallet` and manifest hash, Android.
- [ ] Milestone 3: the same for iOS, with a first Swift test target.
- [ ] Milestone 4: descriptors and watch-only in the Android sample.
- [ ] Milestone 5: descriptors and watch-only in the iOS sample.
- [ ] Milestone 6: documentation.
- [ ] Milestone 7: test against released binaries.


## Surprises & Discoveries

These were found while writing this plan, from reading the code on 2026-11-04.

- Observation: A descriptor wallet's restart depends on which path created it. WalletKit
  uses `Mobile.createWallet`, where the host's password protects the wallet, so
  `unlockWallet` reopens it. The atomic `StartDescriptorWallet` verb derives the password
  instead, but WalletKit does not call it, so the existing flow is sound.
  Evidence: `parseDescriptorWalletRequest` in `sdk/wavewalletdk/descriptor.go` in
  `wavelength`; the list of `Mobile.*` calls in `WalletClient.kt`.

- Observation: `android/walletkit` has unit tests, but `ios/WalletKit` has no test target,
  and the repository has no CI workflows. Everything on iOS, and every device test,
  is run by hand.
  Evidence: `ios/WalletKit` holds only `Package.swift` and `Sources`; there is no
  `.github` directory.

- Observation: The fetch scripts default to the latest `wavelength` release, which does
  not contain this work.
  Evidence: `scripts/fetch-aar.sh` header, and `v0.2.1` is not an ancestor of the daemon's
  `descriptors` branch.


## Decision Log

- Decision: Reach watch-only wallets through `start` then `createWallet` and
  `unlockWallet`, and do not wrap the atomic `StartWatchOnlyWallet` verb.
  Rationale: WalletKit's whole shape is start, create, unlock. It wraps neither
  `StartExternalSeedWallet` nor `StartDescriptorWallet`. Go's `CreateWallet` already takes
  the watch-only inputs (`sdk/wavewalletdk/client.go`), so this needs no new gomobile binding
  and gives a watch-only wallet the same unlock flow as any other. If Milestone 4 shows this
  path cannot work, add the verb and record why here.
  Date/Author: 2026-11-04, plan author.

- Decision: WalletKit stays stateless about secrets. Bundles, authentication keys and the
  wallet password pass through it and are never stored by it.
  Rationale: It holds no mnemonics today. The samples show where they belong: the Android
  Keystore and iOS Keychain.
  Date/Author: 2026-11-04, plan author.

- Decision: Do not add CI or new helper libraries in this plan.
  Rationale: CI for gomobile binaries is its own project. Note it as a follow-up.
  Date/Author: 2026-11-04, plan author.


## Outcomes & Retrospective

Nothing from this plan has been implemented yet. Fill this in at each milestone and at
the end.


## Context and Orientation

This repository wraps a Go wallet for mobile. `waved`, the wavelength wallet daemon, runs
inside the app's process through gomobile, a tool that turns a Go package into an Android
`.aar` and an iOS `.xcframework`. The Go package is `sdk/wavewalletdk/mobile` in the
sibling `wavelength` repository, called "the facade" here. Its functions take and return
JSON with PascalCase fields; `[]byte` fields are base64, and start configuration uses
snake_case. Only one daemon runs per process.

There are two parallel wrappers that must stay in step. On Android they live in
`android/walletkit/src/main/java/engineering/lightning/wavewalletdk/client/`: `WalletClient.kt`
(the `suspend` API and private `@Serializable` request classes at the end of the file),
`Models.kt` (results) and `WalletConfig.kt`. Tests live in the same path under `src/test`:
`WalletClientFormatTest.kt` and `WalletConfigTest.kt`. On iOS they live in
`ios/WalletKit/Sources/WalletKit/`: `WalletClient.swift`, `Models.swift`, `WalletConfig.swift`,
and `Bindings.swift`, which declares which `Mobile*` functions are used.

The sample apps are `android/app` (`ui/main/WalletDemoScreen.kt`) and `ios/Sample`
(`Sources/ContentView.swift`). Both are one simple screen that logs what happens.
`docs/api-guide.md` is the public guide. `scripts/fetch-aar.sh` and
`scripts/fetch-xcframework.sh` download binaries from a release, or build them from source
when `WAVELENGTH_DIR` points at a checkout. `scripts/run-ios-sample.sh` runs the iOS sample.

These terms are used throughout.

An output descriptor is a line of text that describes keys and how to spend from them, such
as `tr(tprv.../86h/0h/0h/<0;1>/*)#checksum`. A private one is as sensitive as a seed.

A remote signer is a separate process holding the private keys, which the wallet asks to sign
over gRPC. `wavesigner` is ours, and lnd works too. A watch-only wallet holds only public keys
and signs everything through the signer. The phone always dials out to it, so the app still
opens no port.

A bundle is the text the signer exports for a watch-only wallet. A minimal bundle also holds
private "authentication" keys, used for routine Ark server authentication so that the signer
is not asked each time. A manifest is a JSON file used instead of a bundle when the wallet's
fund slots come from several keys; it needs a matching authentication keys file. The manifest
hash identifies which manifest created a wallet. The wallet password protects the local
database and any authentication keys.

On every open, the daemon has the signer sign a challenge with each key slot and checks the
result, so unlocking a watch-only wallet fails if the signer is unreachable or holds different
keys.

The facade expects these JSON fields, all in `sdk/wavewalletdk/types.go`. `CreateWalletRequest`
has `WatchOnlyBundle`, `WatchOnlyManifest` and `AuthKeys` as base64 of the text, and
`OverrideIdentity` as a boolean. `CreateWalletResult` and `Info` have `ManifestHash`. The config
has `remote_signer_rpc_host`, `remote_signer_macaroon_path`, `remote_signer_tls_cert_path`,
`remote_signer_connect_timeout_seconds` and `remote_signer_request_timeout_seconds`.

### Prerequisites in `wavelength`

Milestones 0 to 6 build against a local checkout of its `descriptors` branch. Milestone 7 needs
a published release containing that branch, written `vX.Y.Z` here.


## Plan of Work

### Milestone 0: bindings from source

Build both binaries from the daemon's `descriptors` branch and run the existing Android tests.
The `.aar` is the only binary this machine can build unless it is a Mac; the `.xcframework`
needs macOS and Xcode. The result is a sample app that still builds and runs its existing flow
on the new daemon, which also exercises the descriptor commits' JSON for the first time. Record
any drift between the daemon's branch and the current latest release.

### Milestone 1: remote signer settings

In `WalletConfig.kt`, add nullable fields with the five `@SerialName` values above, grouped under
a comment that says what they do and that they need a watch-only wallet. Add a `remoteSigner(host,
macaroonPath, tlsCertPath)` helper if the file's preset style has an equivalent; otherwise leave
them plain. In `WalletConfig.swift`, add the same as optional properties and `CodingKeys`.

The daemon's rule is that the macaroon and certificate paths are real file paths, which the Go
code opens directly. Say so in the doc comment, because a host with a `content://` or `file://`
URI would otherwise fail at unlock with a confusing error.

The result: `WalletConfigTest` shows the five keys in the JSON when set, and absent when not.

### Milestone 2: watch-only createWallet on Android

Add `watchOnlyBundle`, `watchOnlyManifest`, `authKeys` and `overrideIdentity` parameters to
`WalletClient.createWallet`, at the end with defaults, so existing callers compile. Enforce what the
daemon enforces with the same `require(...)` style already there, so a host gets a clear message
without booting: no bundle or manifest with a mnemonic or descriptors, not both a bundle and a
manifest, a manifest needs authentication keys, and a bundle takes neither authentication keys nor
an identity override. Base64-encode the three text values into `CreateWalletReq`, which already
base64-encodes the password. Add `@SerialName("ManifestHash")` to `CreateWalletResult` and `Info`
in `Models.kt`, defaulting to an empty string.

The result: `WalletClientFormatTest` asserts the exact JSON and the rule failures, and old JSON
without `ManifestHash` still decodes.

### Milestone 3: watch-only createWallet on iOS, and a Swift test target

Mirror Milestone 2 in `WalletClient.swift` and `Models.swift`, decoding `ManifestHash` leniently as
the descriptor fields already do, so older daemons still parse. The private `CreateWalletReq` gains
the four fields.

iOS has no tests, and the logic added here is exactly what tests should cover, so add a
`WalletKitTests` target to `ios/WalletKit/Package.swift`. If linking the binary target makes the test
target need the `.xcframework` present, have it skip with a clear message when absent. Cover the
validation rules, the JSON of `WalletConfig` and `CreateWalletReq`, and the lenient decoding,
including the descriptor fields already merged without tests.

The result, on a Mac: `swift test` in `ios/WalletKit` passes. On any other machine this milestone
cannot be verified, so record that plainly in `Progress` instead of marking it done.

### Milestone 4: Android sample

Extend `WalletDemoScreen.kt`. Add a "Restore from descriptors" action taking pasted lines and an
optional birthday, an "Export descriptors" action that logs public descriptors, and a "Watch-only
wallet" section taking the signer host, the bundle, and the macaroon and certificate. Because the
daemon needs file paths, write those two into `context.noBackupFilesDir/signer/` with owner-only
access; `android:allowBackup="false"` is already set, and this folder is excluded from automatic backup
as well. Make every text field that can hold a secret a password-style field. Never pass secrets to
the screen's `append` log, and print only counts and public keys.

On restart, run `start` with the signer config and then `unlockWallet` with the password. This is the
first real run of the decision above, so check all of it: the import, a kill and relaunch, and the
identity staying the same. The Go SDK's watch-only path has only been tested against a stub daemon,
so treat failures as likely real. If `start` plus `createWallet` cannot open a watch-only wallet,
revisit the Decision Log and add the atomic verb.

A watch-only wallet needs its signer every time it unlocks, including after any restart of the daemon.
`docs/api-guide.md` says nothing about backgrounding today, so read what `docs/wavewalletdk_mobile.md` in
`wavelength` requires, apply it to the sample if the sample does not already, and document it in Milestone 6.
Show a signer failure with a retry action rather than treating it as a wrong password. After the first open,
only the password is needed, because the wallet database holds the imported public keys, so the sample need not
keep the bundle.

### Milestone 5: iOS sample

Mirror Milestone 4 in `ContentView.swift`, storing the signer's files in Application Support with
`NSFileProtectionComplete` and `isExcludedFromBackup`. Use the simulator first, then a device, because
network and file behavior differ. A simulator reaches the host machine at `localhost`, so start `wavesigner`
with `--tlsextradomain=localhost`; a device needs the host's address through `--tlsextraip`.

### Milestone 6: documentation

Add a "Watch-only wallets" section to `docs/api-guide.md` after "Restore from output descriptors", with
Kotlin and Swift examples, the file path rule, what happens with the signer offline, and where secrets
belong. Add `manifestHash` to the results table. Correct the existing sentence saying account-level
descriptors are refused. The daemon accepts account-level private keys when they cover the on-chain
account and every key family, and refuses partial sets, so say that. Update `docs/architecture.md` to
mention the outbound signer connection, and `README.md`'s feature list. Note in `docs/signet.md` how to
stand up a signer for testing, using the steps below.

### Milestone 7: test against a release

Once `vX.Y.Z` is published, run the scripts with no `WAVELENGTH_DIR` and `WAVELENGTH_VERSION=vX.Y.Z`,
then repeat the acceptance list on both platforms. Until then this branch cannot merge, because the
binaries a user would fetch lack `Mobile.listWalletDescriptors`, which these commits already call.


## Concrete Steps

Run these from the repository root. Building the `.aar` needs the Android SDK, NDK r29, Go, gomobile
and JDK 17 or newer, as `docs/android.md` describes.

    export WAVELENGTH_DIR="$(cd ../wavelength && pwd)"
    scripts/fetch-aar.sh
    cd android && ./gradlew :walletkit:testDebugUnitTest :app:assembleDebug

Expect `BUILD SUCCESSFUL`. On a Mac:

    scripts/fetch-xcframework.sh
    cd ios/WalletKit && swift test

To stand up a signer on the host. An emulator reaches the host at `10.0.2.2`, which must be in the
signer's certificate:

    export WAVESIGNER_PASSWORD=test-password-only
    wavesigner --network=signet create --mnemonic-file wallet.mnemonic
    wavesigner --network=signet export-watchonly --output signer.bundle
    wavesigner --network=signet run --listen=0.0.0.0:10019 --tlsextraip=10.0.2.2

`wallet.mnemonic` holds a throwaway 24-word mnemonic for this test only; `create` also takes
`--descriptor-file`. `signer.macaroon` and `tls.cert` are written to `~/.wavesigner/signet/` when `run` first
starts. Copy them to the emulator with `adb push`, or paste them into the sample.

A manifest needs a signer store with overrides, so use a separate data directory. The key files hold private
keys for those fund slots; `docs/daemon_cli_guide.md` in `wavelength`, under "Fund slots from other keys
(manifests)", describes their format:

    wavesigner --network=signet --datadir=/tmp/signer2 create \
      --mnemonic-file wallet.mnemonic --override onchain=cold-a.key
    wavesigner --network=signet --datadir=/tmp/signer2 export-watchonly \
      --manifest-out wallet.manifest --auth-keys-out auth.keys

Commit after each milestone on the existing `descriptors` branch.


## Validation and Acceptance

Android unit tests pass at every milestone. New cases fail if the JSON or rules are removed.

Then, on an emulator and on the iOS simulator:

1. Restore from a descriptor on signet. The result has a `rootFingerprint` and public descriptors.
2. Export. Lines start with `tr(` and `wpkh(`, and none contain `prv`.
3. Kill and relaunch. `unlockWallet` with the same password brings back the same identity.
4. Open a watch-only wallet against the signer. The identity matches `wavesigner`'s and `Info` shows
   an empty `manifestHash`.
5. Repeat with the manifest and authentication keys from the second signer above, on a fresh data
   directory, with that signer running. `manifestHash` is now set.
6. Stop the signer and relaunch. Unlock fails with a message naming the signer. Restart it, retry,
   and it opens.
7. Use a different signer's files. The open fails.
8. Search the device log for `prv` during all of this and find nothing.

Check what a private export does on a watch-only wallet, and document what you find. The daemon has
no private keys for it, so expect a refusal.


## Idempotence and Recovery

Every script can be rerun; it overwrites the staged binary. Binaries are not checked in. Sample wallets
live in the app's data, so clearing it restarts from nothing. Use `--datadir` for a throwaway signer.
Nothing here migrates existing wallets: new fields are optional and new JSON keys are ignored by older
daemons, except that an older daemon will not honor watch-only inputs at all, so Milestone 7 pins a
version containing them.


## Artifacts and Notes

The create request the facade expects for a bundle:

    {
      "WalletPassword": "aHVudGVyMg==",
      "WatchOnlyBundle": "<base64 of the bundle text>",
      "Birthday": "2026-01-01T00:00:00Z"
    }

A nil Go slice arrives as JSON `null`, which is why results decode leniently. `wavelength-sdk` has the
TypeScript version of this feature for comparison, in `packages/core/src/requests.ts`.


## Interfaces and Dependencies

No new dependencies and no new gomobile bindings. At the end:

    // Kotlin, WalletClient.kt
    suspend fun createWallet(
      walletPassword: ByteArray,
      mnemonic: List<String> = emptyList(),
      descriptors: List<String> = emptyList(),
      birthdayUnixSeconds: Long? = null,
      watchOnlyBundle: String? = null,
      watchOnlyManifest: String? = null,
      authKeys: String? = null,
      overrideIdentity: Boolean = false,
    ): CreateWalletResult

    // Swift, WalletClient.swift
    public func createWallet(
        walletPassword: Data,
        mnemonic: [String] = [],
        descriptors: [String] = [],
        birthday: Date? = nil,
        watchOnlyBundle: String? = nil,
        watchOnlyManifest: String? = nil,
        authKeys: String? = nil,
        overrideIdentity: Bool = false
    ) async throws -> CreateWalletResult

Match the existing parameter names when they differ from these sketches.


Revision note (2026-11-04): first version, written from the `descriptors` branch here and in
`wavelength`.
