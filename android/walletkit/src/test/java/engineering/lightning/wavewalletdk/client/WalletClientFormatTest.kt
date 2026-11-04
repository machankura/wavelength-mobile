package engineering.lightning.wavewalletdk.client

import org.junit.Assert.assertEquals
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
}
