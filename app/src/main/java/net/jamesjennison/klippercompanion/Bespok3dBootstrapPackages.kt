// Adapted from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later.
// Original: android/app/src/main/java/org/crabcore/u1control/bespok3d/Bespok3dBootstrapPackages.kt
//
// Diverges from Helix's original here: Helix's bundle format wraps the daemon+jinni .b3 archives
// in an outer index.json/index.json.sig pinning their exact filenames and a combined signature
// over that pairing. Only Bespok3d's own release tooling can produce that combined signed index,
// and no public artifact of it exists beyond the specific pairing Helix happened to ship — so it
// can't be reproduced here for a newer daemon/jinni pairing. Each .b3 archive is independently
// signed on its own (manifest.json + manifest.json.sig, verified below against the same pinned
// publisher key), which is the actual proof of Bespok3d authorship; the outer index signature was
// redundant on top of that. This bundle format therefore drops the outer index requirement and
// verifies each archive purely on its own signed manifest — see scripts/build_bespok3d_bootstrap.py
// for how the bundled asset (assets/bespok3d/bootstrap.zip) is produced and independently verified
// before being committed.
package net.jamesjennison.klippercompanion

import android.content.Context
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

data class Bespok3dBootstrapFile(
  val path: String,
  val mode: Int,
  val bytes: ByteArray,
)

data class Bespok3dBootstrapPackage(
  val name: String,
  val version: String,
  val files: List<Bespok3dBootstrapFile>,
)

data class Bespok3dBootstrapSet(
  val daemon: Bespok3dBootstrapPackage,
  val jinni: Bespok3dBootstrapPackage,
)

/**
 * Opens only the release artifacts pinned into this build. Both OpenPGP
 * signatures and every signed payload hash are checked before bytes are exposed.
 */
object Bespok3dBootstrapPackages {
  const val ASSET_PATH = "bespok3d/bootstrap.zip"

  fun load(context: Context): Bespok3dBootstrapSet =
    context.assets.open(ASSET_PATH).use(::load)

  fun load(input: InputStream): Bespok3dBootstrapSet {
    val bundled = readZip(input, MAX_BUNDLE_BYTES, MAX_PACKAGE_BYTES)
    require(bundled.size == 2) { "Bespok3d bootstrap bundle must contain exactly two packages" }
    val packages = bundled.values.map { verifyPackage(it, listed = null) }
    val daemon = packages.singleOrNull { it.name == DAEMON_NAME }
      ?: throw IllegalArgumentException("Bespok3d bootstrap bundle is missing the daemon package")
    val jinni = packages.singleOrNull { it.name == JINNI_NAME }
      ?: throw IllegalArgumentException("Bespok3d bootstrap bundle is missing the jinni package")
    return Bespok3dBootstrapSet(daemon, jinni)
  }

  /**
   * Applies the same pinned-publisher signature, identity, and payload-hash checks to a
   * store package before the daemon is allowed to see it.
   */
  fun verifyOfficialInstallPackage(bytes: ByteArray, name: String, version: String) {
    require(bytes.size <= MAX_STORE_PACKAGE_BYTES) { "Bespok3d package exceeds its size limit" }
    require(PLUGIN_NAME.matches(name)) { "Bespok3d package name is invalid" }
    require(VERSION.matches(version)) { "Bespok3d package version is invalid" }
    verifyPackage(bytes, ListedRelease(name, version), MAX_STORE_UNPACKED_BYTES)
  }

  /** Verifies exact catalog bytes against this app's pinned Bespok3d publisher key. */
  fun verifyOfficialSignature(content: ByteArray, armoredSignature: ByteArray) {
    verifySignature(content, armoredSignature)
  }

  internal fun validateRelativePath(path: String): String {
    require(path.isNotEmpty() && path.length <= 512) { "Bespok3d package path is invalid" }
    require(!path.startsWith('/') && !path.contains('\\')) { "Bespok3d package path is unsafe" }
    require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
      "Bespok3d package path is unsafe"
    }
    return path
  }

  private data class ListedRelease(val name: String, val version: String)

  /**
   * Verifies a single .b3 archive's own signed manifest — the sole cryptographic trust anchor for
   * both call sites here (see the file header for why bootstrap packages no longer also require a
   * combined outer index signature). When [listed] is given (the store-install path, where the
   * caller already has an already-signature-verified catalog entry to check identity against) the
   * manifest's declared name/version must match it exactly; when null (the bootstrap path, which
   * has no such catalog entry) the manifest's own name must simply be one of the two known
   * bootstrap packages, and its version is trusted as declared in the signed manifest itself.
   */
  private fun verifyPackage(
    bytes: ByteArray,
    listed: ListedRelease?,
    maxUnpackedBytes: Int = MAX_PACKAGE_UNPACKED_BYTES,
  ): Bespok3dBootstrapPackage {
    val archive = readZip(ByteArrayInputStream(bytes), maxUnpackedBytes, MAX_STORE_ENTRY_BYTES)
    val manifestBytes = archive[MANIFEST_NAME]
      ?: throw IllegalArgumentException("Bespok3d package has no manifest")
    val signatureBytes = archive[MANIFEST_SIGNATURE_NAME]
      ?: throw IllegalArgumentException("Bespok3d package has no signature")
    verifySignature(manifestBytes, signatureBytes)

    val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
    val name = manifest.getString("name")
    val version = manifest.getString("version")
    if (listed != null) {
      require(name == listed.name) { "Bespok3d package identity mismatch" }
      require(version == listed.version) { "Bespok3d package version mismatch" }
    } else {
      require(name == DAEMON_NAME || name == JINNI_NAME) {
        "Bespok3d bootstrap bundle contains an unexpected package"
      }
      require(VERSION.matches(version)) { "Bespok3d package version is invalid" }
    }
    require(manifest.getString("publisher").uppercase(Locale.US) == OFFICIAL_FINGERPRINT) {
      "Bespok3d package has the wrong publisher"
    }

    val declared = manifest.getJSONArray("files")
    require(declared.length() in 1..MAX_ENTRIES) { "Bespok3d package declares no usable payload" }
    val payload = ArrayList<Bespok3dBootstrapFile>(declared.length())
    val declaredArchivePaths = mutableSetOf<String>()
    for (position in 0 until declared.length()) {
      val file = declared.getJSONObject(position)
      val archivePath = validateRelativePath(file.getString("path"))
      require(declaredArchivePaths.add(archivePath)) { "Bespok3d manifest repeats a file path" }
      val fileBytes = archive[archivePath]
        ?: throw IllegalArgumentException("Bespok3d package is missing $archivePath")
      require(sha256(fileBytes) == file.getString("sha256").lowercase(Locale.US)) {
        "Bespok3d file hash failed for $archivePath"
      }
      val modeText = file.optString("mode", "644")
      require(modeText.matches(Regex("^[0-7]{3,4}$"))) { "Bespok3d file mode is invalid" }
      if (archivePath.startsWith(PAYLOAD_PREFIX)) {
        payload += Bespok3dBootstrapFile(
          path = validateRelativePath(archivePath.removePrefix(PAYLOAD_PREFIX)),
          mode = modeText.toInt(8),
          bytes = fileBytes,
        )
      }
    }
    require(payload.isNotEmpty()) { "Bespok3d package declares no installable payload" }
    val actualSignedPaths = archive.keys
      .filterTo(mutableSetOf()) { it != MANIFEST_NAME && it != MANIFEST_SIGNATURE_NAME }
    require(actualSignedPaths == declaredArchivePaths) {
      "Bespok3d package contains an undeclared file"
    }
    return Bespok3dBootstrapPackage(name, version, payload)
  }

  private fun verifySignature(content: ByteArray, armoredSignature: ByteArray) {
    val signatures = PGPUtil.getDecoderStream(ByteArrayInputStream(armoredSignature)).use { decoded ->
      JcaPGPObjectFactory(decoded).nextObject() as? PGPSignatureList
    } ?: throw IllegalArgumentException("Bespok3d signature is malformed")
    require(signatures.size() == 1) { "Bespok3d signature count is invalid" }
    val signature = signatures[0]
    val keyRings = PGPUtil.getDecoderStream(
      ByteArrayInputStream(OFFICIAL_PUBLIC_KEY.toByteArray(Charsets.US_ASCII)),
    ).use { decoded -> PGPPublicKeyRingCollection(decoded, JcaKeyFingerprintCalculator()) }
    val signingKey = keyRings.getPublicKey(signature.keyID)
      ?: throw IllegalArgumentException("Bespok3d signature was not made by the official key")
    val primaryFingerprint = keyRings.keyRings.asSequence()
      .firstOrNull { it.getPublicKey(signature.keyID) != null }
      ?.publicKey
      ?.fingerprint
      ?.joinToString("") { "%02X".format(it) }
    require(primaryFingerprint == OFFICIAL_FINGERPRINT) {
      "Bespok3d signature was not made by the pinned publisher"
    }
    signature.init(JcaPGPContentVerifierBuilderProvider().setProvider(BouncyCastleProvider()), signingKey)
    signature.update(content)
    require(signature.verify()) { "Bespok3d signature verification failed" }
  }

  private fun readZip(input: InputStream, maxTotalBytes: Int, maxEntryBytes: Int): Map<String, ByteArray> {
    val files = linkedMapOf<String, ByteArray>()
    var totalBytes = 0
    ZipInputStream(input.buffered()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        require(!entry.isDirectory) { "Bespok3d archive contains a directory entry" }
        val name = validateRelativePath(entry.name)
        require(files.size < MAX_ENTRIES && !files.containsKey(name)) {
          "Bespok3d archive contains too many or duplicate entries"
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
          val count = zip.read(buffer)
          if (count < 0) break
          output.write(buffer, 0, count)
          totalBytes += count
          require(output.size() <= maxEntryBytes && totalBytes <= maxTotalBytes) {
            "Bespok3d archive exceeds its size limit"
          }
        }
        files[name] = output.toByteArray()
        zip.closeEntry()
      }
    }
    return files
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private const val MANIFEST_NAME = "manifest.json"
  private const val MANIFEST_SIGNATURE_NAME = "manifest.json.sig"
  private const val PAYLOAD_PREFIX = "files/"
  private const val DAEMON_NAME = "bespok3d-daemon"
  private const val JINNI_NAME = "bespok3d-jinni-snapmaker-u1"
  private const val OFFICIAL_FINGERPRINT = "679939555819FB5F6423DC68C4388E76BFA9B4E0"
  private const val MAX_ENTRIES = 512
  private const val MAX_BUNDLE_BYTES = 12 * 1024 * 1024
  private const val MAX_PACKAGE_BYTES = 10 * 1024 * 1024
  private const val MAX_PACKAGE_UNPACKED_BYTES = 16 * 1024 * 1024
  private const val MAX_STORE_PACKAGE_BYTES = 64 * 1024 * 1024
  private const val MAX_STORE_UNPACKED_BYTES = 128 * 1024 * 1024
  private const val MAX_STORE_ENTRY_BYTES = 64 * 1024 * 1024
  private val PLUGIN_NAME = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
  private val VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")

  private const val OFFICIAL_PUBLIC_KEY = """-----BEGIN PGP PUBLIC KEY BLOCK-----

mQINBGoe+wUBEADJjkI85zRmpx2XmaU2e7eb1OGR0Khw0z5dByvQ0odMovBhInK4
mmWR1d+DL2yLt8QNh421LGuBd1iWXSx6jTKPi8PcxBSxfhfJydJWIji58HFN/sTd
dyk+I20Ln9k0B0A8BpLnSzVUTEKYrqYiRSAJcPVkrA1myp3X4kUt/DyqERHE/HF+
bmwMsW0pgpdvs1umUOV7EdpADWorfWcWFOGKFJSGbd8K3hjFR9IPt6sPeKsUGU5U
01hdFp89a/DAX/Q2LGQP/v+WNUpNQtj6CMPRPc2sjNcyH16m9EsIugkWoimxsoSk
gKAoINq+gQtp/qckQiXoApXnB1ewQfWmz0C+zAoSL/qXd/QEpStZhgvlDX4eOeUl
LdOLleRnwqorNgz4Qr96C1uETJF2ew8iZm5v4nPOidP9eG0OOrYsiHjmiOubD3A9
V6GLGiaVuRNJ1dIew615bOmOhQY/8Sa32QoUeDYVDEL4pZxyk+fuxObvBGfvRFdG
wuuVvEXX0L+Ne7KSHSVUXQGGobjfrektB8OSOFpAM9iGAhtH/lCXq8OjogjzoetE
47JflKHZLmAaspl16WrsRk+GPxGwAf8ckAs7GxgaxbTECkeauG2Iqcmme1k+3kmK
NQBrQq5NMz4A+OMN0g/4BO/S8RkLtxC1cjDCZ72MNgzh4lt+to91Vr8R6wARAQAB
tFFCZXNwb2szZCBSZWdpc3RyeSBTaWduaW5nIEtleSAob2ZmaWNpYWwgbGlzdCBz
aWduaW5nIGtleSkgPHJlZ2lzdHJ5QGJlc3BvazNkLm9yZz6JAm0EEwEIAFcWIQRn
mTlVWBn7X2Qj3GjEOI52v6m04AUCah77BRsUgAAAAAAEAA5tYW51MiwyLjUrMS4x
MiwwLDMCGwMFCwkIBwICIgIGFQoJCAsCBBYCAwECHgcCF4AACgkQxDiOdr+ptOAq
7BAAlCoYtauXk8As3ajW2IJLUOYHxtal+h4UUaXiiNKwgtZBbnIZByfDZ68veDoP
SQ3PfKLKgypuJqGNRKCORiP/zw2Co7AqwHgsG9G5B48SsDIQlRX1nad5Acc5XyHN
GKqDu0mxQd9GVU96zhOknZoF4f2yrrHhrv1OYrbzHsp9ktyddfyO4izurs0zPh6B
6ln1AgbOwc+yMG3NjqpmjEgXn/5B+WCXU/9wwOC8TmOGdZHtdVgzExZEbEgRkqe+
Wzq8Or8at+CLn2BCyYyKJcRQVDNYubjpE0BsYw4t/n01PwDKlgk4Kc4JPmjAXgqh
7ZJDegBIb14+rhwptKBpr/bGHJxJQBqAPmeqIPjNYNSkXlVbToS8RRsy5/7wWm7E
UKQChOBY4CZ9+d6H7IEIkj6Cay0NRDNRGBJ8H1ePsA9P8xCU567F0iEXwKKmWPiL
lB1lLI5KScW7kfx9iHQ8NKGxhmiDbB7J/Zd+et5WZIKONit+xifU4YVOpELbhRYA
6G7i1pFOQhXLZG832pKMqHCPCpBqT5imrJ2NKYqCHyZ2aVi3gK6mpYWnzSh5Xcpv
HGkr0kOBhL1zF6g4Cn/wU26QI4mQ2eEOqBRhUTFBbBZ7fQTbFgA4AV9Gwi8L6tNB
At5hzMkILtyaJ1gDVIBv/Qmet5QtOB22Sq54rRL4W+igroM=
=DgD7
-----END PGP PUBLIC KEY BLOCK-----"""
}
