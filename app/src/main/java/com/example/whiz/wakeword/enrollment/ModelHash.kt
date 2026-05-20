package com.example.whiz.wakeword.enrollment

import java.io.InputStream
import java.security.MessageDigest

object ModelHash {

    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        stream.use { s ->
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
