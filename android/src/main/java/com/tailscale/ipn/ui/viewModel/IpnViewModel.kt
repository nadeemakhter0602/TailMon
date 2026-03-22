// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.UserID
import com.tailscale.ipn.ui.model.deepCopy
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Base model for most models in this application. Provides common facilities for watching IPN
 * notifications, managing login/logout, updating preferences, etc.
 */
open class IpnViewModel : ViewModel() {
  protected val TAG = this::class.simpleName

  val loggedInUser: StateFlow<IpnLocal.LoginProfile?> = MutableStateFlow(null)
  val loginProfiles: StateFlow<List<IpnLocal.LoginProfile>?> = MutableStateFlow(null)

  // The userId associated with the current node. ie: The logged in user.
  private var selfNodeUserId: UserID? = null

  val prefs = Notifier.prefs
  val netmap = Notifier.netmap

  init {
    viewModelScope.launch {
      Notifier.state.collect {
        viewModelScope.launch { loadUserProfiles() }
      }
    }

    viewModelScope.launch {
      Notifier.netmap.collect {
        it?.SelfNode?.User.let {
          if (it != selfNodeUserId) {
            selfNodeUserId = it
            viewModelScope.launch { loadUserProfiles() }
          }
        }
      }
    }

    viewModelScope.launch { loadUserProfiles() }

    TSLog.d(TAG, "Created")
  }

  // VPN Control
  fun startVPN() {
    App.get().startUserspaceVPN()
  }

  fun stopVPN() {
    App.get().stopUserspaceVPN()
  }

  // Login/Logout

  /**
   * Order of operations:
   * 1. editPrefs() with maskedPrefs (to allow ControlURL override), LoggedOut=false if AuthKey !=
   *    null
   * 2. start() starts the LocalBackend state machine with WantRunning=true.
   * 3. startLoginInteractive() is currently required for both interactive and non-interactive
   *    (using auth key) login
   *
   * Any failure short-circuits the chain and invokes completionHandler once.
   */
  fun login(
      maskedPrefs: Ipn.MaskedPrefs? = null,
      authKey: String? = null,
      completionHandler: (Result<Unit>) -> Unit = {}
  ) {
    App.get().startForegroundForLogin()
    val client = Client(viewModelScope)

    val finalMaskedPrefs = maskedPrefs?.deepCopy() ?: Ipn.MaskedPrefs()
    if (authKey != null) {
      finalMaskedPrefs.LoggedOut = false
    }

    client.editPrefs(finalMaskedPrefs) { editResult ->
      editResult
          .onFailure {
            TSLog.e(TAG, "editPrefs() failed: ${it.message}")
            completionHandler(Result.failure(it))
          }
          .onSuccess {
            it.WantRunning = true
            val opts = Ipn.Options(UpdatePrefs = it, AuthKey = authKey)
            client.start(opts) { startResult ->
              startResult
                  .onFailure {
                    TSLog.e(TAG, "start() failed: ${it.message}")
                    completionHandler(Result.failure(it))
                  }
                  .onSuccess {
                    client.startLoginInteractive { loginResult ->
                      loginResult
                          .onFailure {
                            TSLog.e(TAG, "startLoginInteractive() failed: ${it.message}")
                            completionHandler(Result.failure(it))
                          }
                          .onSuccess { completionHandler(Result.success(Unit)) }
                    }
                  }
            }
          }
    }
  }

  fun loginWithAuthKey(authKey: String, completionHandler: (Result<Unit>) -> Unit = {}) {
    val prefs = Ipn.MaskedPrefs()
    prefs.WantRunning = true
    login(prefs, authKey = authKey, completionHandler)
  }

  fun loginWithCustomControlURL(
      controlURL: String,
      completionHandler: (Result<Unit>) -> Unit = {}
  ) {
    val prefs = Ipn.MaskedPrefs()
    prefs.ControlURL = controlURL
    login(prefs, completionHandler = completionHandler)
  }

  fun logout(completionHandler: (Result<String>) -> Unit = {}) {
    Client(viewModelScope).logout { result ->
      result
          .onSuccess { TSLog.d(TAG, "Logout started: $it") }
          .onFailure { TSLog.e(TAG, "Error starting logout: ${it.message}") }
      completionHandler(result)
    }
  }

  // User Profiles

  private fun loadUserProfiles() {
    Client(viewModelScope).profiles { result ->
      result.onSuccess(loginProfiles::set).onFailure {
        TSLog.e(TAG, "Error loading profiles: ${it.message}")
      }
    }

    Client(viewModelScope).currentProfile { result ->
      result
          .onSuccess { loggedInUser.set(if (it.isEmpty()) null else it) }
          .onFailure { TSLog.e(TAG, "Error loading current profile: ${it.message}") }
    }
  }

  fun switchProfile(profile: IpnLocal.LoginProfile, completionHandler: (Result<String>) -> Unit) {
    val switchProfile = {
      Client(viewModelScope).switchProfile(profile) {
        startVPN()
        completionHandler(it)
      }
    }
    Client(viewModelScope).editPrefs(Ipn.MaskedPrefs().apply { WantRunning = false }) { result ->
      result
          .onSuccess { switchProfile() }
          .onFailure { TSLog.e(TAG, "Error setting wantRunning to false: ${it.message}") }
    }
  }

  fun addProfile(completionHandler: (Result<String>) -> Unit) {
    Client(viewModelScope).addProfile {
      if (it.isSuccess) {
        login()
      }
      startVPN()
      completionHandler(it)
    }
  }

  fun deleteProfile(profile: IpnLocal.LoginProfile, completionHandler: (Result<String>) -> Unit) {
    Client(viewModelScope).deleteProfile(profile) {
      viewModelScope.launch { loadUserProfiles() }
      completionHandler(it)
    }
  }
}
