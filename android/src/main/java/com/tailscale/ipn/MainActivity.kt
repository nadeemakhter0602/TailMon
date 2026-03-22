// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
import android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.AboutView
import com.tailscale.ipn.ui.view.HealthView
import com.tailscale.ipn.ui.view.IntroView
import com.tailscale.ipn.ui.view.LoginWithAuthKeyView
import com.tailscale.ipn.ui.view.LoginWithCustomControlURLView
import com.tailscale.ipn.ui.view.MainView
import com.tailscale.ipn.ui.view.MainViewNavigation
import com.tailscale.ipn.ui.view.NotificationsView
import com.tailscale.ipn.ui.view.PeerDetails
import com.tailscale.ipn.ui.view.PermissionsView
import com.tailscale.ipn.ui.view.PrimaryActionButton
import com.tailscale.ipn.ui.view.SearchView
import com.tailscale.ipn.ui.view.SettingsView
import com.tailscale.ipn.ui.view.TaildropDirView
import com.tailscale.ipn.ui.view.TaildropDirectoryPickerPrompt
import com.tailscale.ipn.ui.view.UserSwitcherNav
import com.tailscale.ipn.ui.view.UserSwitcherView
import com.tailscale.ipn.ui.viewModel.AppViewModel
import com.tailscale.ipn.ui.viewModel.MainViewModel
import com.tailscale.ipn.ui.viewModel.MainViewModelFactory
import com.tailscale.ipn.ui.viewModel.PermissionsViewModel
import com.tailscale.ipn.ui.viewModel.PingViewModel
import com.tailscale.ipn.ui.viewModel.SettingsNav
import com.tailscale.ipn.util.ShareFileHelper
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private lateinit var navController: NavHostController
  private lateinit var appViewModel: AppViewModel
  private lateinit var viewModel: MainViewModel

  val permissionsViewModel: PermissionsViewModel by viewModels()

  companion object {
    private const val TAG = "Main Activity"
    private const val START_AT_ROOT = "startAtRoot"
  }

  private fun Context.isLandscapeCapable(): Boolean {
    return (resources.configuration.screenLayout and SCREENLAYOUT_SIZE_MASK) >=
        SCREENLAYOUT_SIZE_LARGE
  }
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  @SuppressLint("SourceLockedOrientationActivity")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // grab app to make sure it initializes
    App.get()
    appViewModel = (application as App).getAppScopedViewModel()
    viewModel =
        ViewModelProvider(this, MainViewModelFactory(appViewModel)).get(MainViewModel::class.java)

    // (jonathan) TODO: Force the app to be portrait on small screens until we have
    // proper landscape layout support
    if (!isLandscapeCapable()) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    installSplashScreen()
    val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
          if (uri != null) {
            try {
              // Try to take persistable permissions for both read and write.
              contentResolver.takePersistableUriPermission(
                  uri,
                  Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
              TSLog.e("MainActivity", "Failed to persist permissions: $e")
            }
            // Check if write permission is actually granted.
            val writePermission =
                this.checkUriPermission(
                    uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (writePermission == PackageManager.PERMISSION_GRANTED) {
              TSLog.d("MainActivity", "Write permission granted for $uri")

              lifecycleScope.launch(Dispatchers.IO) {
                try {
                  TaildropDirectoryStore.saveFileDirectory(uri)
                  permissionsViewModel.refreshCurrentDir()
                  ShareFileHelper.notifyDirectoryReady()
                  ShareFileHelper.setUri(uri.toString())
                } catch (e: Exception) {
                  TSLog.e("MainActivity", "Failed to set Taildrop root: $e")
                }
              }
            } else {
              TSLog.d(
                  "MainActivity",
                  "Write access not granted for $uri. Falling back to internal storage.")
              // Don't save directory URI and fall back to internal storage.
            }
          } else {
            TSLog.d(
                "MainActivity", "Taildrop directory not saved. Will fall back to internal storage.")
            // Fall back to internal storage.
          }
        }

    appViewModel.directoryPickerLauncher = directoryPickerLauncher

    setContent {
      var showDialog by remember { mutableStateOf(false) }

      LaunchedEffect(Unit) { appViewModel.triggerDirectoryPicker.collect { showDialog = true } }

      if (showDialog) {
        AppTheme {
          AlertDialog(
              onDismissRequest = {
                showDialog = false
                appViewModel.directoryPickerLauncher?.launch(null)
              },
              title = {
                Text(text = stringResource(id = R.string.taildrop_directory_picker_title))
              },
              text = { TaildropDirectoryPickerPrompt() },
              confirmButton = {
                PrimaryActionButton(
                    onClick = {
                      showDialog = false
                      appViewModel.directoryPickerLauncher?.launch(null)
                    }) {
                      Text(text = stringResource(id = R.string.taildrop_directory_picker_button))
                    }
              })
        }
      }

      navController = rememberNavController()

      AppTheme {
        Surface(color = MaterialTheme.colorScheme.inverseSurface) { // Background for the letterbox
          Surface {
            NavHost(
                navController = navController,
                startDestination = "main",
                enterTransition = {
                  slideInHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      initialOffsetX = { it }) +
                      fadeIn(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                },
                exitTransition = {
                  slideOutHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      targetOffsetX = { -it }) +
                      fadeOut(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                },
                popEnterTransition = {
                  slideInHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      initialOffsetX = { -it }) +
                      fadeIn(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                },
                popExitTransition = {
                  slideOutHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      targetOffsetX = { it }) +
                      fadeOut(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                }) {
                  fun backTo(route: String): () -> Unit = {
                    navController.popBackStack(route = route, inclusive = false)
                  }
                  val mainViewNav =
                      MainViewNavigation(
                          onNavigateToSettings = { navController.navigate("settings") },
                          onNavigateToPeerDetails = {
                            navController.navigate("peerDetails/${it.StableID}")
                          },
                          onNavigateToHealth = { navController.navigate("health") },
                          onNavigateToSearch = {
                            viewModel.enableSearchAutoFocus()
                            navController.navigate("search")
                          })
                  val settingsNav =
                      SettingsNav(
                          onNavigateToAbout = { navController.navigate("about") },
                          onNavigateToUserSwitcher = { navController.navigate("userSwitcher") },
                          onNavigateToPermissions = { navController.navigate("permissions") },
                          onBackToSettings = backTo("settings"),
                          onNavigateBackHome = backTo("main"))
                  val userSwitcherNav =
                      UserSwitcherNav(
                          backToSettings = backTo("settings"),
                          onNavigateHome = backTo("main"),
                          onNavigateCustomControl = {
                            navController.navigate("loginWithCustomControl")
                          },
                          onNavigateToAuthKey = { navController.navigate("loginWithAuthKey") })

                  composable("main", enterTransition = { fadeIn(animationSpec = tween(150)) }) {
                    MainView(
                        loginAtUrl = ::login,
                        navigation = mainViewNav,
                        viewModel = viewModel,
                        appViewModel = appViewModel)
                  }
                  composable("search") {
                    val autoFocus = viewModel.autoFocusSearch
                    SearchView(
                        viewModel = viewModel,
                        navController = navController,
                        onNavigateBack = { navController.popBackStack() },
                        autoFocus = autoFocus)
                  }
                  composable("settings") {
                    SettingsView(settingsNav = settingsNav, appViewModel = appViewModel)
                  }
                  composable("health") { HealthView(backTo("main")) }
                  composable(
                      "peerDetails/{nodeId}",
                      arguments = listOf(navArgument("nodeId") { type = NavType.StringType })) {
                        PeerDetails(
                            { navController.popBackStack() },
                            it.arguments?.getString("nodeId") ?: "",
                            PingViewModel())
                      }
                  composable("about") { AboutView(backTo("settings")) }
                  composable("userSwitcher") { UserSwitcherView(userSwitcherNav) }
                  composable("permissions") {
                    PermissionsView(
                        backTo("settings"),
                        { navController.navigate("taildropDir") },
                        { navController.navigate("notifications") })
                  }
                  composable("taildropDir") {
                    TaildropDirView(
                        backTo("permissions"), directoryPickerLauncher, permissionsViewModel)
                  }
                  composable("notifications") {
                    NotificationsView(backTo("permissions"), ::openApplicationSettings)
                  }
                  composable("intro", exitTransition = { fadeOut(animationSpec = tween(150)) }) {
                    IntroView(backTo("main"))
                  }
                  composable("loginWithAuthKey") {
                    LoginWithAuthKeyView(onNavigateHome = backTo("main"), backTo("userSwitcher"))
                  }
                  composable("loginWithCustomControl") {
                    LoginWithCustomControlURLView(
                        onNavigateHome = backTo("main"), backTo("userSwitcher"))
                  }
                }
            if (isIntroScreenViewedSet()) {
              navController.navigate("intro")
              setIntroScreenViewed(true)
            }
          }
        }
        // Login actions are app wide.  If we are told about a browse-to-url, we should render it
        // over whatever screen we happen to be on.
      }
    }
  }

  init {
    // Watch the model's browseToURL and launch the browser when it changes or
    // pop up a QR code to scan
    lifecycleScope.launch {
      Notifier.browseToURL.collect { url ->
        url?.let { Dispatchers.Main.run { login(it) } }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (intent.getBooleanExtra(START_AT_ROOT, false)) {
      if (this::navController.isInitialized) {
        val previousEntry = navController.previousBackStackEntry
        TSLog.d("MainActivity", "onNewIntent: previousBackStackEntry = $previousEntry")
        if (this::navController.isInitialized) {
          val previousEntry = navController.previousBackStackEntry
          TSLog.d("MainActivity", "onNewIntent: previousBackStackEntry = $previousEntry")
          if (previousEntry != null) {
            navController.popBackStack(route = "main", inclusive = false)
          } else {
            TSLog.e(
                "MainActivity",
                "onNewIntent: No previous back stack entry, navigating directly to 'main'")
            navController.navigate("main") { popUpTo("main") { inclusive = true } }
          }
        }
      }
    }
  }

  private fun login(urlString: String) {
    // Launch coroutine to listen for state changes. When the user completes login, relaunch
    // MainActivity to bring the app back to focus.
    App.get().applicationScope.launch {
      try {
        Notifier.state.collect { state ->
          if (state > Ipn.State.NeedsMachineAuth) {
            // Clear URL because if MainActivity is destroyed while backgrounded, the new instance
            // would hold the old auth URL
            Notifier.browseToURL.set(null)
            val intent =
                Intent(applicationContext, MainActivity::class.java).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                  action = Intent.ACTION_MAIN
                  addCategory(Intent.CATEGORY_LAUNCHER)
                  putExtra(START_AT_ROOT, true)
                }
            startActivity(intent)
            // Cancel coroutine once we've logged in
            this@launch.cancel()
          }
        }
      } catch (e: Exception) {
        TSLog.e(TAG, "Login: failed to start MainActivity: $e")
      }
    }
    val url = urlString.toUri()
    try {
      val customTabsIntent = CustomTabsIntent.Builder().build()
      customTabsIntent.launchUrl(this, url)
    } catch (e: Exception) {
      // Fallback to a regular browser if CustomTabsIntent fails
      try {
        val fallbackIntent = Intent(Intent.ACTION_VIEW, url)
        startActivity(fallbackIntent)
      } catch (e: Exception) {
        TSLog.e(TAG, "Login: failed to open browser: $e")
      }
    }
  }

  override fun onResume() {
    super.onResume()
  }

  override fun onStop() {
    super.onStop()
  }

  private fun openApplicationSettings() {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
          putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
  }

  private fun isIntroScreenViewedSet(): Boolean {
    return !getSharedPreferences("introScreen", Context.MODE_PRIVATE).getBoolean("seen", false)
  }

  private fun setIntroScreenViewed(seen: Boolean) {
    getSharedPreferences("introScreen", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("seen", seen)
        .apply()
  }
}
