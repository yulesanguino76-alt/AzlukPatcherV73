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
 * ApkEngine V7.3 — fixed streaming patcher.
 *
 * Root causes of slowness fixed:
 * 1. readBytes() per ZIP entry → streaming copyTo() with 64KB buffer
 * 2. patchClassData NOP'd ALL methods → now only targets methods
 *    that directly reference the matched string via string-id lookup
 * 3. tmp.readBytes() for signing → FileInputStream streamed directly
 * 4. Adler32 + SHA1 computed incrementally, not via full array copy
 */
class ApkEngine(private val ctx: Context) {

    companion object {
        private const val TAG      = "ApkEngine"
        private val DEX_MAGIC      = byteArrayOf(0x64, 0x65, 0x78, 0x0a)
        private const val RET_VOID: Byte = 0x0e
        private const val CONST4:   Byte = 0x12

        // Patterns: [searchString, patchTypeKey, description]
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
            arrayOf("isRooted",                    "ROOT_BYPASS",      "Root detection"),
            arrayOf("RootBeer",                    "ROOT_BYPASS",      "RootBeer library"),
            arrayOf("isDeviceRooted",              "ROOT_BYPASS",      "Root check method"),
            arrayOf("SafetyNet",                   "NONE",             "SafetyNet anti-tamper"),
            arrayOf("com/google/android/play/core/integrity", "NONE",  "Play Integrity API"),
            arrayOf("frida",                       "NONE",             "Frida detection"),
            arrayOf("XposedBridge",                "NONE",             "Xposed detection")
        )

        private const val BUF = 65536
    }

    fun interface Progress { fun on(msg: String) }

    // ── PUBLIC ────────────────────────────────────────────────────────────────

    fun quickStatus(pkg: String): PatchStatus {
        return try {
            val ai  = ctx.packageManager.getApplicationInfo(pkg, 0)
            val apk = File(ai.sourceDir)
            if (apk.length() > 150L * 1024 * 1024) return PatchStatus.LIKELY
            val r = scanFile(apk)
            when {
                r.isEmpty() -> PatchStatus.UNKNOWN
                r.any { it.desc?.let { d ->
                    d.contains("SafetyNet") || d.contains("Frida") ||
                    d.contains("Xposed")   || d.contains("Integrity") } == true
                } -> PatchStatus.COMPLEX
                r.size > 2  -> PatchStatus.PATCHABLE
                else        -> PatchStatus.LIKELY
            }
        } catch (e: Exception) { PatchStatus.UNKNOWN }
    }

    fun quickCount(pkg: String): Int = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        scanFile(File(ai.sourceDir)).size
    } catch (e: Exception) { 0 }

    fun scan(pkg: String): List<ScanResult> {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        return scanFile(File(ai.sourceDir))
    }

    fun patch(pkg: String, patches: List<PatchType>, progress: Progress): File {
        val ai  = ctx.packageManager.getApplicationInfo(pkg, 0)
        val out = File(StorageUtils.getPatchedDir(), "${pkg}_azluk.apk")
        patchToDisk(File(ai.sourceDir), out, patches, progress)
        return out
    }

    fun patchExternal(input: File, patches: List<PatchType>, progress: Progress): File {
        if (input.name.endsWith(".xapk")) return patchXapk(input, patches, progress)
        val out = File(StorageUtils.getPatchedDir(),
            input.name.replace(".apk", "_azluk.apk"))
        patchToDisk(input, out, patches, progress)
        return out
    }

    // ── XAPK ─────────────────────────────────────────────────────────────────

    private fun patchXapk(xapk: File, patches: List<PatchType>, p: Progress): File {
        p.on("Analyzing XAPK…")
        val tmp = File(ctx.cacheDir, "azluk_${System.currentTimeMillis()}").also { it.mkdirs() }
        return try {
            val ex = LinkedHashMap<String, File>()
            ZipInputStream(BufferedInputStream(FileInputStream(xapk), BUF)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val d = File(tmp, e.name.replace("/", "__"))
                    FileOutputStream(d).use { fo -> z.copyTo(fo, BUF) }
                    ex[e.name] = d
                    e = z.nextEntry
                }
            }
            var main: File? = null; var mainKey: String? = null
            for ((k, v) in ex) { if (k == "base.apk") { main = v; mainKey = k; break } }
            if (main == null) for ((k, v) in ex) {
                if (k.endsWith(".apk")) { main = v; mainKey = k; break } }
            requireNotNull(main) { "No APK in XAPK" }
            p.on("Patching $mainKey…")
            val pb = File(tmp, "patched.apk")
            patchToDisk(main, pb, patches, p)
            ex[mainKey!!] = pb
            val outXapk = File(StorageUtils.getPatchedDir(),
                xapk.name.replace(".xapk", "_azluk.xapk"))
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outXapk), BUF)).use { zo ->
                for ((k, v) in ex) {
                    zo.putNextEntry(ZipEntry(k).apply { method = ZipEntry.DEFLATED })
                    v.inputStream().use { it.copyTo(zo, BUF) }
                    zo.closeEntry()
                }
            }
            outXapk
        } finally { tmp.deleteRecursively() }
    }

    // ── PATCH CORE ────────────────────────────────────────────────────────────

    /**
     * Stream every ZIP entry through — for non-DEX entries we pipe bytes
     * directly without loading into memory. For DEX entries we load only
     * that entry (usually 1-5MB), patch, write back. The final signed APK
     * is written to disk without a second full-RAM copy.
     */
    private fun patchToDisk(
        input: File, out: File,
        patches: List<PatchType>, progress: Progress
    ) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, "${out.name}.unsigned")
        progress.on("Opening ${input.name} (${fmtSize(input.length())})…")

        // Step 1: stream-repack, patching DEX entries in place
        ZipInputStream(BufferedInputStream(FileInputStream(input), BUF)).use { zi ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), BUF)).use { zo ->
                var e = zi.nextEntry
                while (e != null) {
                    val name = e.name

                    // Drop old signatures
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") ||
                         name.endsWith(".DSA") || name.endsWith(".EC") ||
                         name.endsWith(".MF"))) {
                        zi.closeEntry(); e = zi.nextEntry; continue
                    }

                    if (name.endsWith(".dex")) {
                        // Load DEX entry only (typically 1-10MB)
                        val dex = zi.readBytes()
                        if (isDex(dex)) {
                            progress.on("Patching $name (${fmtSize(dex.size.toLong())})…")
                            val patched = patchDex(dex, patches)
                            val ze = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
                            zo.putNextEntry(ze)
                            zo.write(patched)
                            zo.closeEntry()
                        } else {
                            // Not a real DEX, write as-is
                            val ze = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
                            zo.putNextEntry(ze)
                            zo.write(dex)
                            zo.closeEntry()
                        }
                    } else {
                        // Stream all other entries directly — no RAM allocation
                        val method = if (name == "resources.arsc" || name.endsWith(".so"))
                            ZipEntry.STORED else ZipEntry.DEFLATED

                        if (method == ZipEntry.STORED) {
                            // STORED needs size+crc upfront — must buffer
                            val data = zi.readBytes()
                            val crc  = CRC32().also { it.update(data) }.value
                            val ze   = ZipEntry(name).apply {
                                this.method         = ZipEntry.STORED
                                this.size           = data.size.toLong()
                                this.compressedSize = data.size.toLong()
                                this.crc            = crc
                            }
                            zo.putNextEntry(ze)
                            zo.write(data)
                            zo.closeEntry()
                        } else {
                            zo.putNextEntry(ZipEntry(name).apply { this.method = method })
                            zi.copyTo(zo, BUF)
                            zo.closeEntry()
                        }
                    }
                    zi.closeEntry()
                    e = zi.nextEntry
                }
            }
        }

        // Step 2: sign — reads tmp from disk, writes signed to out
        progress.on("Signing APK (V1)…")
        ApkSignerV2.sign(tmp, out)   // file-to-file signing (no full RAM load)
        tmp.delete()
        progress.on("✓ Done — ${out.name} (${fmtSize(out.length())})")
    }

    // ── DEX PATCHING ─────────────────────────────────────────────────────────

    /**
     * Patch a DEX byte array.
     *
     * Strategy: scan the string ID table to find strings matching our
     * patterns. Collect the string IDs of matched strings. Then walk
     * encoded methods: for each method, inspect its instructions for
     * const-string opcodes (0x1a / 0x1b) that reference a matched string.
     * Only those methods get their first instruction replaced.
     * This avoids nuking random methods and makes patching targeted.
     */
    private fun patchDex(dex: ByteArray, patches: List<PatchType>): ByteArray {
        val patched  = dex.copyOf()
        val buf      = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN)
        val patchKeys = patches.map { it.key }.toSet()

        return try {
            val strIdsOff  = buf.getInt(0x38)
            val strIdsSize = buf.getInt(0x34)

            // Build set of string indices that match our patterns
            val matchedStrIds = mutableSetOf<Int>()
            val matchedTypes  = mutableSetOf<String>() // which patchType matched

            for (i in 0 until strIdsSize) {
                val strDataOff = buf.getInt(strIdsOff + i * 4)
                if (strDataOff <= 0 || strDataOff >= patched.size) continue

                val str = readDexString(patched, strDataOff) ?: continue

                for (pat in PATTERNS) {
                    if (pat[1] !in patchKeys) continue
                    if (str.contains(pat[0], ignoreCase = true)) {
                        matchedStrIds.add(i)
                        matchedTypes.add(pat[1])
                    }
                }
            }

            if (matchedStrIds.isEmpty() && PatchType.ROOT_BYPASS !in patches &&
                PatchType.FORCE_DEBUGGABLE !in patches) {
                return patched // nothing to do
            }

            // Walk class defs → class data → methods
            val classDefsOff  = buf.getInt(0x60)
            val classDefsSize = buf.getInt(0x5c)

            for (ci in 0 until classDefsSize) {
                val classDataOff = buf.getInt(classDefsOff + ci * 32 + 24)
                if (classDataOff == 0) continue
                try {
                    patchMethods(patched, classDataOff, patchKeys,
                        matchedStrIds, matchedTypes)
                } catch (_: Exception) { /* skip corrupt class */ }
            }

            recomputeChecksums(patched)
            patched
        } catch (e: Exception) {
            Log.w(TAG, "patchDex: ${e.message}")
            patched // return original if parsing fails
        }
    }

    private fun readDexString(dex: ByteArray, off: Int): String? {
        var pos = off; var len = 0; var shift = 0
        while (pos < dex.size) {
            val b = dex[pos++].toInt() and 0xFF
            len = len or ((b and 0x7F) shl shift); shift += 7
            if (b and 0x80 == 0) break
        }
        if (pos + len > dex.size || len <= 0 || len > 65536) return null
        return try { String(dex, pos, len, Charsets.UTF_8) } catch (_: Exception) { null }
    }

    /**
     * Walk encoded_method list, find const-string refs to matched IDs,
     * replace first instruction of those methods.
     */
    private fun patchMethods(
        dex: ByteArray, classDataOff: Int,
        patchKeys: Set<String>,
        matchedStrIds: Set<Int>,
        matchedTypes: Set<String>
    ) {
        var pos = classDataOff

        fun uleb(): Int {
            var v = 0; var s = 0
            while (pos < dex.size) {
                val b = dex[pos++].toInt() and 0xFF
                v = v or ((b and 0x7F) shl s); s += 7
                if (b and 0x80 == 0) break
            }
            return v
        }

        val sf = uleb(); val inst = uleb()
        val dm = uleb(); val vm   = uleb()

        // Skip fields
        repeat(sf + inst) { uleb(); uleb() }

        repeat(dm + vm) {
            uleb() // method_idx_diff
            uleb() // access_flags
            val codeOff = uleb()

            if (codeOff == 0 || codeOff + 16 >= dex.size) return@repeat

            val insnsOff = codeOff + 16     // code_item.insns starts here
            val insnsLen = ByteBuffer.wrap(dex, codeOff + 12, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt() * 2  // in bytes

            if (insnsOff + insnsLen > dex.size || insnsLen < 2) return@repeat

            // Scan instructions for const-string (0x1a) or const-string/jumbo (0x1b)
            var hasMatchedStr = false
            var ip = insnsOff
            while (ip < insnsOff + insnsLen - 3) {
                val op = dex[ip].toInt() and 0xFF
                if (op == 0x1a) {  // const-string vX, string@XXXX (2 code-units)
                    val strIdx = (dex[ip+2].toInt() and 0xFF) or
                                 ((dex[ip+3].toInt() and 0xFF) shl 8)
                    if (strIdx in matchedStrIds) { hasMatchedStr = true; break }
                    ip += 4
                } else if (op == 0x1b) { // const-string/jumbo (3 code-units)
                    val strIdx = ByteBuffer.wrap(dex, ip+2, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).getInt()
                    if (strIdx in matchedStrIds) { hasMatchedStr = true; break }
                    ip += 6
                } else {
                    // Advance by instruction size — use opcode width table shortcut:
                    // most ops are 1 or 2 code-units; for correctness skip by 2 bytes
                    ip += 2
                }
            }

            if (!hasMatchedStr && PatchType.ROOT_BYPASS.key !in matchedTypes &&
                PatchType.FORCE_DEBUGGABLE.key !in matchedTypes) return@repeat

            // Determine replacement based on context
            if (hasMatchedStr) {
                when {
                    "ROOT_BYPASS" in matchedTypes && dex[insnsOff].toInt() and 0xFF in setOf(0x0f, 0x12) -> {
                        // boolean-return method that checks root → return false
                        if (insnsOff + 3 < dex.size) {
                            dex[insnsOff]   = CONST4
                            dex[insnsOff+1] = 0x00 // const/4 v0, #0
                            dex[insnsOff+2] = 0x0f // return v0
                            dex[insnsOff+3] = 0x00
                        }
                    }
                    else -> {
                        // void-return or other — just RET_VOID
                        dex[insnsOff]   = RET_VOID
                        if (insnsOff + 1 < dex.size) dex[insnsOff+1] = 0x00
                    }
                }
            }
        }
    }

    private fun recomputeChecksums(dex: ByteArray) {
        if (dex.size < 0x70) return
        // SHA-1 over dex[32..end]
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(dex, 32, dex.size - 32)
        System.arraycopy(sha1.digest(), 0, dex, 12, 20)
        // Adler32 over dex[12..end]
        val adler = java.util.zip.Adler32()
        adler.update(dex, 12, dex.size - 12)
        val cs = adler.value
        dex[8]  = (cs and 0xFF).toByte()
        dex[9]  = ((cs shr 8)  and 0xFF).toByte()
        dex[10] = ((cs shr 16) and 0xFF).toByte()
        dex[11] = ((cs shr 24) and 0xFF).toByte()
    }

    // ── STREAMING SCANNER ─────────────────────────────────────────────────────

    fun scanFile(apk: File): List<ScanResult> {
        val results  = mutableListOf<ScanResult>()
        var dexIndex = 0
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(apk), 32768)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name.endsWith(".dex"))
                        results.addAll(scanDexStream(z, dexIndex++))
                    else
                        z.skip(Long.MAX_VALUE)
                    z.closeEntry()
                    e = z.nextEntry
                }
            }
        } catch (ex: Exception) { Log.e(TAG, "scanFile: ${ex.message}") }
        return results.distinctBy { it.patchType + it.desc }
    }

    private fun scanDexStream(z: ZipInputStream, idx: Int): List<ScanResult> {
        val CHUNK = 131072; val OVERLAP = 512
        val buf = ByteArray(CHUNK + OVERLAP); val prev = ByteArray(OVERLAP)
        var prevLen = 0; var first = true
        val found = mutableSetOf<String>(); val results = mutableListOf<ScanResult>()

        while (true) {
            System.arraycopy(prev, 0, buf, 0, prevLen)
            var read = 0
            while (read < CHUNK) {
                val n = z.read(buf, prevLen + read, CHUNK - read)
                if (n == -1) break; read += n
            }
            if (read == 0 && prevLen == 0) break
            val avail = prevLen + read

            if (first) {
                first = false
                if (avail < 4 || buf[0] != 0x64.toByte() || buf[1] != 0x65.toByte() ||
                    buf[2] != 0x78.toByte() || buf[3] != 0x0a.toByte()) {
                    while (z.read(buf) != -1) {}
                    return results
                }
            }

            for (pat in PATTERNS) {
                val key = pat[1] + pat[2]
                if (found.contains(key)) continue
                if (indexOfBytes(buf, pat[0].toByteArray(), avail) >= 0) {
                    found.add(key)
                    results.add(ScanResult(pat[1], pat[2], idx, 0))
                }
            }

            prevLen = minOf(OVERLAP, avail)
            System.arraycopy(buf, avail - prevLen, prev, 0, prevLen)
            if (read < CHUNK) break
        }
        return results
    }

    private fun indexOfBytes(buf: ByteArray, pat: ByteArray, len: Int): Int {
        val end = minOf(len, buf.size) - pat.size
        outer@ for (i in 0..end) {
            for (j in pat.indices) if (buf[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun isDex(data: ByteArray) = data.size > 4 &&
        data[0] == DEX_MAGIC[0] && data[1] == DEX_MAGIC[1] &&
        data[2] == DEX_MAGIC[2] && data[3] == DEX_MAGIC[3]

    private fun fmtSize(b: Long) = when {
        b < 1024       -> "$b B"
        b < 1024*1024  -> "%.1f KB".format(b/1024f)
        else           -> "%.1f MB".format(b/(1024f*1024))
    }
}
