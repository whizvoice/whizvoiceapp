package com.example.whiz.wakeword.enrollment

sealed class EnrollmentState {
    object NotEnrolled : EnrollmentState()
    data class Recording(val clipIndex: Int) : EnrollmentState()
    data class Confirming(val clipIndex: Int, val score: Float, val pcm: ShortArray) : EnrollmentState() {
        override fun equals(other: Any?) =
            other is Confirming && clipIndex == other.clipIndex && score == other.score
        override fun hashCode() = clipIndex * 31 + score.hashCode()
    }
    data class RetryClip(val clipIndex: Int, val lastScore: Float, val retriesLeft: Int) : EnrollmentState()
    data class Complete(val threshold: Float, val enrolledAt: String) : EnrollmentState()
    data class Error(val message: String) : EnrollmentState()
}
