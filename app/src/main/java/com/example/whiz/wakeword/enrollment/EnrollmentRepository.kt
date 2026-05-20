package com.example.whiz.wakeword.enrollment

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Disk layout (all under [dir], EncryptedFile-wrapped):
 *   clip-{1..5}.pcm     16-bit signed PCM little-endian, 16 kHz mono, exactly 32000 samples
 *   enrollment.json     EnrollmentSidecar JSON
 *
 * Caller picks the path; in whiz that's filesDir/wake_word/enrollment/.
 */
class EnrollmentRepository(private val context: Context, private val dir: File) {

    init { dir.mkdirs() }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun encryptedFile(name: String): EncryptedFile =
        EncryptedFile.Builder(
            context,
            File(dir, name),
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    fun saveClip(index: Int, pcm: ShortArray) {
        require(pcm.size == CLIP_SAMPLES) {
            "Expected $CLIP_SAMPLES samples, got ${pcm.size}"
        }
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) bb.putShort(s)
        val file = clipFile(index)
        if (file.exists()) file.delete()
        encryptedFile(file.name).openFileOutput().use { it.write(bb.array()) }
    }

    fun loadClip(index: Int): ShortArray? {
        val file = clipFile(index)
        if (!file.exists()) return null
        val bytes = encryptedFile(file.name).openFileInput().use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) out[i] = bb.short
        return out
    }

    /** Returns all enrolled clips in index order (1..N). Empty list if no enrollment. */
    fun loadAllClips(): List<ShortArray> {
        val out = mutableListOf<ShortArray>()
        for (i in 1..5) {
            val clip = loadClip(i) ?: break
            out.add(clip)
        }
        return out
    }

    fun saveSidecar(sidecar: EnrollmentSidecar) {
        val file = sidecarFile()
        if (file.exists()) file.delete()
        encryptedFile(file.name).openFileOutput().use { it.write(sidecar.toJsonString().toByteArray()) }
    }

    fun loadSidecar(): EnrollmentSidecar? {
        val file = sidecarFile()
        if (!file.exists()) return null
        val bytes = encryptedFile(file.name).openFileInput().use { it.readBytes() }
        return EnrollmentSidecar.fromJsonString(String(bytes))
    }

    fun deleteAll() {
        val d = dir
        if (!d.exists()) return
        d.listFiles()?.forEach { it.delete() }
        d.delete()
    }

    private fun clipFile(index: Int) = File(dir, "clip-$index.pcm")
    private fun sidecarFile() = File(dir, "enrollment.json")

    companion object {
        const val CLIP_SAMPLES = 32_000  // 2 s @ 16 kHz
    }
}
