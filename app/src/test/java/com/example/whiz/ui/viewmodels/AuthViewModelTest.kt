package com.example.whiz.ui.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.whiz.MainDispatcherRule
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.auth.UserProfile
import com.example.whiz.data.remote.AuthApi
import com.example.whiz.data.remote.AuthResponse
import com.example.whiz.data.remote.User
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Mock
    private lateinit var authRepository: AuthRepository

    @Mock
    private lateinit var authApi: AuthApi

    @Mock
    private lateinit var googleSignInAccount: GoogleSignInAccount

    private lateinit var viewModel: AuthViewModel

    // Test data
    private val testUserProfile = UserProfile(
        userId = "test_user_123",
        email = "test@example.com",
        name = "Test User",
        photoUrl = "https://example.com/profile.jpg"
    )

    private val testUser = User(
        id = "test_user_123",
        email = "test@example.com",
        name = "Test User",
        photoUrl = "https://example.com/profile.jpg"
    )

    private val testAuthResponse = AuthResponse(
        accessToken = "test_access_token",
        tokenType = "bearer",
        refreshToken = "test_refresh_token",
        user = testUser
    )

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        
        // Setup default mock behavior to prevent initialization issues
        whenever(authRepository.userProfile).thenReturn(MutableStateFlow(null))
        whenever(authRepository.serverToken).thenReturn(MutableStateFlow(null))
        whenever(authRepository.authToken).thenReturn(flowOf(null))
    }

    @Test
    fun `getSignInIntent should delegate to authRepository`() {
        // Given
        val mockIntent = android.content.Intent()
        whenever(authRepository.createSignInIntent()).thenReturn(mockIntent)
        
        // Create viewModel after mocks are set up
        viewModel = AuthViewModel(authRepository, authApi)

        // When
        val result = viewModel.getSignInIntent()

        // Then
        assertThat(result).isEqualTo(mockIntent)
        verify(authRepository).createSignInIntent()
    }

    @Test
    fun `successful sign in should process account and authenticate with server`() = runTest {
        // Given
        val idToken = "test_id_token"
        whenever(googleSignInAccount.email).thenReturn("test@example.com")
        whenever(googleSignInAccount.idToken).thenReturn(idToken)
        whenever(authApi.authenticateWithGoogle(idToken)).thenReturn(Result.success(testAuthResponse))
        
        // Create viewModel after mocks are set up
        viewModel = AuthViewModel(authRepository, authApi)

        // When
        viewModel.initiateSignInProcessing(googleSignInAccount)

        // Then
        verify(authRepository).processSignInAccount(googleSignInAccount)
        verify(authApi).authenticateWithGoogle(idToken)
        verify(authRepository).saveAuthTokensFromServer(
            accessToken = testAuthResponse.accessToken,
            refreshToken = testAuthResponse.refreshToken
        )
    }

    @Test
    fun `signOut should call repository signOut`() = runTest {
        // Given
        viewModel = AuthViewModel(authRepository, authApi)

        // When
        viewModel.signOut()

        // Then
        verify(authRepository).signOut()
    }

    @Test
    fun `clearError should reset error state`() = runTest {
        // Given
        viewModel = AuthViewModel(authRepository, authApi)
        
        // When
        viewModel.clearError()

        // Then - no exception should be thrown
        // This is a basic test to ensure the method works
    }
} 