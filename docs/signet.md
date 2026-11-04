# Running the sample on signet

The embedded wallet uses the lightweight, Esplora-backed wallet (`lwwallet`),
so it needs only an Esplora REST endpoint to sync the chain — no neutrino P2P,
no full node. The Ark operator mailbox is a separate endpoint, needed only for
rounds / sends (cooperative join, leave, Lightning).

## What syncs with just Esplora

After `Start` + `CreateWallet`, the wallet begins importing block / filter
headers from `ESPLORA_URL` and the reported `BlockHeight` climbs toward the
signet tip. Balance and on-chain history work off this sync alone. The Ark
operator is **not** required for chain sync — a missing/placeholder operator
just means sends and rounds are unavailable.

## Endpoints

`WalletConfig.signet(...)` carries Lightning Labs' public signet deployment as
defaults; override any of them per call:

| Parameter | Meaning | Default |
|-----------|---------|---------|
| `esploraUrl` | Esplora REST base URL | `https://mempool.space/signet/api` |
| `operatorAddress` | Ark operator mailbox `host:port` (TLS) | `arkd-signet.testnet.lightningcluster.com:443` |
| `swapServerAddress` | swapdk-server `host:port` (TLS) | `swapd-signet.testnet.lightningcluster.com:443` |

The operator and swap endpoints terminate TLS at `:443`, so they run secure (no
`insecure` flag). Pass an empty `operatorAddress` to run sync-only without an
operator.

Verified end to end on an emulator: the embedded wallet connects to the operator
over TLS, fetches operator terms, and starts the durable mailbox ingress loop,
while the Esplora-backed wallet syncs to the signet tip.

## A signer for watch-only wallets

To try a watch-only wallet, run `wavesigner` from a `wavelength` checkout on the
host machine. `wallet.mnemonic` holds a throwaway 24-word mnemonic for this test
only; `create` also takes `--descriptor-file`. An emulator reaches the host at
`10.0.2.2`, a simulator at `localhost`, and a device at the host's address, and
whichever you use must be in the signer's certificate:

```bash
export WAVESIGNER_PASSWORD=test-password-only
wavesigner --network=signet create --mnemonic-file wallet.mnemonic
wavesigner --network=signet export-watchonly --output signer.bundle
wavesigner --network=signet run --listen=0.0.0.0:10019 --tlsextraip=10.0.2.2
```

`signer.macaroon` and `tls.cert` are written to `~/.wavesigner/signet/` when `run`
first starts. In a sample app, choose **Watch-only…** and paste the host, the bundle
and the certificate, and the macaroon as hex (`xxd -p -c0 signer.macaroon`). A
certificate that already exists is not regenerated, so delete `tls.cert` and
`tls.key` if you add an address later.

For a manifest, use a signer store with overrides in a separate data directory. The
key files hold private keys for those fund slots; `docs/daemon_cli_guide.md` in
`wavelength`, under "Fund slots from other keys (manifests)", describes their format:

```bash
wavesigner --network=signet --datadir=/tmp/signer2 create \
  --mnemonic-file wallet.mnemonic --override onchain=cold-a.key
wavesigner --network=signet --datadir=/tmp/signer2 export-watchonly \
  --manifest-out wallet.manifest --auth-keys-out auth.keys
```

## Standard signet vs mutinynet

`waved` maps the network name `signet` to **standard** `chaincfg.SigNetParams`.
Mutinynet is a *custom* signet with its own signet challenge; pointing a
standard-signet wallet at a mutinynet Esplora will fail header validation.
Supporting mutinynet requires wiring a configurable signet challenge into
`waved` first — track this before switching the default endpoint.

## Notes

- The emulator reaches the host loopback at `10.0.2.2`; a public Esplora URL
  (like the default) works directly.
- First sync downloads a lot of headers; give it time and watch the `sync:`
  log lines.
- Public Esplora servers rate-limit. A few wallets syncing from scratch on one
  address can trip the limit, and then opening a wallet fails at `get tip height`
  with an HTTP 429 or a timeout, leaving it locked. Unlock works again once the
  limit clears. For repeated testing, point `esploraUrl` at your own server.
