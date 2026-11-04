import Foundation

// WalletConfig is the typed mirror of the Go facade's JSON config. Optional
// properties that are nil are omitted by JSONEncoder, so the daemon's build-tag
// defaults fill in the rest. The CodingKeys match the Go json tags (snake_case).

public struct WalletConfig: Encodable, Sendable {
    public var dataDir: String
    public var network: String
    public var serverAddress: String?
    public var serverTransport: String?
    public var serverInsecure: Bool?
    public var walletType: String?
    public var walletEsploraURL: String?
    public var walletPollIntervalSeconds: Int64?
    public var swapServerAddress: String?
    public var debugLevel: String?
    public var allowMainnet: Bool?

    // A remote signer holds the keys of a watch-only wallet. The daemon always
    // dials out to it, and contacts it on create and unlock. The macaroon and
    // certificate paths must be plain file paths (`URL.path`), because Go opens
    // them directly; a file:// URL string only fails later, at unlock.
    public var remoteSignerRPCHost: String?
    public var remoteSignerMacaroonPath: String?
    public var remoteSignerTLSCertPath: String?
    public var remoteSignerConnectTimeoutSeconds: Int64?
    public var remoteSignerRequestTimeoutSeconds: Int64?

    enum CodingKeys: String, CodingKey {
        case dataDir = "data_dir"
        case network = "network"
        case serverAddress = "server_address"
        case serverTransport = "server_transport"
        case serverInsecure = "server_insecure"
        case walletType = "wallet_type"
        case walletEsploraURL = "wallet_esplora_url"
        case walletPollIntervalSeconds = "wallet_poll_interval_seconds"
        case swapServerAddress = "swap_server_address"
        case debugLevel = "debug_level"
        case allowMainnet = "allow_mainnet"
        case remoteSignerRPCHost = "remote_signer_rpc_host"
        case remoteSignerMacaroonPath = "remote_signer_macaroon_path"
        case remoteSignerTLSCertPath = "remote_signer_tls_cert_path"
        case remoteSignerConnectTimeoutSeconds = "remote_signer_connect_timeout_seconds"
        case remoteSignerRequestTimeoutSeconds = "remote_signer_request_timeout_seconds"
    }

    /// A signet config for the lightweight (Esplora-backed) wallet. Endpoints
    /// default to Lightning Labs' public signet deployment; pass empty strings
    /// to run sync-only without an operator.
    public static func signet(
        dataDir: String,
        esploraURL: String = "https://mempool.space/signet/api",
        operatorAddress: String = "arkd-signet.testnet.lightningcluster.com:443",
        swapServerAddress: String = "swapd-signet.testnet.lightningcluster.com:443"
    ) -> WalletConfig {
        WalletConfig(
            dataDir: dataDir,
            network: "signet",
            // The operator and swap endpoints terminate TLS at :443, so they
            // are not insecure; leave the flag unset.
            serverAddress: operatorAddress.isEmpty ? nil : operatorAddress,
            serverTransport: "grpc",
            serverInsecure: nil,
            walletType: "lwwallet",
            walletEsploraURL: esploraURL,
            walletPollIntervalSeconds: 30,
            swapServerAddress: swapServerAddress.isEmpty ? nil : swapServerAddress,
            debugLevel: "info"
        )
    }
}
