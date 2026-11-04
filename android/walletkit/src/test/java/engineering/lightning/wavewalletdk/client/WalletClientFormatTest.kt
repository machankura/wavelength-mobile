package engineering.lightning.wavewalletdk.client

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WalletClientFormatTest {
  @Test
  fun birthdayIsRfc3339InUtc() {
    // Go's time.Time decodes this form; the daemon reads it as the birthday
    // of a descriptor import.
    assertEquals("2026-01-01T00:00:00Z", WalletClient.rfc3339(1_767_225_600))
    assertEquals("1970-01-01T00:00:00Z", WalletClient.rfc3339(0))
  }

  @Test
  fun goNullSlicesDecodeAsEmptyLists() {
    // Go marshals a nil slice as null, which is what a descriptor import
    // returns for its mnemonic.
    val res = WalletClient.DEFAULT_JSON.decodeFromString(
      CreateWalletResult.serializer(),
      """{"Mnemonic":null,"Descriptors":null,"IdentityPubKey":"02ab"}""",
    )
    assertEquals(emptyList<String>(), res.mnemonic)
    assertEquals(emptyList<String>(), res.descriptors)
    assertEquals("02ab", res.identityPubKey)
  }

  @Test
  fun watchOnlyBundleRequestJson() {
    assertEquals(
      """{"WalletPassword":"aHVudGVyMg==",""" +
        """"Birthday":"2026-01-01T00:00:00Z",""" +
        """"WatchOnlyBundle":"YnVuZGxlIHRleHQ="}""",
      json(
        createWalletReq(
          birthdayUnixSeconds = 1_767_225_600,
          watchOnlyBundle = "bundle text",
        ),
      ),
    )
  }

  @Test
  fun watchOnlyManifestRequestJson() {
    assertEquals(
      """{"WalletPassword":"aHVudGVyMg==",""" +
        """"WatchOnlyManifest":"bWFuaWZlc3Q=",""" +
        """"AuthKeys":"YXV0aCBrZXlz","OverrideIdentity":true}""",
      json(
        createWalletReq(
          watchOnlyManifest = "manifest",
          authKeys = "auth keys",
          overrideIdentity = true,
        ),
      ),
    )
  }

  @Test
  fun descriptorRequestJsonHasNoWatchOnlyKeys() {
    assertEquals(
      """{"WalletPassword":"aHVudGVyMg==","Descriptors":["tr(x)"]}""",
      json(createWalletReq(descriptors = listOf("tr(x)"))),
    )
  }

  @Test
  fun watchOnlyRulesAreEnforced() {
    val refused = listOf(
      "a watch-only wallet cannot also have a mnemonic or descriptors" to {
        createWalletReq(watchOnlyBundle = "b", mnemonic = listOf("word"))
      },
      "a watch-only wallet cannot also have a mnemonic or descriptors" to {
        createWalletReq(watchOnlyManifest = "m", authKeys = "a", descriptors = listOf("d"))
      },
      "a watch-only wallet needs a wallet password" to {
        createWalletReq(watchOnlyBundle = "b", walletPassword = ByteArray(0))
      },
      "watchOnlyBundle must not be empty" to {
        createWalletReq(watchOnlyBundle = "")
      },
      "watchOnlyManifest must not be empty" to {
        createWalletReq(watchOnlyManifest = "", authKeys = "a")
      },
      "authKeys must not be empty" to {
        createWalletReq(watchOnlyManifest = "m", authKeys = "")
      },
      "exactly one of watchOnlyBundle and watchOnlyManifest is required" to {
        createWalletReq(watchOnlyBundle = "b", watchOnlyManifest = "m", authKeys = "a")
      },
      "exactly one of watchOnlyBundle and watchOnlyManifest is required" to {
        createWalletReq(authKeys = "a")
      },
      "exactly one of watchOnlyBundle and watchOnlyManifest is required" to {
        createWalletReq(overrideIdentity = true)
      },
      "a watchOnlyManifest requires its authKeys" to {
        createWalletReq(watchOnlyManifest = "m")
      },
      "authKeys and overrideIdentity apply only to a watchOnlyManifest" to {
        createWalletReq(watchOnlyBundle = "b", authKeys = "a")
      },
      "authKeys and overrideIdentity apply only to a watchOnlyManifest" to {
        createWalletReq(watchOnlyBundle = "b", overrideIdentity = true)
      },
    )
    for ((message, build) in refused) {
      val e = assertThrows(IllegalArgumentException::class.java) { build() }
      assertEquals(message, e.message)
    }
  }

  @Test
  fun manifestHashDecodesAndDefaultsToEmpty() {
    val json = WalletClient.DEFAULT_JSON
    assertEquals(
      "abc",
      json.decodeFromString(Info.serializer(), """{"ManifestHash":"abc"}""").manifestHash,
    )
    assertEquals("", json.decodeFromString(Info.serializer(), "{}").manifestHash)
    assertEquals(
      "abc",
      json.decodeFromString(
        CreateWalletResult.serializer(),
        """{"ManifestHash":"abc"}""",
      ).manifestHash,
    )
    assertEquals(
      "",
      json.decodeFromString(CreateWalletResult.serializer(), "{}").manifestHash,
    )
  }

  private fun createWalletReq(
    walletPassword: ByteArray = "hunter2".toByteArray(),
    mnemonic: List<String> = emptyList(),
    descriptors: List<String> = emptyList(),
    birthdayUnixSeconds: Long? = null,
    watchOnlyBundle: String? = null,
    watchOnlyManifest: String? = null,
    authKeys: String? = null,
    overrideIdentity: Boolean = false,
  ): CreateWalletReq = WalletClient.createWalletReq(
    walletPassword,
    mnemonic,
    descriptors,
    birthdayUnixSeconds,
    watchOnlyBundle,
    watchOnlyManifest,
    authKeys,
    overrideIdentity,
    toBase64 = { Base64.getEncoder().encodeToString(it) },
  )

  private fun json(req: CreateWalletReq): String =
    WalletClient.DEFAULT_JSON.encodeToString(CreateWalletReq.serializer(), req)
}
