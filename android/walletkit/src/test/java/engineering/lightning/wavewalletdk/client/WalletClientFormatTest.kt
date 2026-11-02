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
}
