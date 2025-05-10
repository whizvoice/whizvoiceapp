package com.example.whiz.ui.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.whiz.R
import com.example.whiz.data.preferences.UserPreferences
import dagger.hilt.android.AndroidEntryPoint
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
        
        // Initialize views
        claudeTokenInput = findViewById(R.id.claudeTokenInput)
        asanaTokenInput = findViewById(R.id.asanaTokenInput)
        saveButton = findViewById(R.id.saveButton)
        claudeTokenStatus = findViewById(R.id.claudeTokenStatus)
        asanaTokenStatus = findViewById(R.id.asanaTokenStatus)
        
        // Load current tokens
        loadTokens()
        
        // Set up save button
        saveButton.setOnClickListener {
            saveTokens()
        }
    }
    
    private fun loadTokens() {
        // Check if tokens exist and update UI accordingly
        val hasClaudeToken = userPreferences.hasClaudeToken()
        val hasAsanaToken = userPreferences.hasAsanaToken()
        
        claudeTokenStatus.text = if (hasClaudeToken) "Token is set" else "No token set"
        asanaTokenStatus.text = if (hasAsanaToken) "Token is set" else "No token set"
        
        // Don't show the actual tokens for security
        claudeTokenInput.hint = if (hasClaudeToken) "Token is set" else "Enter Claude API token"
        asanaTokenInput.hint = if (hasAsanaToken) "Token is set" else "Enter Asana access token"
    }
    
    private fun saveTokens() {
        val claudeToken = claudeTokenInput.text.toString()
        val asanaToken = asanaTokenInput.text.toString()
        
        if (claudeToken.isNotEmpty()) {
            userPreferences.setClaudeToken(claudeToken)
        }
        
        if (asanaToken.isNotEmpty()) {
            userPreferences.setAsanaToken(asanaToken)
        }
        
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        loadTokens() // Refresh the UI
    }
} 