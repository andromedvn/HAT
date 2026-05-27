package andromedvn.heuristic.activity.tracker.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object VaultSecurity {
    private const val SECRET_KEY = "HAT_HEURISTIC_VAULT_SECURE_KEY_V1"
    private const val ALGORITHM = "HmacSHA256"

    private fun getMac(): Mac {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(SECRET_KEY.toByteArray(Charsets.UTF_8), ALGORITHM))
        return mac
    }

    fun exportMasterVault(outputStream: OutputStream, dbFile: File, prefsJson: String) {
        val mac = getMac()

        ZipOutputStream(outputStream).use { zos ->
            zos.putNextEntry(ZipEntry("preferences.json"))
            val prefsBytes = prefsJson.toByteArray(Charsets.UTF_8)
            zos.write(prefsBytes)
            mac.update(prefsBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("database.db"))
            FileInputStream(dbFile).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    zos.write(buffer, 0, read)
                    mac.update(buffer, 0, read)
                }
            }
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("signature.txt"))
            val signature = Base64.encodeToString(mac.doFinal(), Base64.NO_WRAP)
            zos.write(signature.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }


    fun importMasterVault(inputStream: InputStream, tempDir: File): Pair<File, String>? {
        val mac = getMac()
        var prefsStr: String? = null
        var signatureStr: String? = null
        val dbTempFile = File(tempDir, "temp_restore.db")

        try {
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "preferences.json" -> {
                            val bytes = zis.readBytes()
                            prefsStr = String(bytes, Charsets.UTF_8)
                            mac.update(bytes)
                        }
                        "database.db" -> {
                            FileOutputStream(dbTempFile).use { fos ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (zis.read(buffer).also { read = it } != -1) {
                                    fos.write(buffer, 0, read)
                                    mac.update(buffer, 0, read)
                                }
                            }
                        }
                        "signature.txt" -> {
                            signatureStr = String(zis.readBytes(), Charsets.UTF_8)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (prefsStr != null && dbTempFile.exists() && signatureStr != null) {
                val calculatedSig = Base64.encodeToString(mac.doFinal(), Base64.NO_WRAP)
                if (calculatedSig == signatureStr) {
                    HatLogger.log("VaultSecurity: Signature verified.")
                    return Pair(dbTempFile, prefsStr!!)
                } else {
                    HatLogger.logError("VaultSecurity", "Signature mismatch. Expected: $signatureStr, Calculated: $calculatedSig")
                }
            } else {
                HatLogger.logError("VaultSecurity", "Missing required vault files. Prefs=${prefsStr != null}, DB=${dbTempFile.exists()}, Sig=${signatureStr != null}")
            }
        } catch (e: Exception) {
            HatLogger.logError("VaultSecurity", "Exception during Zip Extraction", e)
        }
        
        if (dbTempFile.exists()) dbTempFile.delete()
        return null
    }
}
