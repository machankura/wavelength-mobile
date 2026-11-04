package engineering.lightning.wavewalletdk.client

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletConfigTest {
  private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

  @Test
  fun signetEncodesOperatorAndEsplora() {
    val s = json.encodeToString(
      WalletConfig.serializer(),
      WalletConfig.signet(dataDir = "/tmp/wallet"),
    )
    assertTrue("missing server_address: $s", s.contains("\"server_address\""))
    assertTrue("missing operator host: $s", s.contains("arkd-signet"))
    assertTrue("missing esplora: $s", s.contains("mempool.space/signet"))
    assertTrue("missing network: $s", s.contains("\"network\":\"signet\""))
  }

  @Test
  fun remoteSignerFieldsEncodeWhenSet() {
    val s = json.encodeToString(
      WalletConfig.serializer(),
      WalletConfig(
        dataDir = "/tmp/wallet",
        network = "signet",
        remoteSignerRpcHost = "10.0.2.2:10019",
        remoteSignerMacaroonPath = "/keys/signer.macaroon",
        remoteSignerTlsCertPath = "/keys/tls.cert",
        remoteSignerConnectTimeoutSeconds = 5,
        remoteSignerRequestTimeoutSeconds = 30,
      ),
    )
    assertEquals(
      """{"data_dir":"/tmp/wallet","network":"signet",""" +
        """"remote_signer_rpc_host":"10.0.2.2:10019",""" +
        """"remote_signer_macaroon_path":"/keys/signer.macaroon",""" +
        """"remote_signer_tls_cert_path":"/keys/tls.cert",""" +
        """"remote_signer_connect_timeout_seconds":5,""" +
        """"remote_signer_request_timeout_seconds":30}""",
      s,
    )
  }

  @Test
  fun remoteSignerFieldsAreAbsentWhenUnset() {
    val s = json.encodeToString(
      WalletConfig.serializer(),
      WalletConfig.signet(dataDir = "/tmp/wallet"),
    )
    assertFalse("unexpected signer key: $s", s.contains("remote_signer"))
  }
}
