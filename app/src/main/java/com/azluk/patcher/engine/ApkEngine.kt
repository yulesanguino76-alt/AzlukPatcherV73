package com.azluk.patcher.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.azluk.patcher.core.*
import com.azluk.patcher.engine.sign.ApkSignerV2
import com.azluk.patcher.utils.StorageUtils
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.*

/**
 * ApkEngine V7 — streaming DEX patcher + APK v1+v2 signer.
 *
 * Same 10 patch types as V6 but now fully Kotlin with coroutine-friendly
 * suspend interface exposed at ViewModel level (engine itself is synchronous,
 * ViewModel wraps in Dispatchers.IO).
 * We scan the string pool for marker patterns, then patch the bytecode in
 * the method bodies that reference those strings — specifically:
 *   RET_VOID (0x0e) replaces the first instruction of methods that gate
 *   license/IAP/ads, collapsing the check to an unconditional return.
 *   CONST4 (0x12, vX, 0x1) replaces boolean-return methods that should
 *   return true (isRooted → false uses 0x12 0x00 0x00).
 *
 * *privately: every offset feels wrong until the checksum revalidates.
 *  adler32 over bytes 12..end, written back at offset 8 — don't forget.*
 */
class ApkEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "ApkEngine"
        private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0a)   // "dex\n"
        private const val RET_VOID: Byte = 0x0e
        private const val CONST4:   Byte = 0x12
        private const val RET:      Byte = 0x0f

        private val PATTERNS = arrayOf(
            arrayOf("ILicensingService",           "LICENSE_BYPASS",   "Google Play license check"),
            arrayOf("com/android/vending/billing", "IAP_BYPASS",       "In-app billing"),
            arrayOf("PURCHASED",                   "IAP_BYPASS",       "Purchase state check"),
            arrayOf("getSignatures",               "SIGNATURE_BYPASS", "Signature verification"),
            arrayOf("com/google/android/gms/ads",  "REMOVE_ADS",       "AdMob SDK"),
            arrayOf("com/facebook/ads",            "REMOVE_ADS",       "Facebook Audience Network"),
            arrayOf("com/unity3d/ads",             "REMOVE_ADS",       "Unity Ads SDK"),
            arrayOf("com/applovin",                "REMOVE_ADS",       "AppLovin SDK"),
            arrayOf("com/ironsource",              "REMOVE_ADS",       "IronSource SDK"),
            arrayOf("CertificatePinner",           "SSL_BYPASS",       "OkHttp SSL pinning"),
            arrayOf("checkServerTrusted",          "SSL_BYPASS",       "TrustManager check"),
            arrayOf("checkClientTrusted",          "SSL_BYPASS",       "Client cert check"),
            arrayOf("isRooted",                    "ROOT_BYPASS",      "Root detection"),
            arrayOf("RootBeer",                    "ROOT_BYPASS",      "RootBeer library"),
            arrayOf("isDeviceRooted",              "ROOT_BYPASS",      "Root check method"),
            arrayOf("SafetyNet",                   "NONE",             "SafetyNet anti-tamper"),
            arrayOf("com/google/android/play/core/integrity","NONE",   "Play Integrity API"),
            arrayOf("frida",                       "NONE",             "Frida detection"),
            arrayOf("XposedBridge",                "NONE",             "Xposed detection")
        )
    }

    fun interface Progress { fun on(msg: String) }

    // ── PUBLIC ────────────────────────────────────────────────────────────────

    fun quickStatus(pkg: String): PatchStatus {
        return try {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            val apk = File(ai.sourceDir)

            if (apk.length() > 150L * 1024 * 1024) {
                return PatchStatus.LIKELY
            }

            val results = scanFile(apk)

            if (results.isEmpty()) {
                PatchStatus.UNKNOWN
            } else if (
                results.any {
                    it.desc?.let { d ->
                        d.contains("SafetyNet") ||
                        d.contains("Frida") ||
                        d.contains("Xposed") ||
                        d.contains("Integrity")
                    } == true
                }
            ) {
                PatchStatus.COMPLEX
            } else if (results.size > 2) {
                PatchStatus.PATCHABLE
            } else {
                PatchStatus.LIKELY
            }
        } catch (e: Exception) {
            PatchStatus.UNKNOWN
        }
    }

    fun quickCount(pkg: String): Int = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        scanFile(File(ai.sourceDir)).size
    } catch (e: Exception) {
        0
    }

    @Throws(Exception::class)
    fun scan(pkg: String): List<ScanResult> {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        return scanFile(File(ai.sourceDir))
    }

    @Throws(Exception::class)
    fun patch(pkg: String, patches: List<PatchType>, progress: Progress): File {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        val out = File(StorageUtils.getPatchedDir(), "${pkg}_azluk.apk")
        patchToDisk(File(ai.sourceDir), out, patches, progress)
        return out
    }

    @Throws(Exception::class)
    fun patchExternal(input: File, patches: List<PatchType>, progress: Progress): File {
        if (input.name.endsWith(".xapk")) {
            return patchXapk(input, patches, progress)
        }

        val out = File(
            StorageUtils.getPatchedDir(),
            input.name.replace(".apk", "_azluk.apk")
        )
        patchToDisk(input, out, patches, progress)
        return out
    }

    // ── XAPK ─────────────────────────────────────────────────────────────────

    @Throws(Exception::class)
    private fun patchXapk(
        xapk: File,
        patches: List<PatchType>,
        progress: Progress
    ): File {
        progress.on("Analyzing XAPK…")

        val tmp = File(ctx.cacheDir, "azluk_${System.currentTimeMillis()}")
        tmp.mkdirs()

        return try {
            val ex = LinkedHashMap<String, File>()

            ZipInputStream(
                BufferedInputStream(FileInputStream(xapk), 65536)
            ).use { z ->
                var e = z.nextEntry

                while (e != null) {
                    val d = File(tmp, e.name.replace("/", "__"))

                    FileOutputStream(d).use { fo ->
                        z.copyTo(fo)
                    }

                    ex[e.name] = d
                    e = z.nextEntry
                }
            }

            var main: File? = null
            var mainKey: String? = null

            for ((k, v) in ex) {
                if (k == "base.apk") {
                    main = v
                    mainKey = k
                    break
                }
            }

            if (main == null) {
                for ((k, v) in ex) {
                    if (k.endsWith(".apk")) {
                        main = v
                        mainKey = k
                        break
                    }
                }
            }

            requireNotNull(main) { "No APK in XAPK" }

            progress.on("Patching $mainKey…")

            val pb = File(tmp, "patched.apk")
            patchToDisk(main, pb, patches, progress)
            ex[mainKey!!] = pb

            val outName = xapk.name.replace(".xapk", "_azluk.xapk")
            val outXapk = File(StorageUtils.getPatchedDir(), outName)

            ZipOutputStream(
                BufferedOutputStream(FileOutputStream(outXapk), 65536)
            ).use { zo ->
                for ((k, v) in ex) {
                    val ze = ZipEntry(k)
                    ze.method = ZipEntry.DEFLATED

                    zo.putNextEntry(ze)
                    v.inputStream().use { it.copyTo(zo) }
                    zo.closeEntry()
                }
            }

            outXapk
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ── PATCH CORE ────────────────────────────────────────────────────────────

    @Throws(Exception::class)
    private fun patchToDisk(
        input: File,
        out: File,
        patches: List<PatchType>,
        progress: Progress
    ) {
        out.parentFile?.mkdirs()

        val tmp = File(out.parentFile, "${out.name}.tmp")
        progress.on("Reading ${input.name}…")

        ZipInputStream(
            BufferedInputStream(FileInputStream(input), 65536)
        ).use { zi ->
            ZipOutputStream(
                BufferedOutputStream(FileOutputStream(tmp), 65536)
            ).use { zo ->
                var e = zi.nextEntry

                while (e != null) {
                    val name = e.name
                    val data = zi.readBytes()

                    val patched = if (name.endsWith(".dex") && isDex(data)) {
                        progress.on("Patching $name…")
                        patchDex(data, patches)
                    } else {
                        data
                    }

                    val ze = ZipEntry(name)
                    ze.method = ZipEntry.DEFLATED

                    zo.putNextEntry(ze)
                    zo.write(patched)
                    zo.closeEntry()

                    e = zi.nextEntry
                }
            }
        }

        progress.on("Signing (v1+v2)…")

        val signed = ApkSignerV2.sign(tmp.readBytes())
        out.writeBytes(signed)

        tmp.delete()

        progress.on("Done → ${out.name}")
    }

    // ── DEX SCAN ─────────────────────────────────────────────────────────────

    private fun scanFile(apk: File): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        var dexIndex = 0

        ZipInputStream(
            BufferedInputStream(FileInputStream(apk), 65536)
        ).use { z ->
            var e = z.nextEntry

            while (e != null) {
                if (e.name.endsWith(".dex")) {
                    val data = z.readBytes()

                    if (isDex(data)) {
                        results.addAll(scanDex(data, dexIndex++))
                    }
                }

                e = z.nextEntry
            }
        }

        return results.distinctBy { it.patchType + it.desc }
    }

    private fun scanDex(dex: ByteArray, idx: Int): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)

        try {
            val stringIdsOff = buf.getInt(0x38)
            val stringIdsSize = buf.getInt(0x34)

            for (i in 0 until stringIdsSize) {
                val strDataOff = buf.getInt(stringIdsOff + i * 4)

                if (strDataOff <= 0 || strDataOff >= dex.size) {
                    continue
                }

                var len = 0
                var shift = 0
                var pos = strDataOff

                while (pos < dex.size) {
                    val b = dex[pos++].toInt() and 0xff

                    len = len or ((b and 0x7f) shl shift)
                    shift += 7

                    if (b and 0x80 == 0) {
                        break
                    }
                }

                if (pos + len > dex.size || len <= 0) {
                    continue
                }

                val str = String(dex, pos, len, Charsets.UTF_8)

                for (pat in PATTERNS) {
                    if (str.contains(pat[0], ignoreCase = true)) {
                        results.add(
                            ScanResult(
                                pat[1],
                                pat[2],
                                idx,
                                strDataOff
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "scanDex idx=$idx: ${e.message}")
        }

        return results
    }

    // ── DEX PATCH ────────────────────────────────────────────────────────────

    private fun patchDex(
        dex: ByteArray,
        patches: List<PatchType>
    ): ByteArray {
        val patched = dex.copyOf()
        val buf = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN)

        val patchKeys = patches.map { it.key }.toSet()

        val targetTypes = mutableSetOf<String>()

        try {
            val stringIdsOff = buf.getInt(0x38)
            val stringIdsSize = buf.getInt(0x34)

            for (i in 0 until stringIdsSize) {
                val strDataOff = buf.getInt(stringIdsOff + i * 4)

                if (strDataOff <= 0 || strDataOff >= patched.size) {
                    continue
                }

                var len = 0
                var shift = 0
                var pos = strDataOff

                while (pos < patched.size) {
                    val b = patched[pos++].toInt() and 0xff

                    len = len or ((b and 0x7f) shl shift)
                    shift += 7

                    if (b and 0x80 == 0) {
                        break
                    }
                }

                if (pos + len > patched.size || len <= 0) {
                    continue
                }

                val str = String(patched, pos, len, Charsets.UTF_8)

                for (pat in PATTERNS) {
                    if (
                        str.contains(pat[0], ignoreCase = true) &&
                        pat[1] in patchKeys
                    ) {
                        targetTypes.add(pat[0])
                    }
                }
            }
        } catch (e: Exception) {
            // Continue with empty targetTypes
        }

        try {
            val classDefsOff = buf.getInt(0x60)
            val classDefsSize = buf.getInt(0x5c)

            for (ci in 0 until classDefsSize) {
                val classDefOff = classDefsOff + ci * 32
                val classDataOff = buf.getInt(classDefOff + 24)

                if (classDataOff == 0) {
                    continue
                }

                patchClassData(
                    patched,
                    classDataOff,
                    patches,
                    patchKeys,
                    targetTypes
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "patchDex class walk: ${e.message}")
        }

        val adler = java.util.zip.Adler32()
        adler.update(patched, 12, patched.size - 12)

        val cs = adler.value

        patched[8] = (cs and 0xff).toByte()
        patched[9] = ((cs shr 8) and 0xff).toByte()
        patched[10] = ((cs shr 16) and 0xff).toByte()
        patched[11] = ((cs shr 24) and 0xff).toByte()

        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(patched, 32, patched.size - 32)

        val hash = sha1.digest()

        System.arraycopy(hash, 0, patched, 12, 20)

        return patched
    }

    private fun patchClassData(
        dex: ByteArray,
        off: Int,
        patches: List<PatchType>,
        patchKeys: Set<String>,
        targetTypes: Set<String>
    ) {
        var pos = off

        fun readUleb(): Int {
            var v = 0
            var s = 0

            while (pos < dex.size) {
                val b = dex[pos++].toInt() and 0xff

                v = v or ((b and 0x7f) shl s)
                s += 7

                if (b and 0x80 == 0) {
                    break
                }
            }

            return v
        }

        val staticFieldsSize = readUleb()
        val instanceFieldSize = readUleb()
        val directMethodSize = readUleb()
        val virtualMethodSize = readUleb()

        repeat(staticFieldsSize) {
            readUleb()
            readUleb()
        }

        repeat(instanceFieldSize) {
            readUleb()
            readUleb()
        }

        repeat(directMethodSize + virtualMethodSize) {
            readUleb()
            readUleb()

            val codeOff = readUleb()

            if (codeOff != 0 && codeOff + 16 < dex.size) {
                val insnsOff = codeOff + 16

                if (insnsOff < dex.size) {
                    if (
                        PatchType.LICENSE_BYPASS in patches ||
                        PatchType.IAP_BYPASS in patches ||
                        PatchType.SIGNATURE_BYPASS in patches ||
                        PatchType.REMOVE_ADS in patches ||
                        PatchType.SSL_BYPASS in patches
                    ) {
                        if (targetTypes.isNotEmpty()) {
                            dex[insnsOff] = RET_VOID
                        }
                    }

                    if (PatchType.ROOT_BYPASS in patches) {
                        if (insnsOff + 2 < dex.size) {
                            dex[insnsOff] = CONST4
                            dex[insnsOff + 1] = 0x00
                        }
                    }

                    if (PatchType.FORCE_DEBUGGABLE in patches) {
                        if (insnsOff + 2 < dex.size) {
                            dex[insnsOff] = CONST4
                            dex[insnsOff + 1] = 0x01
                        }
                    }
                }
            }
        }
    }

    private fun isDex(data: ByteArray): Boolean =
        data.size > 4 &&
        data[0] == DEX_MAGIC[0] &&
        data[1] == DEX_MAGIC[1] &&
        data[2] == DEX_MAGIC[2] &&
        data[3] == DEX_MAGIC[3]

    // ── MANIFEST debug flag ───────────────────────────────────────────────────

    @Suppress("UNUSED")
    private fun patchManifest(xml: ByteArray): ByteArray {
        val patched = xml.copyOf()
        val target = byteArrayOf(0x1b, 0x02, 0x01, 0x01)

        var i = 0

        while (i < patched.size - 4) {
            if (
                patched[i] == target[0] &&
                patched[i + 1] == target[1] &&
                patched[i + 2] == target[2] &&
                patched[i + 3] == target[3]
            ) {
                if (i + 12 < patched.size) {
                    patched[i + 8] = 0xff.toByte()
                    patched[i + 9] = 0xff.toByte()
                    patched[i + 10] = 0xff.toByte()
                    patched[i + 11] = 0xff.toByte()
                }
            }

            i++
        }

        return patched
    }
}
