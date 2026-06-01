package com.example.whiz.wakeword.enrollment

sealed class EnrollmentState {
    object NotEnrolled : EnrollmentState()
    data class Recording(val clipIndex: Int, val lastAcceptedScore: Float? = null) : EnrollmentState()
    data class RetryClip(val clipIndex: Int, val lastScore: Float, val retriesLeft: Int) : EnrollmentState()
    data class Complete(val threshold: Float, val enrolledAt: String) : EnrollmentState()
    data class Error(val message: String) : EnrollmentState()
}
