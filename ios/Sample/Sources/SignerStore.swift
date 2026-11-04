import Foundation

/// Where a remote signer is, and the plain file paths the daemon reads to reach it.
struct RemoteSigner {
    let host: String
    let macaroonPath: String
    let tlsCertPath: String
}

struct SignerStoreError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}

/// Keeps the signer's address and credentials between launches. The daemon opens
/// the macaroon and certificate by path on every start, so they must be real
/// files. They are excluded from backup and readable only while the device is
/// unlocked; deleting the app forgets them.
struct SignerStore {
    let dir: URL

    private var hostURL: URL { dir.appendingPathComponent("host") }
    private var macaroonURL: URL { dir.appendingPathComponent("signer.macaroon") }
    private var tlsCertURL: URL { dir.appendingPathComponent("tls.cert") }

    func load() -> RemoteSigner? {
        guard let data = try? Data(contentsOf: hostURL) else { return nil }
        return signer(host: String(decoding: data, as: UTF8.self))
    }

    /// Validates what was pasted and writes it. The macaroon is binary, so it
    /// arrives as hex.
    func save(host: String, macaroonHex: String, tlsCert: String) throws -> RemoteSigner {
        let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedHost.isEmpty else {
            throw SignerStoreError("the signer host is required")
        }
        guard tlsCert.contains("BEGIN CERTIFICATE") else {
            throw SignerStoreError("the signer certificate must be PEM text")
        }
        let macaroon = try Self.bytes(fromHex: macaroonHex)

        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var excluded = dir
        try excluded.setResourceValues(values)

        try write(Data(trimmedHost.utf8), to: hostURL)
        try write(macaroon, to: macaroonURL)
        try write(Data(tlsCert.utf8), to: tlsCertURL)
        return signer(host: trimmedHost)
    }

    private func signer(host: String) -> RemoteSigner {
        RemoteSigner(host: host, macaroonPath: macaroonURL.path, tlsCertPath: tlsCertURL.path)
    }

    private func write(_ data: Data, to url: URL) throws {
        try data.write(to: url, options: [.atomic, .completeFileProtection])
    }

    private static func bytes(fromHex hex: String) throws -> Data {
        let invalid = SignerStoreError("the signer macaroon must be hex")
        let clean = hex.filter { !$0.isWhitespace }
        guard !clean.isEmpty, clean.count % 2 == 0 else { throw invalid }
        var data = Data(capacity: clean.count / 2)
        var index = clean.startIndex
        while index < clean.endIndex {
            let next = clean.index(index, offsetBy: 2)
            guard let byte = UInt8(clean[index..<next], radix: 16) else { throw invalid }
            data.append(byte)
            index = next
        }
        return data
    }
}
