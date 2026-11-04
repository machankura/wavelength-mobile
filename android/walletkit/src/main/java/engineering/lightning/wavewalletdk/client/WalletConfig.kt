package engineering.lightning.wavewalletdk.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// WalletConfig is the typed mirror of the Go facade's JSON config. Only set
// fields are encoded (nulls are omitted), so the daemon's build-tag defaults
// fill in the rest. The @SerialName keys match the Go json tags (snake_case).

@Serializable
data class WalletConfig(
  @SerialName("data_dir") val dataDir: String,
  @SerialName("network") val network: String,

  @SerialName("server_address") val serverAddress: String? = null,
  @SerialName("server_transport") val serverTransport: String? = null,
  @SerialName("server_insecure") val serverInsecure: Boolean? = null,

  @SerialName("wallet_type") val walletType: String? = null,
  @SerialName("wallet_esplora_url") val walletEsploraUrl: String? = null,
  @SerialName("wallet_poll_interval_seconds")
  val walletPollIntervalSeconds: Long? = null,

  @SerialName("swap_server_address") val swapServerAddress: String? = null,
  @SerialName("swap_server_insecure") val swapServerInsecure: Boolean? = null,

  @SerialName("debug_level") val debugLevel: String? = null,
  @SerialName("allow_mainnet") val allowMainnet: Boolean? = null,

  // A remote signer holds the keys of a watch-only wallet. The daemon always
  // dials out to it, and contacts it on create and unlock. The macaroon and
  // certificate paths must be plain file paths, because Go opens them directly;
  // a content:// or file:// URI only fails later, at unlock.
  @SerialName("remote_signer_rpc_host") val remoteSignerRpcHost: String? = null,
  @SerialName("remote_signer_macaroon_path")
  val remoteSignerMacaroonPath: String? = null,
  @SerialName("remote_signer_tls_cert_path")
  val remoteSignerTlsCertPath: String? = null,
  @SerialName("remote_signer_connect_timeout_seconds")
  val remoteSignerConnectTimeoutSeconds: Long? = null,
  @SerialName("remote_signer_request_timeout_seconds")
  val remoteSignerRequestTimeoutSeconds: Long? = null,
) {
  companion object {
    /**
     * Build a signet config for the lightweight (Esplora-backed) wallet. The
     * operator and swap endpoints default to Lightning Labs' public signet
     * deployment; pass empty strings to run sync-only without an operator.
     */
    fun signet(
      dataDir: String,
      esploraUrl: String = "https://mempool.space/signet/api",
      operatorAddress: String = "arkd-signet.testnet.lightningcluster.com:443",
      swapServerAddress: String = "swapd-signet.testnet.lightningcluster.com:443",
    ): WalletConfig = WalletConfig(
      dataDir = dataDir,
      network = "signet",
      walletType = "lwwallet",
      walletEsploraUrl = esploraUrl,
      walletPollIntervalSeconds = 30,
      // The operator and swap endpoints terminate TLS at :443, so they are
      // not insecure; leave the insecure flags unset (false).
      serverAddress = operatorAddress.ifEmpty { null },
      serverTransport = "grpc",
      swapServerAddress = swapServerAddress.ifEmpty { null },
      debugLevel = "info",
    )
  }
}
