// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.localapi.Request
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.notifier.HealthNotifier
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.viewModel.AppViewModel
import com.tailscale.ipn.ui.viewModel.AppViewModelFactory
import com.tailscale.ipn.util.FeatureFlags
import com.tailscale.ipn.util.ShareFileHelper
import com.tailscale.ipn.util.TSLog
import java.io.IOException
import java.lang.UnsupportedOperationException
import java.net.NetworkInterface
import java.security.GeneralSecurityException
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import libtailscale.Libtailscale

class App : UninitializedApp(), libtailscale.AppContext, ViewModelStoreOwner {
  val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  companion object {
    private const val FILE_CHANNEL_ID = "tailscale-files"
    // Key to store the SAF URI in EncryptedSharedPreferences.
    private val PREF_KEY_SAF_URI = "saf_directory_uri"
    private const val TAG = "App"
    private lateinit var appInstance: App
    /**
     * Initializes the app (if necessary) and returns the singleton app instance. Always use this
     * function to obtain an App reference to make sure the app initializes.
     */
    @JvmStatic
    fun get(): App {
      appInstance.initOnce()
      return appInstance
    }
  }

  val dns = DnsConfig()
  private lateinit var connectivityManager: ConnectivityManager
  private lateinit var app: libtailscale.Application
  override val viewModelStore: ViewModelStore
    get() = appViewModelStore

  private val appViewModelStore: ViewModelStore by lazy { ViewModelStore() }
  var healthNotifier: HealthNotifier? = null

  override fun getPlatformDNSConfig(): String = dns.dnsConfigAsString

  override fun getInstallSource(): String = "tailmon"

  override fun shouldUseGoogleDNSFallback(): Boolean = BuildConfig.USE_GOOGLE_DNS_FALLBACK

  override fun log(s: String, s1: String) {
    Log.d(s, s1)
  }

  fun getLibtailscaleApp(): libtailscale.Application {
    if (!isInitialized) {
      initOnce() // Calls the synchronized initialization logic
    }
    return app
  }

  override fun onCreate() {
    super.onCreate()
    appInstance = this
    setUnprotectedInstance(this)
    createNotificationChannel(
        STATUS_CHANNEL_ID,
        getString(R.string.vpn_status),
        getString(R.string.optional_notifications_which_display_the_status_of_the_vpn_tunnel),
        NotificationManagerCompat.IMPORTANCE_MIN)
    createNotificationChannel(
        FILE_CHANNEL_ID,
        getString(R.string.taildrop_file_transfers),
        getString(R.string.notifications_delivered_when_a_file_is_received_using_taildrop),
        NotificationManagerCompat.IMPORTANCE_DEFAULT)
    createNotificationChannel(
        HealthNotifier.HEALTH_CHANNEL_ID,
        getString(R.string.health_channel_name),
        getString(R.string.health_channel_description),
        NotificationManagerCompat.IMPORTANCE_HIGH)
  }

  override fun onTerminate() {
    super.onTerminate()
    Notifier.stop()
    notificationManager.cancelAll()
    applicationScope.cancel()
    viewModelStore.clear()
  }

  @Volatile private var isInitialized = false

  @Synchronized
  private fun initOnce() {
    if (isInitialized) {
      return
    }
    initializeApp()
    isInitialized = true
  }

  private fun initializeApp() {
    val storedUri = getStoredDirectoryUri()
    if (storedUri != null && storedUri.toString().startsWith("content://")) {
      startLibtailscale(storedUri.toString(), false)
    } else {
      startLibtailscale(this.filesDir.absolutePath, false)
    }
    healthNotifier = HealthNotifier(Notifier.health, Notifier.state, applicationScope)
    connectivityManager = this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    NetworkChangeCallback.monitorDnsChanges(connectivityManager, dns)
    initViewModels()
    applicationScope.launch {
      Notifier.state.collect { state ->
        val ableToStartVPN = state > Ipn.State.NeedsMachineAuth
        updateConnStatus(ableToStartVPN)
        val vpnRunning = state == Ipn.State.Starting || state == Ipn.State.Running
        QuickToggleService.setVPNRunning(vpnRunning)
        if (state == Ipn.State.Stopped) {
          notifyStatus(vpnRunning = false, hideDisconnectAction = false)
        } else if (vpnRunning) {
          notifyStatus(vpnRunning = true, hideDisconnectAction = false)
        }
      }
    }
    // Auto-connect on every app start
    startUserspaceVPN()
    TSLog.init(this)
    FeatureFlags.initialize(mapOf("enable_new_search" to true))
  }
  /**
   * Called when a SAF directory URI is available (either already stored or chosen). We must restart
   * Tailscale because directFileRoot must be set before LocalBackend starts being used.
   */
  fun startLibtailscale(directFileRoot: String, hardwareAttestation: Boolean) {
    app = Libtailscale.startUserspace(
        this.filesDir.absolutePath, directFileRoot, hardwareAttestation, this)
    ShareFileHelper.init(this, app, directFileRoot, applicationScope)
    Request.setApp(app)
    Notifier.setApp(app)
    Notifier.start(applicationScope)
  }

  private fun initViewModels() {
    appViewModel =
        ViewModelProvider(this, AppViewModelFactory(this, ShareFileHelper.observeTaildropPrompt()))
            .get(AppViewModel::class.java)
  }

  fun setWantRunning(wantRunning: Boolean, onSuccess: (() -> Unit)? = null) {
    val callback: (Result<Ipn.Prefs>) -> Unit = { result ->
      result.fold(
          onSuccess = { onSuccess?.invoke() },
          onFailure = { error ->
            TSLog.d("TAG", "Set want running: failed to update preferences: ${error.message}")
          })
    }
    Client(applicationScope)
        .editPrefs(Ipn.MaskedPrefs().apply { WantRunning = wantRunning }, callback)
  }
  // encryptToPref a byte array of data using the Jetpack Security
  // library and writes it to a global encrypted preference store.
  @Throws(IOException::class, GeneralSecurityException::class)
  override fun encryptToPref(prefKey: String?, plaintext: String?) {
    getEncryptedPrefs().edit().putString(prefKey, plaintext).commit()
  }
  // decryptFromPref decrypts a encrypted preference using the Jetpack Security
  // library and returns the plaintext.
  @Throws(IOException::class, GeneralSecurityException::class)
  override fun decryptFromPref(prefKey: String?): String? {
    return getEncryptedPrefs().getString(prefKey, null)
  }

  override fun getStateStoreKeysJSON(): String {
    val prefix = "statestore-"
    val keys =
        getEncryptedPrefs()
            .getAll()
            .keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
    return org.json.JSONArray(keys).toString()
  }

  @Throws(IOException::class, GeneralSecurityException::class)
  fun getEncryptedPrefs(): SharedPreferences {
    val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(
        this,
        "secret_shared_prefs",
        key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
  }

  fun getStoredDirectoryUri(): Uri? {
    val uriString = getEncryptedPrefs().getString(PREF_KEY_SAF_URI, null)
    return uriString?.let { Uri.parse(it) }
  }
  /*
   * setAbleToStartVPN remembers whether or not we're able to start the VPN
   * by storing this in a shared preference. This allows us to check this
   * value without needing a fully initialized instance of the application.
   */
  private fun updateConnStatus(ableToStartVPN: Boolean) {
    setAbleToStartVPN(ableToStartVPN)
    QuickToggleService.updateTile()
    TSLog.d("App", "Set Tile Ready: $ableToStartVPN")
  }

  override fun getDeviceName(): String {
    // Try user-defined device name first
    android.provider.Settings.Global.getString(
            contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        ?.let {
          return it
        }

    // Otherwise fallback to manufacturer + model
    val manu = Build.MANUFACTURER
    var model = Build.MODEL
    // Strip manufacturer from model.
    val idx = model.lowercase(Locale.getDefault()).indexOf(manu.lowercase(Locale.getDefault()))
    if (idx != -1) {
      model = model.substring(idx + manu.length).trim()
    }
    return "$manu $model"
  }

  override fun getOSVersion(): String = Build.VERSION.RELEASE

  override fun isChromeOS(): Boolean {
    return packageManager.hasSystemFeature("android.hardware.type.pc")
  }

  @Serializable
  data class AddrJson(
      val ip: String,
      val prefixLen: Int,
  )

  @Serializable
  data class InterfaceJson(
      val name: String,
      val index: Int,
      val mtu: Int,
      val up: Boolean,
      val broadcast: Boolean,
      val loopback: Boolean,
      val pointToPoint: Boolean,
      val multicast: Boolean,
      val addrs: List<AddrJson>,
  )

  override fun getInterfacesAsJson(): String {
    val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
    val out = ArrayList<InterfaceJson>(interfaces.size)

    for (nif in interfaces) {
      try {
        val addrs = ArrayList<AddrJson>()
        for (ia in nif.interfaceAddresses) {
          val addr = ia.address ?: continue
          // hostAddress is stable and avoids InterfaceAddress.toString() formatting risks.
          val host = addr.hostAddress ?: continue
          addrs.add(AddrJson(ip = host, prefixLen = ia.networkPrefixLength.toInt()))
        }

        out.add(
            InterfaceJson(
                name = nif.name,
                index = nif.index,
                mtu = nif.mtu,
                up = nif.isUp,
                broadcast = nif.supportsMulticast(),
                loopback = nif.isLoopback,
                pointToPoint = nif.isPointToPoint,
                multicast = nif.supportsMulticast(),
                addrs = addrs,
            ))
      } catch (_: Exception) {
        continue
      }
    }

    // Avoid pretty printing to keep payload small.
    return Json { encodeDefaults = true }.encodeToString(out)
  }

  // No MDM policy — always report key not found to the Go backend.
  class NoSuchKeyException : Exception()

  @Throws(IOException::class, GeneralSecurityException::class, NoSuchKeyException::class)
  override fun getSyspolicyBooleanValue(key: String): Boolean {
    throw NoSuchKeyException()
  }

  @Throws(IOException::class, GeneralSecurityException::class, NoSuchKeyException::class)
  override fun getSyspolicyStringValue(key: String): String {
    throw NoSuchKeyException()
  }

  @Throws(IOException::class, GeneralSecurityException::class, NoSuchKeyException::class)
  override fun getSyspolicyStringArrayJSONValue(key: String): String {
    throw NoSuchKeyException()
  }

  fun notifyPolicyChanged() {
    app.notifyPolicyChanged()
  }

  override fun hardwareAttestationKeySupported(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    } else {
      false
    }
  }

  override fun hardwareAttestationKeyCreate(): String = throw UnsupportedOperationException()
  override fun hardwareAttestationKeyRelease(id: String) = throw UnsupportedOperationException()
  override fun hardwareAttestationKeySign(id: String, data: ByteArray): ByteArray = throw UnsupportedOperationException()
  override fun hardwareAttestationKeyPublic(id: String): ByteArray = throw UnsupportedOperationException()
  override fun hardwareAttestationKeyLoad(id: String) = throw UnsupportedOperationException()

  override fun bindSocketToNetwork(fd: Int): Boolean {
    val net =
        NetworkChangeCallback.cachedDefaultNetwork
            ?: run {
              TSLog.d(TAG, "bindSocketToActiveNetwork: no cached default network; noop")
              return false
            }

    val iface = NetworkChangeCallback.cachedDefaultInterfaceName

    TSLog.d(
        TAG,
        "bindSocketToActiveNetwork: binding fd=$fd to net=$net iface=$iface",
    )

    return try {
      android.os.ParcelFileDescriptor.fromFd(fd).use { pfd -> net.bindSocket(pfd.fileDescriptor) }
      true
    } catch (e: Exception) {
      TSLog.w(
          TAG,
          "bindSocketToActiveNetwork: bind failed fd=$fd net=$net iface=$iface: $e",
      )
      false
    }
  }
}

/**
 * UninitializedApp contains all of the methods of App that can be used without having to initialize
 * the Go backend. This is useful when you want to access functions on the App without creating side
 * effects from starting the Go backend (such as launching the VPN).
 */
open class UninitializedApp : Application() {
  companion object {
    const val TAG = "UninitializedApp"
    const val STATUS_NOTIFICATION_ID = 1
    const val STATUS_EXIT_NODE_FAILURE_NOTIFICATION_ID = 2
    const val STATUS_CHANNEL_ID = "tailscale-status"
    // Key for shared preference that tracks whether or not we're able to start
    // the VPN (i.e. we're logged in and machine is authorized).
    private const val ABLE_TO_START_VPN_KEY = "ableToStartVPN"

    // File for shared preferences that are not encrypted.
    private const val UNENCRYPTED_PREFERENCES = "unencrypted"
    private lateinit var appInstance: UninitializedApp
    lateinit var notificationManager: NotificationManagerCompat

    lateinit var appViewModel: AppViewModel

    @JvmStatic
    fun get(): UninitializedApp {
      return appInstance
    }
    /**
     * Return the name of the active (but not the selected/prior one) exit node based on the
     * provided [Ipn.Prefs] and [Netmap.NetworkMap].
     *
     * @return The name of the exit node or `null` if there isn't one.
     */
    fun getExitNodeName(prefs: Ipn.Prefs?, netmap: Netmap.NetworkMap?): String? {
      return prefs?.activeExitNodeID?.let { exitNodeID ->
        netmap?.Peers?.find { it.StableID == exitNodeID }?.exitNodeName
      }
    }
  }

  protected fun setUnprotectedInstance(instance: UninitializedApp) {
    appInstance = instance
  }

  protected fun setAbleToStartVPN(rdy: Boolean) {
    getUnencryptedPrefs().edit().putBoolean(ABLE_TO_START_VPN_KEY, rdy).apply()
  }
  /** This function can be called without initializing the App. */
  fun isAbleToStartVPN(): Boolean {
    return getUnencryptedPrefs().getBoolean(ABLE_TO_START_VPN_KEY, false)
  }

  private fun getUnencryptedPrefs(): SharedPreferences {
    return getSharedPreferences(UNENCRYPTED_PREFERENCES, MODE_PRIVATE)
  }

  fun startForegroundForLogin() {
    val intent =
        Intent(this, UserspaceService::class.java).apply {
          action = UserspaceService.ACTION_START
        }
    try {
      startForegroundService(intent)
    } catch (e: Exception) {
      TSLog.e(TAG, "startForegroundForLogin hit exception: $e")
    }
  }

  fun startUserspaceVPN() {
    val intent =
        Intent(this, UserspaceService::class.java).apply {
          action = UserspaceService.ACTION_START
        }
    try {
      startForegroundService(intent)
    } catch (e: Exception) {
      TSLog.e(TAG, "startUserspaceVPN hit exception: $e")
    }
  }

  fun stopUserspaceVPN() {
    val intent =
        Intent(this, UserspaceService::class.java).apply { action = UserspaceService.ACTION_STOP }
    try {
      startService(intent)
    } catch (e: Exception) {
      TSLog.e(TAG, "stopUserspaceVPN hit exception: $e")
    }
  }

  fun createNotificationChannel(id: String, name: String, description: String, importance: Int) {
    val channel = NotificationChannel(id, name, importance)
    channel.description = description
    notificationManager = NotificationManagerCompat.from(this)
    notificationManager.createNotificationChannel(channel)
  }

  fun notifyStatus(
      vpnRunning: Boolean,
      hideDisconnectAction: Boolean,
      exitNodeName: String? = null
  ) {
    notifyStatus(buildStatusNotification(vpnRunning, hideDisconnectAction, exitNodeName))
  }

  fun notifyStatus(notification: Notification) {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED) {
      // TODO: Consider calling
      //    ActivityCompat#requestPermissions
      // here to request the missing permissions, and then overriding
      //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
      //                                          int[] grantResults)
      // to handle the case where the user grants the permission. See the documentation
      // for ActivityCompat#requestPermissions for more details.
      return
    }
    notificationManager.notify(STATUS_NOTIFICATION_ID, notification)
  }

  fun buildStatusNotification(
      vpnRunning: Boolean,
      hideDisconnectAction: Boolean,
      exitNodeName: String? = null
  ): Notification {
    val title = getString(if (vpnRunning) R.string.connected else R.string.not_connected)
    val message =
        if (vpnRunning && exitNodeName != null) {
          getString(R.string.using_exit_node, exitNodeName)
        } else null
    val icon = if (vpnRunning) R.drawable.ic_notification else R.drawable.ic_notification_disabled
    val action =
        if (vpnRunning) IPNReceiver.INTENT_DISCONNECT_VPN else IPNReceiver.INTENT_CONNECT_VPN
    val actionLabel = getString(if (vpnRunning) R.string.disconnect else R.string.connect)
    val buttonIntent = Intent(this, IPNReceiver::class.java).apply { this.action = action }
    val pendingButtonIntent: PendingIntent =
        PendingIntent.getBroadcast(
            this,
            0,
            buttonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val intent =
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    val pendingIntent: PendingIntent =
        PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val builder =
        NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(!vpnRunning)
            .setOnlyAlertOnce(!vpnRunning)
            .setOngoing(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
    if (!vpnRunning || !hideDisconnectAction) {
      builder.addAction(
          NotificationCompat.Action.Builder(0, actionLabel, pendingButtonIntent).build())
    }
    return builder.build()
  }

  fun getAppScopedViewModel(): AppViewModel {
    return appViewModel
  }

  val builtInDisallowedPackageNames: List<String> =
      listOf(
      )
}
