package com.example.whiz.data.auth

// Custom exception for signaling authentication failure that requires re-login
class AuthenticationRequiredException(message: String = "Authentication required.", cause: Throwable? = null) : Exception(message, cause) 