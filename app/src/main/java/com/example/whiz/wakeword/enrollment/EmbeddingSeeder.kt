package com.example.whiz.wakeword.enrollment

import android.content.Context
import com.example.whiz.wakeword.detection.CamPlusOrtEmbedder
import com.example.whiz.wakeword.detection.SpeakerVerifier

/**
 * Computes CAM++ embeddings for the user's enrolled PCM clips and seeds
 * [EnrolledEmbeddingStore]. Replaces any prior protected set (and clears
 * appended entries — they were paired with the prior owner's voice).
 *
 * Heavy: loads the CAM++ ONNX session, runs once per clip (~50 ms each on a
 * recent Pixel). Call OFF the main thread.
 */
object EmbeddingSeeder {
    fun seed(context: Context, repo: EnrollmentRepository, store: EnrolledEmbeddingStore) {
        val clips = repo.loadAllClips()
        if (clips.isEmpty()) return
        CamPlusOrtEmbedder(context).use { embedder ->
            val embeddings = clips.map { pcm ->
                val sized = ShortArray(32_000)
                val take = minOf(pcm.size, 32_000)
                System.arraycopy(pcm, 0, sized, 0, take)
                SpeakerVerifier.l2Normalize(embedder.embed(sized))
            }
            store.seedProtected(embeddings)
        }
    }
}
