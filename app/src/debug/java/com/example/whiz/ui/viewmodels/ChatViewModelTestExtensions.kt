package com.example.whiz.ui.viewmodels

/**
 * Debug-only extension function to register ChatViewModel for test transcription.
 */
fun ChatViewModel.registerForTestTranscription() {
    com.example.whiz.test.TestTranscriptionReceiver.activeChatViewModel = this
}
