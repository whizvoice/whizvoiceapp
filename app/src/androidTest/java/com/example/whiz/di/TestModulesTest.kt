package com.example.whiz.di

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var debugString: String

    @Before
    fun init() {
        hiltRule.inject()
    }

    @Test
    fun testModuleReplacement() {
        Log.d("TestModulesTest", "🔬 Testing if TestAppModule is being used...")
        Log.d("TestModulesTest", "🔬 Debug string: $debugString")
        
        // If our TestAppModule is working, debugString should be "TEST_MODULE_ACTIVE"
        assert(debugString == "TEST_MODULE_ACTIVE") {
            "TestAppModule is not being used! Expected 'TEST_MODULE_ACTIVE' but got '$debugString'"
        }
        
        Log.d("TestModulesTest", "✅ TestAppModule is working correctly!")
    }
} 