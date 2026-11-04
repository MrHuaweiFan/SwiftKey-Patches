package util

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkSigner
import app.morphe.patcher.apk.ApkSigner.PrivateKeyCertificatePair
import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.patch.loadPatchesFromJar
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Applies a built patches bundle (.mpp) to a SwiftKey APK and signs the result.
 *
 * Usage:
 *   ApkRunner <patches.mpp> <input.apk> <output.apk> <defaults|all> <keystore-file> <keystore-password> <alias>
 *
 * The mode selects the patch set:
 *  - "defaults": every patch whose default is on (the out-of-the-box experience)
 *  - "all": every patch, including the opt-in ones
 *
 * The keystore is created on first use (PKCS12) and reused afterwards, so all
 * builds made from the same checkout share one signature.
 */
fun main(args: Array<String>) {
    if (args.size < 7) {
        System.err.println(
            "Usage: ApkRunner <patches.mpp> <input.apk> <output.apk> <defaults|all> " +
                "<keystore-file> <keystore-password> <alias>",
        )
        kotlin.system.exitProcess(2)
    }
    val patchesFile = File(args[0])
    val inputApk = File(args[1])
    val outputApk = File(args[2])
    val mode = args[3]
    val keystoreFile = File(args[4])
    val keystorePassword = args[5]
    val alias = args[6]

    require(mode == "defaults" || mode == "all") { "unknown mode: $mode" }

    // ---- 1. Load the bundle and select the patches ------------------------------------------
    val patches = loadPatchesFromJar(setOf(patchesFile))
    val included = patches
        .filter { it.name != null }
        .filter { if (mode == "all") true else it.default }
        .toSet()
    println("Loaded ${patches.size} patches from ${patchesFile.name}, applying ${included.size} ($mode):")
    included.sortedBy { it.name }.forEach { println("  - ${it.name}") }

    // ---- 2. Apply the patches ---------------------------------------------------------------
    val unsigned = File(outputApk.parentFile, outputApk.nameWithoutExtension + "-unsigned.apk")
    unsigned.delete()
    inputApk.copyTo(unsigned, overwrite = true)

    val temporaryFiles = File(outputApk.parentFile, "morphe-temporary").apply { deleteRecursively() }
    val patcher = Patcher(
        PatcherConfig(
            apkFile = unsigned,
            temporaryFilesPath = temporaryFiles,
        ),
    )
    try {
        patcher += included

        var failed = 0
        kotlinx.coroutines.runBlocking {
            patcher().collect { result ->
                val exception = result.exception
                if (exception != null) {
                    failed++
                    System.err.println("PATCH FAILED: ${result.patch.name}")
                    exception.printStackTrace()
                } else {
                    println("PATCH OK: ${result.patch.name}")
                }
            }
        }
        if (failed > 0) {
            System.err.println("$failed patches failed; aborting without signing.")
            kotlin.system.exitProcess(1)
        }

        val result = patcher.get()
        ApkUtils.run {
            result.applyTo(unsigned)
        }
    } finally {
        patcher.close()
    }

    // ---- 3. Sign the patched APK -------------------------------------------------------------
    val pair = loadOrCreateKeyPair(keystoreFile, keystorePassword, alias)
    ApkSigner.newApkSigner("Morphe", pair).signApk(unsigned, outputApk)
    unsigned.delete()
    temporaryFiles.deleteRecursively()

    println("Signed ${outputApk.absolutePath} (${outputApk.length()} bytes)")
}

/**
 * Loads the signing key from the keystore, creating the keystore (RSA 4096,
 * valid for 30 years) on first use.
 */
private fun loadOrCreateKeyPair(
    keystoreFile: File,
    password: String,
    alias: String,
): PrivateKeyCertificatePair {
    val keyStore = KeyStore.getInstance("PKCS12")
    if (keystoreFile.exists()) {
        keystoreFile.inputStream().use { stream ->
            keyStore.load(stream, password.toCharArray())
        }
        val key = keyStore.getKey(alias, password.toCharArray()) as PrivateKey
        val certificate = keyStore.getCertificate(alias) as X509Certificate
        return PrivateKeyCertificatePair(key, certificate)
    }

    val pair = ApkSigner.newPrivateKeyCertificatePair(
        "Morphe",
        Date(System.currentTimeMillis() + 30L * 365 * 24 * 60 * 60 * 1000),
    )
    keyStore.load(null, null)
    keyStore.setKeyEntry(alias, pair.privateKey, password.toCharArray(), arrayOf(pair.certificate))
    keystoreFile.parentFile?.mkdirs()
    keystoreFile.outputStream().use { stream ->
        keyStore.store(stream, password.toCharArray())
    }
    println("Created new signing keystore at ${keystoreFile.absolutePath}")
    return pair
}
