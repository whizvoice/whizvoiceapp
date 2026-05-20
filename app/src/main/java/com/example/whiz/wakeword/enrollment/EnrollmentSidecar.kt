package com.example.whiz.wakeword.enrollment

import org.json.JSONArray
import org.json.JSONObject

data class ClipMeta(val path: String, val wakeWordScore: Float)

data class EnrollmentSidecar(
    val version: Int,
    val enrolledAt: String,           // ISO-8601 UTC
    val phrase: String,
    val modelHash: String,             // sha256 of the wake-word ONNX at enrollment time
    val clips: List<ClipMeta>,
    val userThreshold: Float,
    val svModelHash: String? = null,   // CAM++ ONNX hash
    val svThreshold: Float? = null,    // verifier threshold at enrollment time
) {
    fun toJsonString(): String {
        val arr = JSONArray()
        for (c in clips) {
            arr.put(JSONObject().apply {
                put("path", c.path)
                put("wakeWordScore", c.wakeWordScore.toDouble())
            })
        }
        val obj = JSONObject().apply {
            put("version", version)
            put("enrolledAt", enrolledAt)
            put("phrase", phrase)
            put("modelHash", modelHash)
            put("clips", arr)
            put("userThreshold", userThreshold.toDouble())
            if (svModelHash != null) put("svModelHash", svModelHash)
            if (svThreshold != null) put("svThreshold", svThreshold.toDouble())
        }
        return obj.toString()
    }

    companion object {
        fun fromJsonString(s: String): EnrollmentSidecar {
            val obj = JSONObject(s)
            val arr = obj.getJSONArray("clips")
            val clips = ArrayList<ClipMeta>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                clips.add(ClipMeta(c.getString("path"), c.getDouble("wakeWordScore").toFloat()))
            }
            return EnrollmentSidecar(
                version = obj.getInt("version"),
                enrolledAt = obj.getString("enrolledAt"),
                phrase = obj.getString("phrase"),
                modelHash = obj.getString("modelHash"),
                clips = clips,
                userThreshold = obj.getDouble("userThreshold").toFloat(),
                svModelHash = obj.optString("svModelHash").takeIf { it.isNotEmpty() },
                svThreshold = if (obj.has("svThreshold")) obj.getDouble("svThreshold").toFloat() else null,
            )
        }
    }
}
