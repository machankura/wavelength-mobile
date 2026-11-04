package com.example.wavewalletdksample.ui.main

import java.io.File

/** Where a remote signer is, and the plain file paths the daemon reads to reach it. */
internal data class RemoteSigner(
  val host: String,
  val macaroonPath: String,
  val tlsCertPath: String,
)

/**
 * Keeps the signer's address and credentials between launches. The daemon opens
 * the macaroon and certificate by path on every start, so they must be real
 * files. Point [dir] at noBackupFilesDir so they never leave the device, and
 * clear app data to forget them.
 */
internal class SignerStore(private val dir: File) {
  private val hostFile = File(dir, "host")
  private val macaroonFile = File(dir, "signer.macaroon")
  private val tlsCertFile = File(dir, "tls.cert")

  fun load(): RemoteSigner? =
    if (hostFile.exists()) signer(hostFile.readText()) else null

  /** Validates what was pasted and writes it. The macaroon is binary, so it arrives as hex. */
  fun save(host: String, macaroonHex: String, tlsCert: String): RemoteSigner {
    require(host.isNotBlank()) { "the signer host is required" }
    require(tlsCert.contains("BEGIN CERTIFICATE")) {
      "the signer certificate must be PEM text"
    }
    val macaroon = hexToBytes(macaroonHex)

    dir.mkdirs()
    dir.restrictToOwner()
    writeOwnerOnly(hostFile, host.trim().toByteArray())
    writeOwnerOnly(macaroonFile, macaroon)
    writeOwnerOnly(tlsCertFile, tlsCert.toByteArray())
    return signer(host.trim())
  }

  private fun signer(host: String) =
    RemoteSigner(host, macaroonFile.absolutePath, tlsCertFile.absolutePath)

  private fun writeOwnerOnly(file: File, bytes: ByteArray) {
    file.writeBytes(bytes)
    file.restrictToOwner()
  }

  private fun File.restrictToOwner() {
    setReadable(false, false)
    setReadable(true, true)
    setWritable(false, false)
    setWritable(true, true)
    if (isDirectory) {
      setExecutable(false, false)
      setExecutable(true, true)
    }
  }

  private fun hexToBytes(hex: String): ByteArray {
    val clean = hex.filterNot(Char::isWhitespace)
    require(
      clean.isNotEmpty() && clean.length % 2 == 0 &&
        clean.all { it.digitToIntOrNull(16) != null },
    ) { "the signer macaroon must be hex" }
    return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  }
}
