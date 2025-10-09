package com.nhh.miniassistant.ui.screens.edit_credentials

import androidx.lifecycle.ViewModel
import com.nhh.miniassistant.data.GeminiAPIKey
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class EditCredentialsViewModel(
    private val geminiAPIKey: GeminiAPIKey,
) : ViewModel() {
    fun getGeminiAPIKey(): String? = geminiAPIKey.getAPIKey()

    fun saveGeminiAPIKey(apiKey: String) {
        geminiAPIKey.saveAPIKey(apiKey)
    }
}
