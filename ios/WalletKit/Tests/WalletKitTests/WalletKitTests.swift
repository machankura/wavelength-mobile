import XCTest
@testable import WalletKit

final class WalletKitTests: XCTestCase {
    private let password = Data("hunter2".utf8)

    private func json<T: Encodable>(_ value: T) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return String(decoding: try encoder.encode(value), as: UTF8.self)
    }

    private func decode<T: Decodable>(_ type: T.Type, _ text: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(text.utf8))
    }

    // MARK: WalletConfig

    func testRemoteSignerKeysEncodeWhenSet() throws {
        var config = WalletConfig.signet(dataDir: "/tmp/wallet")
        config.remoteSignerRPCHost = "localhost:10019"
        config.remoteSignerMacaroonPath = "/keys/signer.macaroon"
        config.remoteSignerTLSCertPath = "/keys/tls.cert"
        config.remoteSignerConnectTimeoutSeconds = 5
        config.remoteSignerRequestTimeoutSeconds = 30

        let text = try json(config)
        XCTAssertTrue(text.contains(#""remote_signer_rpc_host":"localhost:10019""#), text)
        XCTAssertTrue(text.contains(#""remote_signer_macaroon_path":"/keys/signer.macaroon""#), text)
        XCTAssertTrue(text.contains(#""remote_signer_tls_cert_path":"/keys/tls.cert""#), text)
        XCTAssertTrue(text.contains(#""remote_signer_connect_timeout_seconds":5"#), text)
        XCTAssertTrue(text.contains(#""remote_signer_request_timeout_seconds":30"#), text)
    }

    func testRemoteSignerKeysAreAbsentWhenUnset() throws {
        let text = try json(WalletConfig.signet(dataDir: "/tmp/wallet"))
        XCTAssertFalse(text.contains("remote_signer"), text)
    }

    // MARK: createWallet requests

    func testWatchOnlyBundleRequestJSON() throws {
        let req = try WalletClient.createWalletReq(
            walletPassword: password,
            birthday: Date(timeIntervalSince1970: 1_767_225_600),
            watchOnlyBundle: "bundle text"
        )
        XCTAssertEqual(
            try json(req),
            #"{"Birthday":"2026-01-01T00:00:00Z","Descriptors":[],"Mnemonic":[],"# +
                #""OverrideIdentity":false,"WalletPassword":"aHVudGVyMg==","# +
                #""WatchOnlyBundle":"YnVuZGxlIHRleHQ="}"#
        )
    }

    func testWatchOnlyManifestRequestJSON() throws {
        let req = try WalletClient.createWalletReq(
            walletPassword: password,
            watchOnlyManifest: "manifest",
            authKeys: "auth keys",
            overrideIdentity: true
        )
        XCTAssertEqual(
            try json(req),
            #"{"AuthKeys":"YXV0aCBrZXlz","Descriptors":[],"Mnemonic":[],"# +
                #""OverrideIdentity":true,"WalletPassword":"aHVudGVyMg==","# +
                #""WatchOnlyManifest":"bWFuaWZlc3Q="}"#
        )
    }

    func testDescriptorRequestJSONHasNoWatchOnlyKeys() throws {
        let req = try WalletClient.createWalletReq(
            walletPassword: password,
            descriptors: ["tr(x)"]
        )
        XCTAssertEqual(
            try json(req),
            #"{"Descriptors":["tr(x)"],"Mnemonic":[],"OverrideIdentity":false,"# +
                #""WalletPassword":"aHVudGVyMg=="}"#
        )
    }

    func testCreateWalletRulesAreEnforced() {
        let invalid: [(String, () throws -> CreateWalletReq)] = [
            ("mnemonic and descriptors are mutually exclusive", {
                try WalletClient.createWalletReq(
                    walletPassword: self.password, mnemonic: ["word"], descriptors: ["d"]
                )
            }),
            ("a watch-only wallet cannot also have a mnemonic or descriptors", {
                try WalletClient.createWalletReq(
                    walletPassword: self.password, mnemonic: ["word"], watchOnlyBundle: "b"
                )
            }),
            ("a watch-only wallet cannot also have a mnemonic or descriptors", {
                try WalletClient.createWalletReq(
                    walletPassword: self.password, descriptors: ["d"],
                    watchOnlyManifest: "m", authKeys: "a"
                )
            }),
            ("a watch-only wallet needs a wallet password", {
                try WalletClient.createWalletReq(walletPassword: Data(), watchOnlyBundle: "b")
            }),
            ("watchOnlyBundle must not be empty", {
                try WalletClient.createWalletReq(walletPassword: self.password, watchOnlyBundle: "")
            }),
            ("watchOnlyManifest must not be empty", {
                try WalletClient.createWalletReq(
                    walletPassword: self.password, watchOnlyManifest: "", authKeys: "a"
                )
            }),
            ("authKeys must not be empty", {
                try WalletClient.createWalletReq(
                    walletPassword: self.password, watchOnlyManifest: "m", authKeys: ""
                )
            }),
            ("exactly one of watchOnlyBundle and watchOnlyManifest is required", {
                try WalletClient.createWalletReq(
                    walletPassword: self.password, watchOnlyBundle: "b",
                    watchOnlyManifest: "m", authKeys: "a"
                )
            }),
            ("exactly one of watchOnlyBundle and watchOnlyManifest is required", {
                try WalletClient.createWalletReq(walletPassword: self.password, authKeys: "a")
            }),
            ("exactly one of watchOnlyBundle and watchOnlyManifest is required", {
                try WalletClient.createWalletReq(walletPassword: self.password, overrideIdentity: true)
            }),
            ("a watchOnlyManifest requires its authKeys", {
                try WalletClient.createWalletReq(walletPassword: self.password, watchOnlyManifest: "m")
            }),
            ("authKeys and overrideIdentity apply only to a watchOnlyManifest", {
                try WalletClient.createWalletReq(
                    walletPassword: self.password, watchOnlyBundle: "b", authKeys: "a"
                )
            }),
            ("authKeys and overrideIdentity apply only to a watchOnlyManifest", {
                try WalletClient.createWalletReq(
                    walletPassword: self.password, watchOnlyBundle: "b", overrideIdentity: true
                )
            }),
        ]
        for (message, build) in invalid {
            XCTAssertThrowsError(try build(), message) { error in
                XCTAssertEqual((error as? WalletError)?.message, message)
            }
        }
    }

    // MARK: lenient decoding

    func testCreateWalletResultReadsGoNullsAndMissingFields() throws {
        let nulls = try decode(
            CreateWalletResult.self,
            #"{"Mnemonic":null,"Descriptors":null,"IdentityPubKey":"02ab"}"#
        )
        XCTAssertEqual(nulls.mnemonic, [])
        XCTAssertEqual(nulls.descriptors, [])
        XCTAssertEqual(nulls.identityPubKey, "02ab")

        let old = try decode(CreateWalletResult.self, "{}")
        XCTAssertEqual(old.rootFingerprint, "")
        XCTAssertEqual(old.manifestHash, "")
        XCTAssertFalse(old.recoveryRan)

        let manifest = try decode(CreateWalletResult.self, #"{"ManifestHash":"abc"}"#)
        XCTAssertEqual(manifest.manifestHash, "abc")
    }

    func testListWalletDescriptorsResultReadsGoNulls() throws {
        let result = try decode(ListWalletDescriptorsResult.self, #"{"Descriptors":null}"#)
        XCTAssertEqual(result.descriptors, [])
        XCTAssertEqual(result.rootFingerprint, "")
        XCTAssertFalse(result.includesPrivate)
    }

    func testInfoManifestHashIsOptional() throws {
        let fields = #""Version":"v","Commit":"c","Network":"signet","BlockHeight":1,"# +
            #""ServerConnected":true,"WalletType":"lwwallet","WalletState":3,"# +
            #""IdentityPubKey":"02ab""#
        XCTAssertEqual(try decode(Info.self, "{\(fields)}").manifestHash, "")
        XCTAssertEqual(
            try decode(Info.self, #"{\#(fields),"ManifestHash":"abc"}"#).manifestHash,
            "abc"
        )
    }
}
