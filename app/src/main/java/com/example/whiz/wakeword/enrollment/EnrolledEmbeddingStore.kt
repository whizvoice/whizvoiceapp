package com.example.whiz.wakeword.enrollment

import com.example.whiz.wakeword.detection.SpeakerVerifier
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Disk-backed store of enrolled speaker embeddings.
 *
 * Layout: a single binary file `embeddings.bin` in dir, big-endian via Java DataOutputStream.
 *
 *   int32 protected_count
 *   int32 total_count
 *   for each vector: dim * float32
 *
 * The first protected_count vectors are the initial enrollment (never evicted).
 * Subsequent vectors are FIFO-evicted past cap.
 */
class EnrolledEmbeddingStore(
    private val dir: File,
    private val dim: Int,
    private val cap: Int,
    private val protectedCap: Int,
) : SpeakerVerifier.EmbeddingStore {

    init {
        require(protectedCap <= cap) { "protectedCap ($protectedCap) > cap ($cap)" }
        dir.mkdirs()
    }

    private val file: File = File(dir, "embeddings.bin")
    private var entries: MutableList<FloatArray> = mutableListOf()
    private var protectedCount: Int = 0

    init { load() }

    override fun all(): List<FloatArray> = entries.map { it.copyOf() }

    override fun count(): Int = entries.size

    fun protectedCount(): Int = protectedCount

    fun seedProtected(vectors: List<FloatArray>) {
        require(vectors.size <= protectedCap) {
            "seedProtected: ${vectors.size} > protectedCap $protectedCap"
        }
        for (v in vectors) require(v.size == dim) {
            "seedProtected: vector dim ${v.size} != $dim"
        }
        entries = vectors.map { it.copyOf() }.toMutableList()
        protectedCount = entries.size
        save()
    }

    override fun appendIfBelowCap(embedding: FloatArray): Boolean {
        if (embedding.size != dim) return false
        entries.add(embedding.copyOf())
        // Evict the oldest non-protected slot to make room. Protected entries 0..protectedCount-1
        // are never removed.
        while (entries.size > cap) {
            entries.removeAt(protectedCount)
        }
        save()
        return true
    }

    private fun save() {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(protectedCount)
            out.writeInt(entries.size)
            for (v in entries) for (x in v) out.writeFloat(x)
        }
    }

    private fun load() {
        if (!file.exists()) return
        DataInputStream(file.inputStream().buffered()).use { input ->
            protectedCount = input.readInt()
            val total = input.readInt()
            entries = MutableList(total) {
                FloatArray(dim) { input.readFloat() }
            }
        }
    }
}
