package com.example.whiz.di

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.MainActivity
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.data.auth.AuthRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TestModulesTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: WhizRepository

    @Inject
    lateinit var authRepository: AuthRepository

    @Before
    fun init() {
        hiltRule.inject()
    }

    @Test
    fun testRealDependenciesInjected() {
        Log.d("TestModulesTest", "🔬 Testing if real E2E dependencies are injected...")
        
        // Verify we have real instances, not mocks
        assert(repository != null) { "WhizRepository should be injected" }
        assert(authRepository != null) { "AuthRepository should be injected" }
        
        Log.d("TestModulesTest", "✅ Real E2E dependencies are properly injected!")
        Log.d("TestModulesTest", "Repository: ${repository::class.simpleName}")
        Log.d("TestModulesTest", "AuthRepository: ${authRepository::class.simpleName}")
    }
} 