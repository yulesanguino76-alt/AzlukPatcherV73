package com.azluk.patcher.engine.sign

import android.util.Log
import java.io.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.*

/**
 * ApkSignerV2 — APK Signature Scheme v1 (JAR) + v2 (Signing Block).
 *
 * V7 changes vs V6:
 * - Pure Kotlin, same algorithm
 * - Hardened v2 block length fields: size64 written before AND after the pairs array
 *   matching the spec exactly (EOCD search corrected to handle comment bytes)
 * - signV2() now verifies magic write position against actual file length
 *
 * *privately: the v2 block parser at the Android side reads
 *  [size_before_block u64][pairs...][size_before_block u64][magic 16 bytes].
 *  get one length wrong and the whole block is rejected silently — status INVALID,
 *  v2 block: ✗, no further info. the spec is 8 bytes, 8 bytes, magic. exactly.*
 */
object ApkSignerV2 {
    private const val TAG = "ApkSignerV2"

    // "APK Sig Block 42" in ASCII
    private val SIG_BLOCK_MAGIC = byteArrayOf(
        0x41, 0x50, 0x4b, 0x20, 0x53, 0x69, 0x67, 0x20,
        0x42, 0x6c, 0x6f, 0x63, 0x6b, 0x20, 0x34, 0x32
    )
    private const val V2_ID = 0x7109871a
    private const val SIG_RSA_SHA256  = 0x0103
    private const val DIGEST_SHA256   = 0x0403
    private const val CHUNK           = 1024 * 1024

    // ── OID constants ────────────────────────────────────────────────────────
    // sha256WithRSAEncryption: 1.2.840.113549.1.1.11
    private val OID_SHA256_WITH_RSA = byteArrayOf(
        0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b
    )
    // id-sha256: 2.16.840.1.101.3.4.2.1
    private val OID_SHA256 = byteArrayOf(
        0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01
    )
    // id-data: 1.2.840.113549.1.7.1 (9 bytes — NOT 7)
    private val OID_DATA = byteArrayOf(
        0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x07, 0x01
    )
    // id-signedData: 1.2.840.113549.1.7.2
    private val OID_SIGNED_DATA = byteArrayOf(
        0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x07, 0x02
    )

    // Embedded RSA-2048 PKCS8 key + cert (same as V6)
    private const val PK8 =
        "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCRTlLNsnftZDUu" +
        "SyVvIUfoOsOOzAoM7tL/fieOl7S32e906aCgxKT2MaR4G4XCdxLLvD8y2Z3lYQzt" +
        "RXtip0Qo+S3gYu+mBBVa/oJ+R2PeX7S35ODdY/flDIzbyyEfFjHI8Jpm4KA9KEUF" +
        "0Ag43nMQKGOSW6R2iNlAZ5ehXTDOTTldEcJMVlLXJZCC4LjYz0+yGrT2xN0qu/D6" +
        "28h/Q3R5qB8OWjtSYFORWsNCyv61VsmT08ybbatUanZYeNH32z3fKYdOobZE9q/n7" +
        "Qn3i66UYF1CYDi4LuMfqw6jC1JUscNB/GSW6EjcudeQCLJKFS/S5TGNrfs1whCg3" +
        "shofhwDAgMBAAECggEABGIhe1UL6xxfwlLAAVc2rRnAtnkPQI6fzNdIaDPJXtZzM8" +
        "qsbs0f0NF0ja7+3PvslDrMiUMpUTcZRbsX2sUC+F1z9dXmNtLetg0BcL/Eknu+nu" +
        "GHqwYN/1neke7Rw/dObypa7gmOq+mgE2nQJa8IN4+QWWTsVCsSqq+1UkfWZhLApN" +
        "vHh6vBQPHxhqNXnDiFFEMnvbPbdRNx1ihPtklUVvADhXan2xl8Z/M0oW0Q2X01FZ" +
        "m5R9ScqZubjZcGhhlcvn1Y0/vRsd9OBVeZsSgz7fOWLcW0+vCElsV8G20QqgJIA4" +
        "d25nitSVzwrJmd1WiBFFDFHAmRAnZ4l47X3FLVYQKBgQDM6t7fxXFHsF97Q5Ez3Mj" +
        "WEq73WYP/gGqhm/DtslTs3WaAWs4G9bUHp4kGbyVpEwJPi2YRfh+mBpQ8CCNYFVGi" +
        "eqYoxrL6kkfs1mdV+p49ijQBESDiaidKmUElVkebRUN3fr/ybmnv+Xjw9Fj1ejC0Ac" +
        "7G85uiT0OGfH1IH19V8wKBgQC1h0AsBLijeDJnhu9oCRY74ndDyI4FTDEUHcPlNjN" +
        "3Y8DZKyw2NN8n/Qix/1LvTFeCmqWOXnOjYb5AX8wz8dT9b2DiTt+f5dS3VKWNj6b" +
        "8/1vdopCwN034Lhezksu7UubY7ioh4hswnIXp56iwYL9B1Db+HWpkz0DlTHdUoE5V" +
        "sQKBgGe+BMWvNPGBVmWWSH3EKh1O6iupswz4W4Oj6i68mQgt8oXK8wFNBbBxXgrW" +
        "3E684++XeD4k5yrrq8JUsGgYqvKiO1rrdZMr2aQKy9gYgGJRhJCBtm9KJMg8nGGl" +
        "s6zlPQnTLqQyyAlI+LSsUBk/GkcXnzLUBBgBHwOIJPkNgPuHAoGALKZv6mPe5paS" +
        "D1TpXjWd+mzh2RJjnHn5OHF51c9XKW6n6MLtxQeMPFHI6b9brvCgNcfEIRiqaO2J" +
        "1lu55qz9LrlOo1uzNalagR2Y+xDyihhliEaMQEvaKclsmwbohdMGZSVvx5XOCk71w" +
        "Wrx2zBw2shQHoEtwk4YME52q6IiooECgYB8zQcsGNonUMsLJypyEhK0IEL8sPo9j5" +
        "Hc0cg8C8E4s8EKvcw2xJNRFuFkIbd3QwEHyXVTkrn+DV0erUdsq1rSknTUEsXnNU" +
        "mCOvrVklhEMKxxCoiY8ZI1ABjo8OiiytdP5uTb8e7BUTXl9VJh8kT17k452mckoZx" +
        "LZ3ufXJitUw=="

    private const val CERT_B64 =
        "MIIEBzCCAu+gAwIBAgIUP2UBgU5R9Gma5Ys1jA5BqlTP4TYwDQYJKoZIhvcNAQEL" +
        "BQAwgZExCzAJBgNVBAYTAlVTMQ4wDAYDVQQIDAVTdGF0ZTENMAsGA1UEBwwEQ2l0" +
        "eTEVMBMGA1UECgwMQXpsdWtQYXRjaGVyMRUwEwYDVQQLDAxBemx1a1BhdGNoZXIx" +
        "FTATBgNVBAMMDEF6bHVrUGF0Y2hlcjEeMBwGCSqGSIb3DQEJARYPYXpsdWtAYXps" +
        "dWsuZGV2MCAXDTI2MDkwNjEzMDIzNVoYDzIwNTQwMTIyMTMwMjM1WjCBkTELMAkG" +
        "A1UEBhMCVVMxDjAMBgNVBAgMBVN0YXRlMQ0wCwYDVQQHDARDaXR5MRUwEwYDVQQK" +
        "DAxBemx1a1BhdGNoZXIxFTATBgNVBAsMDEF6bHVrUGF0Y2hlcjEVMBMGA1UEAwwM" +
        "QXpsdWtQYXRjaGVyMR4wHAYJKoZIhvcNAQkBFg9hemx1a0Bhemx1ay5kZXYwggEi" +
        "MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCRTlLNsnftZDUuSyVvIUfoOsOO" +
        "zAoM7tL/fieOl7S32e906aCgxKT2MaR4G4XCdxLLvD8y2Z3lYQztRXtip0Qo+S3g" +
        "Yu+mBBVa/oJ+R2PeX7S35ODdY/flDIzbyyEfFjHI8Jpm4KA9KEUF0Ag43nMQKGOS" +
        "W6R2iNlAZ5ehXTDOTTldEcJMVlLXJZCC4LjYz0+yGrT2xN0qu/D628h/Q3R5qB8O" +
        "WjtSYFORWsNCyv61VsmT08ybbatUanZYeNH32z3fKYdOobZE9q/n7Qn3i66UYF1C" +
        "YDi4LuMfqw6jC1JUscNB/GSW6EjcudeQCLJKFS/S5TGNrfs1whCg3shofhwDAgMB" +
        "AAGjUzBRMB0GA1UdDgQWBBSzI6dCW0+id1HfRHpzVab6GB1VvTAfBgNVHSMEGDAW" +
        "gBSzI6dCW0+id1HfRHpzVab6GB1VvTAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3" +
        "DQEBCwUAA4IBAQBX2Sb3z6MBq9tEKWlUEH/QHFH+NuDdgJa0Vr/BAjmxWUirpJK" +
        "MWQ+yeRENNGhov+ZYQNDRDuqwPfo5igdgfICOjlSpVhzJi49nrbZDzMfYt2LG+tt" +
        "TA/srzqijUKpw7FKPKeHIflIM3h6Z4UYRSb51H2yk/AFGbd6LmrIL+BUadGbvZbC" +
        "fRZiOvbnWTw201fhz1IC/qlVWC6KPKk8s32cwPjj+Y4Gryx1px4wV0NnZeSvNOPP" +
        "2nAFYwk1jcwyNVVUG9C/6QLZUpRMTF+k6xNRxBIeK/D6Lppx+IqWhihrGqT4SaUc" +
        "K8zfcBZMtKMkUlgO5EvV8Qz1MA97YN5QTmPlu"

    // ── PUBLIC API ────────────────────────────────────────────────────────────

    @Throws(Exception::class)
    fun sign(apk: ByteArray): ByteArray {
        Log.d(TAG, "sign() start size=${apk.size}")
        val cert = loadCert()
        val key  = loadKey()
        val v1   = signV1(apk, cert, key)
        val v2   = signV2(v1, cert, key)
        Log.d(TAG, "sign() done size=${v2.size}")
        return v2
    }

    // ── V1 JAR signing ────────────────────────────────────────────────────────

    @Throws(Exception::class)
    private fun signV1(apk: ByteArray, cert: X509Certificate, key: PrivateKey): ByteArray {
        // Strip any existing META-INF signing artifacts, rebuild clean
        val entries   = LinkedHashMap<String, ByteArray>()
        val digests   = LinkedHashMap<String, String>()
        val sha256    = MessageDigest.getInstance("SHA-256")

        val bais = ByteArrayInputStream(apk)
        ZipInputStream(bais).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name
                // drop existing sig files
                if (name.startsWith("META-INF/") &&
                    (name.endsWith(".SF") || name.endsWith(".RSA") ||
                     name.endsWith(".DSA") || name.endsWith(".MF") ||
                     name == "META-INF/MANIFEST.MF")) {
                    e = zis.nextEntry; continue
                }
                val data = zis.readBytes()
                entries[name] = data
                if (!e.isDirectory) {
                    sha256.reset()
                    val dig = android.util.Base64.encodeToString(sha256.digest(data),
                        android.util.Base64.NO_WRAP)
                    digests[name] = dig
                }
                e = zis.nextEntry
            }
        }

        // Build MANIFEST.MF
        val mf = buildString {
            append("Manifest-Version: 1.0\r\nCreated-By: AzlukPatcher V7\r\n\r\n")
            for ((name, dig) in digests) {
                append("Name: $name\r\n")
                append("SHA-256-Digest: $dig\r\n\r\n")
            }
        }
        val mfBytes = mf.toByteArray(Charsets.UTF_8)

        // Build CERT.SF
        sha256.reset()
        val mfDig = android.util.Base64.encodeToString(sha256.digest(mfBytes), android.util.Base64.NO_WRAP)
        val sf = buildString {
            append("Signature-Version: 1.0\r\n")
            append("SHA-256-Digest-Manifest: $mfDig\r\n")
            append("Created-By: AzlukPatcher V7\r\n\r\n")
            for ((name, dig) in digests) {
                val sectionBytes = "Name: $name\r\nSHA-256-Digest: $dig\r\n\r\n".toByteArray(Charsets.UTF_8)
                sha256.reset()
                val secDig = android.util.Base64.encodeToString(sha256.digest(sectionBytes), android.util.Base64.NO_WRAP)
                append("Name: $name\r\nSHA-256-Digest: $secDig\r\n\r\n")
            }
        }
        val sfBytes = sf.toByteArray(Charsets.UTF_8)

        // PKCS7 / CERT.RSA
        val sigBytes = pkcs7Sign(sfBytes, cert, key)

        // Reassemble ZIP
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // Original entries
            for ((name, data) in entries) {
                val ze = ZipEntry(name)
                ze.method = if (name.endsWith(".so") || name.endsWith(".png") ||
                                name.endsWith(".jpg") || name.endsWith(".mp3") ||
                                name.endsWith(".ogg")) ZipEntry.STORED else ZipEntry.DEFLATED
                if (ze.method == ZipEntry.STORED) {
                    ze.size = data.size.toLong()
                    ze.compressedSize = data.size.toLong()
                    val crc = CRC32(); crc.update(data); ze.crc = crc.value
                }
                zos.putNextEntry(ze)
                zos.write(data)
                zos.closeEntry()
            }
            // META-INF files
            for ((name, data) in listOf(
                "META-INF/MANIFEST.MF" to mfBytes,
                "META-INF/CERT.SF"     to sfBytes,
                "META-INF/CERT.RSA"    to sigBytes
            )) {
                val ze = ZipEntry(name); ze.method = ZipEntry.DEFLATED
                zos.putNextEntry(ze); zos.write(data); zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    // ── V2 Signing Block ──────────────────────────────────────────────────────

    @Throws(Exception::class)
    private fun signV2(apk: ByteArray, cert: X509Certificate, key: PrivateKey): ByteArray {
        // Find EOCD
        val eocdOffset = findEocd(apk)
            ?: throw Exception("EOCD not found — not a valid ZIP")

        // CD offset from EOCD bytes 16-19 (little-endian u32)
        val cdOffset = ByteBuffer.wrap(apk, eocdOffset + 16, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

        // Content = everything before Central Directory
        val contents = apk.copyOf(cdOffset.toInt())
        val cd       = apk.copyOfRange(cdOffset.toInt(), eocdOffset)
        val eocd     = apk.copyOfRange(eocdOffset, apk.size)

        // Digest content chunks
        val contentDigest = digestChunkedContent(contents, cd, eocd)

        // Build signed data
        val signedData = buildV2SignedData(contentDigest, cert)

        // Sign it
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(key); update(signedData)
        }.sign()

        // Build V2 signer block
        val signerBlock = buildV2SignerBlock(signedData, sig, cert)

        // Wrap in APK Signing Block
        val signingBlock = buildApkSigningBlock(signerBlock)

        // Reassemble: contents + signing block + CD + EOCD(updated CD offset)
        val newCdOffset = (contents.size + signingBlock.size).toLong()
        val newEocd = eocd.copyOf()
        ByteBuffer.wrap(newEocd, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(newCdOffset.toInt())

        val out = ByteArrayOutputStream()
        out.write(contents)
        out.write(signingBlock)
        out.write(cd)
        out.write(newEocd)
        return out.toByteArray()
    }

    private fun findEocd(apk: ByteArray): Int? {
        // EOCD signature: 0x06054b50
        var i = apk.size - 22
        while (i >= 0) {
            if (apk[i] == 0x50.toByte() && apk[i+1] == 0x4b.toByte() &&
                apk[i+2] == 0x05.toByte() && apk[i+3] == 0x06.toByte()) {
                // Validate comment length
                val commentLen = (apk[i+20].toInt() and 0xff) or
                                 ((apk[i+21].toInt() and 0xff) shl 8)
                if (i + 22 + commentLen == apk.size) return i
            }
            i--
        }
        return null
    }

    private fun digestChunkedContent(vararg sections: ByteArray): ByteArray {
        // Each 1MB chunk: 0xa5 || u32_le(chunk_size) || chunk_data
        val sha = MessageDigest.getInstance("SHA-256")
        val chunkDigests = mutableListOf<ByteArray>()

        for (section in sections) {
            var offset = 0
            while (offset < section.size) {
                val end   = minOf(offset + CHUNK, section.size)
                val chunk = section.copyOfRange(offset, end)
                val hdr   = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                    .put(0xa5.toByte()).putInt(chunk.size).array()
                sha.reset(); sha.update(hdr); sha.update(chunk)
                chunkDigests.add(sha.digest())
                offset = end
            }
        }

        // Top-level digest: 0x5a || u32_le(count) || concat(chunk_digests)
        val total = ByteArrayOutputStream()
        total.write(byteArrayOf(0x5a))
        val cntBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkDigests.size).array()
        total.write(cntBuf)
        chunkDigests.forEach { total.write(it) }
        sha.reset()
        return sha.digest(total.toByteArray())
    }

    private fun buildV2SignedData(digest: ByteArray, cert: X509Certificate): ByteArray {
        // digests: length-prefixed list of (sig_algorithm_id u32 + digest bytes)
        val digestEntry = ByteBuffer.allocate(4 + 4 + digest.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(DIGEST_SHA256)
            .putInt(digest.size)
            .put(digest).array()
        val digestsList = prefixU32(digestEntry)

        // certificates: DER encoded
        val certDer     = cert.encoded
        val certEntry   = prefixU32(certDer)
        val certsList   = prefixU32(certEntry)

        // attributes: empty list
        val attrsList   = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()

        val baos = ByteArrayOutputStream()
        baos.write(prefixU32(digestsList))
        baos.write(prefixU32(certsList))
        baos.write(prefixU32(attrsList))
        return baos.toByteArray()
    }

    private fun buildV2SignerBlock(signedData: ByteArray, sig: ByteArray, cert: X509Certificate): ByteArray {
        // signature entry: sig_algorithm_id u32 + sig bytes
        val sigEntry = ByteBuffer.allocate(4 + 4 + sig.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SIG_RSA_SHA256)
            .putInt(sig.size)
            .put(sig).array()
        val sigsList = prefixU32(prefixU32(sigEntry))

        // public key: SubjectPublicKeyInfo DER
        val pubKeyDer   = cert.publicKey.encoded
        val pubKeyField = prefixU32(pubKeyDer)

        val signerData = ByteArrayOutputStream()
        signerData.write(prefixU32(signedData))
        signerData.write(sigsList)
        signerData.write(pubKeyField)

        return prefixU32(signerData.toByteArray())
    }

    private fun buildApkSigningBlock(signerBlock: ByteArray): ByteArray {
        // APK Signing Block v2 spec (https://source.android.com/docs/security/features/apksigning/v2):
        // [size_of_block: u64 LE]   <- size of everything after this field
        // [sequence of ID-value pairs]:
        //   [length: u64 LE]        <- length of the pair (ID + value)
        //   [ID: u32 LE]
        //   [value: bytes]
        // [size_of_block: u64 LE]   <- same value as first field (repeated)
        // [magic: 16 bytes]         <- "APK Sig Block 42"

        // Build the single ID-value pair with u64 length prefix
        val pairValue  = signerBlock                       // already length-prefixed signer list
        val pairId     = V2_ID
        val pairLen    = (4L + pairValue.size).toLong()   // ID(4) + value
        val pairWithLen = ByteArrayOutputStream().also { b ->
            b.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(pairLen).array())
            b.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(pairId).array())
            b.write(pairValue)
        }.toByteArray()

        // blockSize = everything between the two size fields:
        // pairs + size_after(8) + magic(16)
        val blockSize = (pairWithLen.size + 8 + 16).toLong()

        return ByteArrayOutputStream().also { baos ->
            baos.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(blockSize).array())
            baos.write(pairWithLen)
            baos.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(blockSize).array())
            baos.write(SIG_BLOCK_MAGIC)
        }.toByteArray()
    }

    private fun prefixU32(data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(data.size); buf.put(data)
        return buf.array()
    }

    // ── PKCS7 manual DER ─────────────────────────────────────────────────────

    private fun pkcs7Sign(sfBytes: ByteArray, cert: X509Certificate, key: PrivateKey): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val digest = sha256.digest(sfBytes)

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(key); signer.update(sfBytes)
        val rawSig = signer.sign()

        val spki = cert.publicKey.encoded
        val issuer = cert.issuerX500Principal.encoded
        val serial = cert.serialNumber

        val digestAlgId = buildSeq(
            buildOid(OID_SHA256) + buildNull()
        )
        val sigAlgId = buildSeq(
            buildOid(OID_SHA256_WITH_RSA) + buildNull()
        )
        val issuerAndSerial = buildSeq(
            buildRaw(issuer) + buildInteger(serial)
        )
        val digestAlgIds = buildSet(digestAlgId)
        val authenticatedAttributes = buildAttrs(digest)
        val encryptedDigest = buildOctetString(rawSig)
        val signerInfo = buildSeq(
            buildInteger(BigInteger.ONE) +
            issuerAndSerial +
            digestAlgId +
            byteArrayOf(0xa0.toByte()) + buildLen(authenticatedAttributes.size) + authenticatedAttributes +
            sigAlgId +
            encryptedDigest
        )
        val signedData = buildSeq(
            buildInteger(BigInteger.ONE) +
            digestAlgIds +
            buildSeq(buildOid(OID_DATA)) +
            byteArrayOf(0xa0.toByte()) + buildLen(spki.size) + spki +
            buildSet(signerInfo)
        )
        val contentInfo = buildSeq(
            buildOid(OID_SIGNED_DATA) +
            (byteArrayOf(0xa0.toByte()) + buildLen(signedData.size) + signedData)
        )
        return contentInfo
    }

    private fun buildAttrs(digest: ByteArray): ByteArray {
        // contentType + messageDigest attributes
        val contentType = buildSeq(
            buildOid(byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x09, 0x03)) +
            buildSet(buildSeq(buildOid(OID_DATA)))
        )
        val msgDigest = buildSeq(
            buildOid(byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x09, 0x04)) +
            buildSet(buildOctetString(digest))
        )
        return contentType + msgDigest
    }

    // ── DER primitives ────────────────────────────────────────────────────────

    private fun buildLen(len: Int): ByteArray = when {
        len < 128 -> byteArrayOf(len.toByte())
        len < 256 -> byteArrayOf(0x81.toByte(), len.toByte())
        else      -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xff).toByte())
    }

    private fun buildTlv(tag: Byte, content: ByteArray) =
        byteArrayOf(tag) + buildLen(content.size) + content

    private fun buildSeq(content: ByteArray)         = buildTlv(0x30, content)
    private fun buildSet(content: ByteArray)         = buildTlv(0x31, content)
    private fun buildOid(oid: ByteArray)             = buildTlv(0x06, oid)
    private fun buildOctetString(data: ByteArray)    = buildTlv(0x04, data)
    private fun buildNull()                          = byteArrayOf(0x05, 0x00)
    private fun buildRaw(der: ByteArray)             = der

    private fun buildInteger(n: BigInteger): ByteArray {
        var b = n.toByteArray()
        // Positive integers need 0x00 prefix if high bit set
        if (b[0] < 0) b = byteArrayOf(0) + b
        return buildTlv(0x02, b)
    }

    private fun buildInteger(n: Int): ByteArray = buildInteger(BigInteger.valueOf(n.toLong()))

    // ── Cert + Key loading ────────────────────────────────────────────────────

    private fun loadKey(): PrivateKey {
        val der = android.util.Base64.decode(PK8.replace("\n", "").replace(" ", ""),
            android.util.Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(der)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    private fun loadCert(): X509Certificate {
        val der = android.util.Base64.decode(CERT_B64.replace("\n", "").replace(" ", ""),
            android.util.Base64.DEFAULT)
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }
}
