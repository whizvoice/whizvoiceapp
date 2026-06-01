package com.example.whiz.wakeword.enrollment

import android.content.Context
import com.example.whiz.wakeword.WakeWordEngine

interface EnrollmentScorer {
    /** Score a 2 s (32 000-sample) 16-bit-PCM mono 16 kHz clip via the wake-word chain. */
    fun score(pcm: ShortArray): Float
}

class WakeWordEngineEnrollmentScorer(
    private val context: Context,
    private val modelAsset: () -> String = { WakeWordEngine.DEFAULT_CLASSIFIER_ASSET },
) : EnrollmentScorer {
    override fun score(pcm: ShortArray): Float {
        val engine = WakeWordEngine(context, classifierAsset = modelAsset())
        return try {
            engine.scoreClipDirect(pcm)
        } finally {
            engine.close()
        }
    }
}
