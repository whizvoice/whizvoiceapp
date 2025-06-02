package com.example.whiz.di

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.material3.Text
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.ui.viewmodels.ChatViewModel
import com.example.whiz.ui.theme.WhizTheme
import com.example.whiz.HiltTestActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import javax.inject.Named

@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TestModulesTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var debugString: String

    @Inject 
    @Named("DEPENDENCY_STATUS")
    lateinit var viewModelDependencyStatus: String

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

    @Test
    fun testViewModelDependencies() {
        Log.d("TestModulesTest", "🔬 Testing ViewModel dependencies...")
        Log.d("TestModulesTest", "🔬 Dependency status: $viewModelDependencyStatus")
        
        // If all ViewModel dependencies are available, should be "ALL VIEWMODEL DEPENDENCIES AVAILABLE!"
        assert(viewModelDependencyStatus == "ALL VIEWMODEL DEPENDENCIES AVAILABLE!") {
            "ViewModel dependencies not available! Expected 'ALL VIEWMODEL DEPENDENCIES AVAILABLE!' but got '$viewModelDependencyStatus'"
        }
        
        Log.d("TestModulesTest", "✅ All ViewModel dependencies are available!")
    }
} 