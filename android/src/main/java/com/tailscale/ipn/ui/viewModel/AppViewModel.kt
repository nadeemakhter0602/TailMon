// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.util.ShareFileHelper
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class AppViewModelFactory(val application: Application, private val taildropPrompt: Flow<Unit>) :
    ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
      return AppViewModel(application, taildropPrompt) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

// Application context-aware ViewModel used to track app-wide Taildrop state.
// This must be application-scoped because Tailscale may be used for file
// transfers (Taildrop) outside the activity lifecycle.
class AppViewModel(application: Application, private val taildropPrompt: Flow<Unit>) :
    AndroidViewModel(application) {
  // Select Taildrop directory
  var directoryPickerLauncher: ActivityResultLauncher<Uri?>? = null
  private val _triggerDirectoryPicker = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val triggerDirectoryPicker: SharedFlow<Unit> = _triggerDirectoryPicker
  val TAG = "AppViewModel"

  init {
    observeIncomingTaildrop()
  }

  private fun observeIncomingTaildrop() {
    viewModelScope.launch {
      taildropPrompt.collect {
        TSLog.d(TAG, "Taildrop event received, checking directory")
        checkIfTaildropDirectorySelected()
      }
    }
  }

  fun requestDirectoryPicker() {
    _triggerDirectoryPicker.tryEmit(Unit)
  }

  fun checkIfTaildropDirectorySelected() {
    val app = App.get()
    val storedUri = app.getStoredDirectoryUri()
    if (ShareFileHelper.hasValidTaildropDir()) {
      return
    }

    val documentFile = storedUri?.let { DocumentFile.fromTreeUri(app, it) }
    if (documentFile == null || !documentFile.exists() || !documentFile.canWrite()) {
      TSLog.d(
          "MainViewModel",
          "Stored directory URI is invalid or inaccessible; launching directory picker.")
      viewModelScope.launch { requestDirectoryPicker() }
    } else {
      TSLog.d("MainViewModel", "Using stored directory URI: $storedUri")
    }
  }
}
