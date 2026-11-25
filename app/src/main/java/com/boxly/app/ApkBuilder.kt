package com.boxly.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.TypedValue
import com.android.apksig.ApkSigner
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

data class BuildResult(val label: String, val pkg: String, val file: File, val iconApplied: Boolean)

/**
 * Builds a new APK by copying this app's own APK, swapping assets/www, patching the package id and
 * label in-place (same length) inside the binary manifest, then signing with a key kept in Android Keystore.
 */
object ApkBuilder {
    const val PKG_PLACEHOLDER = "com.boxly.pkg00000000"
    val LABEL_PLACEHOLDER = "Boxly".padEnd(32)
    private const val ALIAS = "boxly_signing_key"

    fun newPackageId(): String {
        val c = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return "com.boxly.pkg" + (1..8).map { c[r.nextInt(c.length)] }.joinToString("")
    }

    fun cleanLabel(n: String): String {
        val s = n.trim().filter { it.code in 32..126 }.take(32).trim()
        return if (s.isEmpty()) "My App" else s
    }

    fun build(ctx: Context, name: String, files: Map<String, ByteArray>, icon: IconAssets?, url: String?, progress: (Int, Int) -> Unit): BuildResult {
        progress(0, 10)
        val label = cleanLabel(name)
        val pkg = newPackageId()
        val dir = File(ctx.filesDir, "apks").apply { mkdirs() }
        val tmp = File(ctx.cacheDir, "patched.apk")
        val safe = label.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "app" }
        val out = File(dir, "${safe}_${System.currentTimeMillis()}.apk")

        val iconOk = patch(File(ctx.applicationInfo.sourceDir), tmp, label, pkg, files, icon,
            resPath(ctx, R.drawable.ic_launcher_fg), resPath(ctx, R.drawable.ic_launcher_bg), url)
        progress(2, 65)
        sign(tmp, out)
        tmp.delete()
        progress(3, 100)
        return BuildResult(label, pkg, out, iconOk)
    }

    /** Path of a resource file inside the APK (works even if AGP renamed/moved it). */
    private fun resPath(ctx: Context, id: Int): String? = runCatching {
        val tv = TypedValue(); ctx.resources.getValue(id, tv, true); tv.string?.toString()
    }.getOrNull()

    private fun patch(
        base: File, out: File, label: String, pkg: String, files: Map<String, ByteArray>,
        icon: IconAssets?, fgPath: String?, bgPath: String?, url: String?
    ): Boolean {
        var iconHits = 0
        ZipFile(base).use { zf ->
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                for (e in zf.entries().asSequence()) {
                    val n = e.name
                    if (e.isDirectory) continue
                    if (n.startsWith("META-INF/") && (n.endsWith(".MF") || n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) continue
                    if (n.startsWith("assets/www/") || n.startsWith("assets/ui/") || n == "assets/boxly_runner" || n == "assets/boxly_url") continue
                    var data = zf.getInputStream(e).readBytes()
                    if (icon != null && n == fgPath) { data = icon.fg; iconHits++ }
                    else if (icon != null && n == bgPath) { data = icon.bg; iconHits++ }
                    if (n == "AndroidManifest.xml") {
                        replaceAll(data, PKG_PLACEHOLDER, pkg)
                        replaceAll(data, LABEL_PLACEHOLDER, label.padEnd(32))
                    } else if (n == "resources.arsc") {
                        replaceAll(data, PKG_PLACEHOLDER, pkg)
                    }
                    zos.putNextEntry(ZipEntry(n)); zos.write(data); zos.closeEntry()
                }
                zos.putNextEntry(ZipEntry("assets/boxly_runner")); zos.closeEntry()
                if (url != null) { zos.putNextEntry(ZipEntry("assets/boxly_url")); zos.write(url.toByteArray()); zos.closeEntry() }
                for ((p, b) in files) {
                    zos.putNextEntry(ZipEntry("assets/www/" + p.trimStart('/')))
                    zos.write(b); zos.closeEntry()
                }
            }
        }
        return iconHits == 2
    }

    private fun sign(input: File, output: File) {
        ApkSigner.Builder(listOf(signerConfig()))
            .setInputApk(input).setOutputApk(output)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(true).setV2SigningEnabled(true)
            .setV3SigningEnabled(false).setV4SigningEnabled(false)
            .setCreatedBy("Boxly")
            .build().sign()
    }

    private fun signerConfig(): ApkSigner.SignerConfig {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            gen.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Boxly, O=Boxly"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                    .setCertificateNotAfter(Date(4102444800000L))
                    .build()
            )
            gen.generateKeyPair()
        }
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return ApkSigner.SignerConfig.Builder("boxly", key, listOf(cert)).build()
    }

    /** Same-length replacement in both UTF-8 and UTF-16LE form (binary XML / resources.arsc string pools). */
    private fun replaceAll(data: ByteArray, from: String, to: String) {
        require(from.length == to.length) { "length mismatch" }
        for (cs in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
            val f = from.toByteArray(cs); val t = to.toByteArray(cs)
            if (f.size != t.size) continue
            var i = 0
            while (true) {
                i = indexOf(data, f, i)
                if (i < 0) break
                System.arraycopy(t, 0, data, i, t.size)
                i += f.size
            }
        }
    }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int): Int {
        outer@ for (i in from..(h.size - n.size)) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }
}
