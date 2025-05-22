package com.example.whiz.ui.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.whiz.R
import com.example.whiz.data.preferences.UserPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    
    @Inject
    lateinit var userPreferences: UserPreferences
    
    private lateinit var claudeTokenInput: EditText
    private lateinit var asanaTokenInput: EditText
    private lateinit var saveButton: Button
    private lateinit var claudeTokenStatus: TextView
    private lateinit var asanaTokenStatus: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Set the app icon in the action bar
        supportActionBar?.apply {
            setDisplayShowHomeEnabled(true)
            setIcon(R.drawable.whiz_icon)
        }
        
        // Initialize views
        claudeTokenInput = findViewById(R.id.claudeTokenInput)
        asanaTokenInput = findViewById(R.id.asanaTokenInput)
        saveButton = findViewById(R.id.saveButton)
        claudeTokenStatus = findViewById(R.id.claudeTokenStatus)
        asanaTokenStatus = findViewById(R.id.asanaTokenStatus)
        
        // Load current tokens
        initializeTokenDisplay()
        
        // Set up save button
        saveButton.setOnClickListener {
            saveTokens()
        }
    }
    
    private fun initializeTokenDisplay() {
        lifecycleScope.launch {
            userPreferences.initializeTokenStatus() // Initialize first

            // Collect token states
            userPreferences.hasClaudeToken.collectLatest { hasClaudeToken ->
                val isSet = hasClaudeToken ?: false // Handle null
                claudeTokenStatus.text = if (isSet) "Token is set" else "No token set"
                claudeTokenInput.hint = if (isSet) "Update Claude token (optional)" else "Enter Claude API token"
            }

            userPreferences.hasAsanaToken.collectLatest { hasAsanaToken ->
                val isSet = hasAsanaToken ?: false // Handle null
                asanaTokenStatus.text = if (isSet) "Token is set" else "No token set"
                asanaTokenInput.hint = if (isSet) "Update Asana token (optional)" else "Enter Asana access token"
            }
        }
    }
    
    private fun saveTokens() {
        lifecycleScope.launch {
            val claudeToken = claudeTokenInput.text.toString()
            val asanaToken = asanaTokenInput.text.toString()
            
            if (claudeToken.isNotEmpty()) {
                userPreferences.setClaudeToken(claudeToken)
            }
            
            if (asanaToken.isNotEmpty()) {
                userPreferences.setAsanaToken(asanaToken)
            }
            
            Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
            initializeTokenDisplay() // Refresh using the new function
        }
    }
} 