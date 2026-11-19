package com.tunnelgo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.net.TrafficStats
import libXray.DialerController
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Android VPN tunnel backed by libXray with bounded automatic profile failover. */
class TunnelVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var watchdogTask: ScheduledFuture<*>? = null
    private var ipWatchdogTask: ScheduledFuture<*>? = null
    private var speedTask: ScheduledFuture<*>? = null
    private val healthCheckInFlight = AtomicBoolean(false)
    private val ipCheckInFlight = AtomicBoolean(false)
    @Volatile private var watchdogEnabled = false
    @Volatile private var tunnelUnhealthySinceMs = 0L
    @Volatile private var failoverInProgress = false
    @Volatile private var ipReconnectInProgress = false
    @Volatile private var tunFd = -1
    private var currentProfileUri: String? = null
    private val attemptedProfiles = mutableSetOf<String>()
    private var lastUnderlyingIp: String? = null
    private var candidateUnderlyingIp: String? = null
    private var candidateIpObservations = 0
    private var lastIpReconnectMs = 0L
    private var lastRxBytes = -1L
    private var lastTxBytes = -1L
    private var lastSpeedSampleMs = 0L
    private val channelId = "tunnelgo_vpn"
    private val notificationId = 7714
    private val prefs by lazy { getSharedPreferences("tunnelgo_connection", MODE_PRIVATE) }
    private val profileStore by lazy { ProfileCryptoStore(this) }

    private val dialerController = object : DialerController {
        override fun protectFd(fd: Long): Boolean = protect(fd.toInt())
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> worker.execute { stopTunnel("Đã ngắt kết nối") }
            ACTION_START -> {
                val profile = intent.getStringExtra(EXTRA_PROFILE)
                if (profile.isNullOrBlank()) {
                    setStatus("Lỗi kết nối", "Không tìm thấy profile")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(notificationId, buildNotification("Đang khởi tạo VPN…"))
                worker.execute {
                    // A new explicit user request starts a fresh bounded failover cycle.
                    stopWatchdog()
                    cleanupCurrentSession()
                    attemptedProfiles.clear()
                    resetIpTracking()
                    failoverInProgress = false
                    currentProfileUri = profile
                    attemptedProfiles += profile
                    setStatus("Đang kết nối", "Đang xin cấu hình tunnel…")
                    startTunnel(profile)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(uri: String) {
        var pfd: ParcelFileDescriptor? = null
        try {
            currentProfileUri = uri
            check(VpnService.prepare(this) == null) {
                "Chưa được cấp quyền VPN. Hãy thử kết nối lại và chấp nhận yêu cầu hệ thống."
            }
            check(!isCoreRunning()) { "Xray core đang chạy từ một phiên trước" }

            setStatus("Đang kết nối", "Đang kiểm tra profile VLESS với Xray-core…")
            val conversion = invoke("convertShareLinksToXrayJson", JSONObject().put("text", uri))
            val outbounds = conversion.getJSONObject("data").getJSONArray("outbounds")
            check(outbounds.length() > 0) { "Xray không tạo được outbound từ profile" }

            setStatus("Đang kết nối", "Đang thiết lập Android VPN tunnel…")
            val builder = Builder()
                .setSession("TunnelGo · Xray")
                .setMtu(1500)
                .addAddress("10.111.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                // IPv4-only until the libXray TUN path has a dedicated IPv6 test.
            applyExcludedApplications(builder)
            pfd = builder.establish()
            check(pfd != null) {
                "Android không thiết lập được TUN. Có thể quyền VPN đã bị từ chối hoặc VPN khác đang hoạt động."
            }

            val fd = pfd!!.detachFd()
            pfd = null
            tunFd = fd
            val config = buildConfig(outbounds, fd)

            LibXray.registerDialerController(dialerController)
            LibXray.setDNS(dialerController, "1.1.1.1:53")
            setStatus("Đang kết nối", "Đang khởi chạy Xray-core…")
            invoke("runXray", JSONObject().put("xrayJson", config.toString()))
            check(isCoreRunning()) { "Xray-core không xác nhận trạng thái chạy" }

            startWatchdog()
            startSpeedMonitor()
            setStatus("Đang kiểm tra tunnel", "Xray đang chạy; đang xác minh HTTPS qua VPN…")
            updateNotification("Đang kiểm tra kết nối tunnel…")
            try {
                checkHttpsThroughVpn()
                markHealthPassed()
                setStatus("Đã kết nối", "Xray tunnel hoạt động và kiểm tra HTTPS thành công.")
                updateNotification("Đã kết nối · Xray VPN")
            } catch (t: Throwable) {
                // Do not kill a potentially recoverable tunnel immediately. The watchdog
                // owns the 120-second continuous failure window and will fail over once.
                markHealthFailed(t)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Xray VPN start failed", t)
            pfd?.close()
            cleanupCurrentSession()
            tryNextProfileOrStop("Profile hiện tại không khởi động được: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun buildConfig(outbounds: JSONArray, fd: Int): JSONObject {
        val selected = JSONObject(outbounds.optJSONObject(0)?.toString() ?: error("Không có Xray outbound"))
        val proxyTag = selected.optString("tag").takeIf { it.isNotBlank() } ?: "tunnelgo-proxy"
        selected.put("tag", proxyTag)
        val allOutbounds = JSONArray().put(selected)
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom").put("settings", JSONObject()))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole").put("settings", JSONObject()))
        val inbound = JSONObject()
            .put("tag", "tunnelgo-tun")
            .put("port", 0)
            .put("protocol", "tun")
            .put("settings", JSONObject().put("name", "tunnelgo0").put("mtu", 1500))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("env", JSONObject().put("xray.tun.fd", fd.toString()))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", allOutbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs"))
    }

    private fun invoke(method: String, payload: JSONObject): JSONObject {
        val request = JSONObject().put("apiVersion", 3).put("method", method).put("payload", payload)
        val response = JSONObject(LibXray.invoke(request.toString()))
        check(response.optBoolean("success")) { response.optString("error", "Lỗi Xray-core") }
        return response
    }

    private fun isCoreRunning(): Boolean = invoke("getXrayState", JSONObject())
        .optJSONObject("data")?.optBoolean("running") == true

    private fun startWatchdog() {
        stopWatchdog()
        watchdogEnabled = true
        tunnelUnhealthySinceMs = 0L
        watchdogTask = watchdogExecutor.scheduleAtFixedRate({
            if (watchdogEnabled) worker.execute { runWatchdogCheck() }
        }, WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS)
        ipWatchdogTask = watchdogExecutor.scheduleAtFixedRate({
            if (watchdogEnabled) worker.execute { runUnderlyingIpCheck() }
        }, IP_CHECK_INTERVAL_SECONDS, IP_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private fun stopWatchdog() {
        watchdogEnabled = false
        watchdogTask?.cancel(false)
        watchdogTask = null
        ipWatchdogTask?.cancel(false)
        ipWatchdogTask = null
        stopSpeedMonitor()
        healthCheckInFlight.set(false)
        ipCheckInFlight.set(false)
        tunnelUnhealthySinceMs = 0L
        resetIpTracking()
    }

    private fun startSpeedMonitor() {
        stopSpeedMonitor()
        lastRxBytes = -1L
        lastTxBytes = -1L
        lastSpeedSampleMs = 0L
        prefs.edit()
            .putLong("download_bps", 0L).putLong("upload_bps", 0L)
            .putLong("download_min_bps", Long.MAX_VALUE).putLong("download_max_bps", 0L)
            .putLong("upload_min_bps", Long.MAX_VALUE).putLong("upload_max_bps", 0L)
            .apply()
        speedTask = watchdogExecutor.scheduleAtFixedRate({
            if (watchdogEnabled) updateTrafficSpeed()
        }, 1L, 1L, TimeUnit.SECONDS)
    }

    private fun stopSpeedMonitor() {
        speedTask?.cancel(false)
        speedTask = null
        lastRxBytes = -1L
        lastTxBytes = -1L
        lastSpeedSampleMs = 0L
        prefs.edit().putLong("download_bps", 0L).putLong("upload_bps", 0L)
            .putLong("download_min_bps", 0L).putLong("download_max_bps", 0L)
            .putLong("upload_min_bps", 0L).putLong("upload_max_bps", 0L).apply()
    }

    private fun updateTrafficSpeed() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        if (rx < 0L || tx < 0L) return
        if (lastRxBytes >= 0L && lastTxBytes >= 0L && lastSpeedSampleMs > 0L) {
            val elapsedMs = (now - lastSpeedSampleMs).coerceAtLeast(1L)
            val downloadBps = ((rx - lastRxBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
            val uploadBps = ((tx - lastTxBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
            val oldDownloadMin = prefs.getLong("download_min_bps", Long.MAX_VALUE)
            val oldUploadMin = prefs.getLong("upload_min_bps", Long.MAX_VALUE)
            prefs.edit().putLong("download_bps", downloadBps).putLong("upload_bps", uploadBps)
                .putLong("download_min_bps", minOf(oldDownloadMin, downloadBps))
                .putLong("download_max_bps", maxOf(prefs.getLong("download_max_bps", 0L), downloadBps))
                .putLong("upload_min_bps", minOf(oldUploadMin, uploadBps))
                .putLong("upload_max_bps", maxOf(prefs.getLong("upload_max_bps", 0L), uploadBps))
                .apply()
        }
        lastRxBytes = rx
        lastTxBytes = tx
        lastSpeedSampleMs = now
    }

    /**
     * Query the public address through the physical network, not the VPN network.
     * This observes the device's current NAT/egress identity without routing the
     * probe through the tunnel being monitored.
     */
    private fun runUnderlyingIpCheck() {
        if (!watchdogEnabled || ipReconnectInProgress || !ipCheckInFlight.compareAndSet(false, true)) return
        try {
            val ip = queryUnderlyingPublicIp() ?: return
            val previous = lastUnderlyingIp
            if (previous == null) {
                lastUnderlyingIp = ip
                candidateUnderlyingIp = null
                candidateIpObservations = 0
                return
            }
            if (ip == previous) {
                candidateUnderlyingIp = null
                candidateIpObservations = 0
                return
            }
            if (candidateUnderlyingIp == ip) candidateIpObservations++
            else {
                candidateUnderlyingIp = ip
                candidateIpObservations = 1
            }
            // Require two consecutive observations so a transient endpoint or
            // captive-portal response cannot cause an unnecessary reconnect.
            if (candidateIpObservations >= IP_CHANGE_CONFIRMATIONS) {
                requestReconnectAfterIpChange(previous, ip)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Underlying IP check failed: ${t.message}")
        } finally {
            ipCheckInFlight.set(false)
        }
    }

    private fun queryUnderlyingPublicIp(): String? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.allNetworks.firstOrNull { candidate ->
            val caps = cm.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: return null
        val connection = network.openConnection(URL(IP_CHECK_URL)) as? HttpsURLConnection ?: return null
        return try {
            connection.connectTimeout = IP_CONNECT_TIMEOUT_MS
            connection.readTimeout = IP_READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.inputStream.bufferedReader().use { it.readText().trim() }
                .takeIf { it.matches(IPV4_OR_IPV6_REGEX) }
        } finally {
            connection.disconnect()
        }
    }

    private fun requestReconnectAfterIpChange(oldIp: String, newIp: String) {
        val now = System.currentTimeMillis()
        if (ipReconnectInProgress || now - lastIpReconnectMs < IP_RECONNECT_COOLDOWN_MS) return
        val uri = currentProfileUri ?: return
        ipReconnectInProgress = true
        lastIpReconnectMs = now
        candidateUnderlyingIp = null
        candidateIpObservations = 0
        lastUnderlyingIp = newIp
        stopWatchdog()
        setStatus("Đang kết nối lại", "Phát hiện IP/NAT thay đổi ($oldIp → $newIp). Đang gửi lại yêu cầu kết nối…")
        updateNotification("Mạng thay đổi · đang kết nối lại")
        cleanupCurrentSession()
        // Reuse the current profile; this is a network rebind, not a server
        // failover. The cooldown and in-progress gate prevent reconnect spam.
        startTunnel(uri)
        ipReconnectInProgress = false
    }

    private fun resetIpTracking() {
        lastUnderlyingIp = null
        candidateUnderlyingIp = null
        candidateIpObservations = 0
    }

    private fun applyExcludedApplications(builder: Builder) {
        val packages = prefs.getStringSet(EXCLUDED_APPS_KEY, emptySet()).orEmpty()
        for (packageName in packages) {
            try {
                builder.addDisallowedApplication(packageName)
            } catch (t: Throwable) {
                Log.w(TAG, "Không thể loại trừ app $packageName: ${t.message}")
            }
        }
    }

    private fun runWatchdogCheck() {
        if (!watchdogEnabled || failoverInProgress || !healthCheckInFlight.compareAndSet(false, true)) return
        try {
            check(isCoreRunning()) { "Xray-core đã dừng" }
            checkHttpsThroughVpn()
            markHealthPassed()
            if (prefs.getString("status", "") != "Đã kết nối") {
                setStatus("Đã kết nối", "Kết nối đã hồi phục sau khi kiểm tra watchdog.")
                updateNotification("Đã kết nối · Xray VPN")
            }
        } catch (t: Throwable) {
            markHealthFailed(t)
            val since = tunnelUnhealthySinceMs
            if (since > 0L && System.currentTimeMillis() - since >= WATCHDOG_TIMEOUT_MS) {
                triggerFailover("Máy chủ hiện tại không phản hồi liên tục 2 phút.")
            }
        } finally {
            healthCheckInFlight.set(false)
        }
    }

    private fun markHealthPassed() {
        tunnelUnhealthySinceMs = 0L
    }

    private fun markHealthFailed(error: Throwable) {
        if (tunnelUnhealthySinceMs == 0L) tunnelUnhealthySinceMs = System.currentTimeMillis()
        val elapsed = (System.currentTimeMillis() - tunnelUnhealthySinceMs).coerceAtLeast(0L) / 1000L
        val detail = "Chưa nhận được phản hồi từ máy chủ (${elapsed}s/120s). ${error.message ?: "Đang thử lại…"}"
        setStatus("Đang kiểm tra tunnel", detail)
        updateNotification("Đang chờ phản hồi máy chủ…")
        Log.w(TAG, "Watchdog health check failed: ${error.message}")
    }

    private fun checkHttpsThroughVpn() {
        val urls = listOf("https://cp.cloudflare.com/generate_204", "https://www.google.com/generate_204")
        var lastError: Throwable? = null
        for (endpoint in urls) {
            var connection: HttpsURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
                    connectTimeout = HEALTH_CONNECT_TIMEOUT_MS
                    readTimeout = HEALTH_READ_TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                }
                val code = connection.responseCode
                if (code in 200..399) return
                lastError = IllegalStateException("Health check trả HTTP $code")
            } catch (t: Throwable) { lastError = t }
            finally { connection?.disconnect() }
        }
        throw IllegalStateException("HTTPS health check thất bại: ${lastError?.message ?: "không có phản hồi"}")
    }

    private fun triggerFailover(reason: String) {
        if (!watchdogEnabled || failoverInProgress) return
        failoverInProgress = true
        stopWatchdog()
        val next = nextAvailableProfile()
        if (next == null) {
            cleanupCurrentSession()
            setStatus("Lỗi kết nối", "$reason Đã thử hết profile dự phòng; hãy kiểm tra lại cấu hình máy chủ.")
            updateNotification("Không còn máy chủ dự phòng khả dụng")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        attemptedProfiles += next.payload
        val displayName = next.name
        cleanupCurrentSession()
        setStatus("Đang chuyển máy chủ", "$reason Tự động chuyển sang máy chủ dự phòng: $displayName…")
        updateNotification("Đang chuyển sang: $displayName")
        currentProfileUri = next.payload
        failoverInProgress = false
        startTunnel(next.payload)
    }

    private fun tryNextProfileOrStop(reason: String) {
        val next = nextAvailableProfile()
        if (next != null) {
            attemptedProfiles += next.payload
            cleanupCurrentSession()
            setStatus("Đang chuyển máy chủ", "$reason Tự động chuyển sang máy chủ dự phòng: ${next.name}…")
            updateNotification("Đang chuyển sang: ${next.name}")
            currentProfileUri = next.payload
            startTunnel(next.payload)
        } else {
            cleanupCurrentSession()
            setStatus("Lỗi kết nối", "$reason Đã thử hết profile dự phòng; hãy kiểm tra lại cấu hình máy chủ.")
            updateNotification("Kết nối thất bại · cần kiểm tra profile")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun nextAvailableProfile(): StoredProfile? = try {
        profileStore.load().firstOrNull {
            it.type.startsWith("VLESS") && it.payload !in attemptedProfiles
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Cannot load backup profiles", t)
        null
    }

    private fun cleanupCurrentSession() {
        try { invoke("stopXray", JSONObject()) } catch (_: Throwable) { }
        try { LibXray.resetDNS() } catch (_: Throwable) { }
        closeTunFd()
    }

    private fun stopTunnel(message: String) {
        stopWatchdog()
        ipReconnectInProgress = false
        failoverInProgress = true
        cleanupCurrentSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        setStatus("Đã ngắt kết nối", message)
        failoverInProgress = false
        stopSelf()
    }

    private fun closeTunFd() {
        if (tunFd >= 0) {
            try { ParcelFileDescriptor.adoptFd(tunFd).close() } catch (_: Throwable) { }
            tunFd = -1
        }
    }

    override fun onRevoke() {
        worker.execute { stopTunnel("Quyền VPN đã bị thu hồi bởi Android") }
        super.onRevoke()
    }

    override fun onDestroy() {
        stopWatchdog()
        cleanupCurrentSession()
        setStatus("Đã ngắt kết nối", "VPN service đã dừng")
        stopForeground(STOP_FOREGROUND_REMOVE)
        worker.shutdownNow()
        watchdogExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun setStatus(status: String, details: String) {
        prefs.edit().putString("status", status).putString("details", details).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "TunnelGo VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("TP VPN · VPN")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(notificationId, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "com.tunnelgo.app.action.START"
        const val ACTION_STOP = "com.tunnelgo.app.action.STOP"
        const val ACTION_STATUS = "com.tunnelgo.app.action.STATUS"
        const val EXTRA_PROFILE = "com.tunnelgo.app.extra.PROFILE"
        private const val WATCHDOG_INTERVAL_SECONDS = 15L
        private const val IP_CHECK_INTERVAL_SECONDS = 30L
        private const val IP_CHANGE_CONFIRMATIONS = 2
        private const val IP_RECONNECT_COOLDOWN_MS = 120_000L
        private const val IP_CONNECT_TIMEOUT_MS = 5_000
        private const val IP_READ_TIMEOUT_MS = 5_000
        private const val IP_CHECK_URL = "https://api64.ipify.org"
        private val IPV4_OR_IPV6_REGEX = Regex("^[0-9a-fA-F:.]{3,45}$")
        private const val EXCLUDED_APPS_KEY = "excluded_apps"
        private const val WATCHDOG_TIMEOUT_MS = 120_000L
        private const val HEALTH_CONNECT_TIMEOUT_MS = 8_000
        private const val HEALTH_READ_TIMEOUT_MS = 8_000
        private const val TAG = "TunnelGoXray"
    }
}
