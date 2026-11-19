package com.tunnelgo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : Activity() {
    private lateinit var store: ProfileCryptoStore
    private lateinit var listContainer: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusDetails: TextView
    private lateinit var speedText: TextView
    private lateinit var connectButton: Button
    private var pendingProfileUri: String? = null
    private val bg = Color.rgb(11, 15, 25)
    private val panel = Color.rgb(20, 27, 41)
    private val text = Color.rgb(238, 242, 247)
    private val muted = Color.rgb(153, 166, 184)
    private val mint = Color.rgb(16, 185, 129)
    private val amber = Color.rgb(245, 158, 11)
    private val fileRequestCode = 4102
    private val vpnRequestCode = 4103
    private val excludedAppsKey = "excluded_apps"
    private val statusPoller = Handler(Looper.getMainLooper())
    private val welcomeDurationMs = 1800L
    private val welcomeRunnable = Runnable {
        if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@Runnable
        setContentView(buildScreen())
        refreshProfiles()
        refreshConnectionStatus()
        statusPoller.post(statusRefreshRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        store = ProfileCryptoStore(this)
        setContentView(buildWelcomeScreen())
        statusPoller.postDelayed(welcomeRunnable, welcomeDurationMs)
    }

    override fun onResume() {
        super.onResume()
        if (::statusTitle.isInitialized) {
            refreshConnectionStatus()
            statusPoller.post(statusRefreshRunnable)
        }
    }

    override fun onPause() {
        statusPoller.removeCallbacks(statusRefreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        statusPoller.removeCallbacks(welcomeRunnable)
        statusPoller.removeCallbacks(statusRefreshRunnable)
        super.onDestroy()
    }

    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            refreshConnectionStatus()
            statusPoller.postDelayed(this, 900)
        }
    }

    private fun buildWelcomeScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 20, 35), Color.rgb(8, 56, 66), Color.rgb(16, 185, 129))
            )
        }
        val badge = TextView(this).apply {
            text = "✦  TP VPN  ✦"
            textSize = 31f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val welcome = TextView(this).apply {
            text = "Chào Mừng Chủ Nhân\nĐã Quay Về Nhà"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val subtitle = TextView(this).apply {
            text = "Kết nối riêng tư · nhanh chóng · an toàn"
            textSize = 13f
            setTextColor(Color.argb(220, 255, 255, 255))
            gravity = Gravity.CENTER
        }
        root.addView(badge, lp(bottom = 22))
        root.addView(welcome)
        root.addView(subtitle, lp(top = 12))
        badge.startAnimation(AnimationSet(true).apply {
            addAnimation(AlphaAnimation(0f, 1f).apply { duration = 900 })
            addAnimation(ScaleAnimation(0.82f, 1f, 0.82f, 1f, 1, 0.5f, 1, 0.5f).apply { duration = 900 })
        })
        welcome.startAnimation(AlphaAnimation(0f, 1f).apply { duration = 1200; startOffset = 250 })
        subtitle.startAnimation(AlphaAnimation(0f, 1f).apply { duration = 1200; startOffset = 450 })
        return root
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); clipToPadding = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(34), dp(22), dp(28))
        }
        scroll.addView(root)

        root.addView(label("TP VPN", 30f, text, true))
        root.addView(label("QUẢN LÝ PROFILE VPN · MVP", 11f, mint, true), lp(top = 3, bottom = 20))

        val statusCard = card()
        statusCard.addView(label("TRẠNG THÁI", 11f, muted, true))
        statusTitle = label("Đã ngắt kết nối", 21f, amber, true)
        statusDetails = label("Chọn profile VLESS rồi nhấn kết nối để bắt đầu Xray VPN.", 14f, muted)
        statusCard.addView(statusTitle, lp(top = 9))
        statusCard.addView(statusDetails, lp(top = 7))
        speedText = label("Download hiện tại: —\nUpload hiện tại: —\nDownload min/max: — / —\nUpload min/max: — / —", 13f, mint, true)
        statusCard.addView(speedText, lp(top = 12))
        root.addView(statusCard, lp(bottom = 14))

        connectButton = Button(this).apply {
            text = "Chọn profile để kết nối VPN"
            isEnabled = true
            setTextColor(Color.rgb(2, 28, 21))
            backgroundTintList = ColorStateList.valueOf(mint)
            minHeight = dp(54)
            setOnClickListener { chooseProfileToConnect() }
        }
        root.addView(connectButton, lp(bottom = 16))

        val profileHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        profileHeader.addView(label("Profile của bạn", 19f, text, true), LinearLayout.LayoutParams(0, dp(44), 1f))
        profileHeader.addView(label("được mã hóa cục bộ", 11f, muted))
        root.addView(profileHeader)

        val addVless = actionButton("＋  Thêm VLESS / Reality URI", mint)
        addVless.setOnClickListener { showVlessDialog() }
        root.addView(addVless, lp(top = 8, bottom = 8))
        val scanQr = actionButton("▣  Quét mã QR cấu hình", panel)
        scanQr.setOnClickListener { scanQrCode() }
        root.addView(scanQr, lp(bottom = 8))
        val excludedApps = actionButton("▣  Chọn ứng dụng không dùng VPN", panel)
        excludedApps.setOnClickListener { showExcludedAppsDialog() }
        root.addView(excludedApps, lp(bottom = 14))

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        val note = card().apply {
            addView(label("V2Ray / VLESS · Xray core", 14f, mint, true))
            addView(label("TP VPN nhận URI vless:// hoặc quét mã QR chứa cấu hình VLESS/V2Ray. Ứng dụng được chọn trong mục loại trừ sẽ đi thẳng qua mạng thường, không qua VPN.", 13f, muted), lp(top = 8))
        }
        root.addView(note, lp(top = 18))
        root.addView(label("Không gửi profile hoặc khóa riêng lên máy chủ. Hãy chỉ nhập cấu hình từ nhà cung cấp mà bạn tin cậy.", 12f, muted), lp(top = 12))
        return scroll
    }

    private fun refreshProfiles() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val profiles = try { store.load() } catch (e: Exception) {
            Toast.makeText(this, "Không đọc được kho profile: ${e.message ?: "lỗi mã hóa"}", Toast.LENGTH_LONG).show()
            emptyList()
        }
        if (profiles.isEmpty()) {
            listContainer.addView(card().apply {
                addView(label("Chưa có profile", 15f, muted, true))
                addView(label("Thêm URI VLESS hoặc quét mã QR để bắt đầu.", 13f, muted), lp(top = 6))
            })
            return
        }
        profiles.forEach { profile ->
            val item = card()
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(label(profile.name, 16f, text, true))
            details.addView(label("${profile.type}  ·  ${profile.host}:${profile.port}", 13f, muted), lp(top = 5))
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val delete = TextView(this).apply {
                this.text = "Xóa"
                textSize = 13f
                setTextColor(Color.rgb(248, 113, 113))
                setPadding(dp(12), dp(12), dp(4), dp(12))
                setOnClickListener { confirmDelete(profile) }
            }
            row.addView(delete)
            item.addView(row)
            item.setOnClickListener { showProfileActions(profile) }
            listContainer.addView(item, lp(bottom = 10))
        }
    }

    private fun refreshConnectionStatus() {
        if (!::statusTitle.isInitialized) return
        val prefs = getSharedPreferences("tunnelgo_connection", Context.MODE_PRIVATE)
        val status = prefs.getString("status", "Đã ngắt kết nối") ?: "Đã ngắt kết nối"
        val details = prefs.getString("details", "Chọn profile VLESS rồi nhấn kết nối để bắt đầu Xray VPN.")
            ?: "Chọn profile VLESS rồi nhấn kết nối để bắt đầu Xray VPN."
        val download = prefs.getLong("download_bps", 0L)
        val upload = prefs.getLong("upload_bps", 0L)
        statusTitle.text = status
        statusDetails.text = details
        val downloadMin = prefs.getLong("download_min_bps", 0L).let { if (it == Long.MAX_VALUE) 0L else it }
        val downloadMax = prefs.getLong("download_max_bps", 0L)
        val uploadMin = prefs.getLong("upload_min_bps", 0L).let { if (it == Long.MAX_VALUE) 0L else it }
        val uploadMax = prefs.getLong("upload_max_bps", 0L)
        speedText.text = "Download hiện tại: ${formatRate(download)}\n" +
            "Upload hiện tại: ${formatRate(upload)}\n" +
            "Download min/max: ${formatRate(downloadMin)} / ${formatRate(downloadMax)}\n" +
            "Upload min/max: ${formatRate(uploadMin)} / ${formatRate(uploadMax)}"
        statusTitle.setTextColor(when (status) {
            "Đã kết nối" -> mint
            "Đang kết nối", "Đang kiểm tra tunnel", "Đang kết nối lại", "Đang chuyển máy chủ" -> amber
            "Lỗi kết nối" -> Color.rgb(248, 113, 113)
            else -> amber
        })
        val active = status in setOf("Đang kết nối", "Đang kiểm tra tunnel", "Đang kết nối lại", "Đang chuyển máy chủ", "Đã kết nối")
        connectButton.text = if (active) "Ngắt kết nối VPN" else "Chọn profile để kết nối VPN"
        connectButton.backgroundTintList = ColorStateList.valueOf(if (active) Color.rgb(185, 58, 67) else mint)
        connectButton.setTextColor(if (active) Color.WHITE else Color.rgb(2, 28, 21))
    }

    private fun chooseProfileToConnect() {
        val current = getSharedPreferences("tunnelgo_connection", Context.MODE_PRIVATE)
            .getString("status", "Đã ngắt kết nối")
        if (current in setOf("Đang kết nối", "Đang kiểm tra tunnel", "Đang kết nối lại", "Đang chuyển máy chủ", "Đã kết nối")) {
            AlertDialog.Builder(this)
                .setTitle("Ngắt VPN?")
                .setMessage("Xray VPN đang chạy hoặc đang kết nối.")
                .setNegativeButton("Giữ kết nối", null)
                .setPositiveButton("Ngắt kết nối") { _, _ -> stopVpnService() }
                .show()
            return
        }
        val choices = try { store.load().filter { it.type.startsWith("VLESS") } } catch (_: Exception) { emptyList() }
        if (choices.isEmpty()) {
            Toast.makeText(this, "Hãy thêm profile VLESS/Reality hoặc quét mã QR trước.", Toast.LENGTH_LONG).show()
            return
        }
        if (choices.size == 1) connectProfile(choices.first())
        else AlertDialog.Builder(this)
            .setTitle("Chọn profile VLESS")
            .setItems(choices.map { it.name }.toTypedArray()) { _, which -> connectProfile(choices[which]) }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showProfileActions(profile: StoredProfile) {
        val isVless = profile.type.startsWith("VLESS")
        val actions = if (isVless) arrayOf("Kết nối VPN bằng Xray", "Chi tiết profile", "Xóa profile")
            else arrayOf("Chi tiết profile", "Xóa profile")
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(actions) { _, which ->
                if (isVless && which == 0) connectProfile(profile)
                else if (which == if (isVless) 1 else 0) {
                    AlertDialog.Builder(this)
                        .setTitle(profile.name)
                        .setMessage("Loại: ${profile.type}\nMáy chủ: ${profile.host}\nCổng: ${profile.port}\n\nBí mật profile được lưu mã hóa trên thiết bị.")
                        .setPositiveButton("Đóng", null).show()
                } else confirmDelete(profile)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun connectProfile(profile: StoredProfile) {
        if (!profile.type.startsWith("VLESS")) {
            Toast.makeText(this, "Định dạng profile chưa được hỗ trợ.", Toast.LENGTH_LONG).show()
            return
        }
        pendingProfileUri = profile.payload
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, vpnRequestCode)
        else startVpnService(profile.payload)
    }

    private fun startVpnService(profileUri: String) {
        val intent = Intent(this, TunnelVpnService::class.java)
            .setAction(TunnelVpnService.ACTION_START)
            .putExtra(TunnelVpnService.EXTRA_PROFILE, profileUri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        getSharedPreferences("tunnelgo_connection", Context.MODE_PRIVATE).edit()
            .putString("status", "Đang kết nối")
            .putString("details", "Đang khởi động Android VPN service…")
            .apply()
        refreshConnectionStatus()
    }

    private fun stopVpnService() {
        startService(Intent(this, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_STOP))
    }

    private fun showVlessDialog() {
        val input = EditText(this).apply {
            hint = "vless://UUID@host:port?security=reality&..."
            setHintTextColor(muted)
            setTextColor(this@MainActivity.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Thêm VLESS / Reality")
            .setMessage("Dán URI từ nhà cung cấp VPN. URI chứa thông tin nhạy cảm và sẽ chỉ được lưu mã hóa trên thiết bị.")
            .setView(input)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Kiểm tra & lưu", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val raw = input.text.toString().trim()
                    val parsed = VlessParser.parse(raw)
                    val updated = store.load().toMutableList()
                    require(updated.none { it.payload == raw }) { "Profile này đã có trong danh sách" }
                    updated.add(StoredProfile(parsed.id, parsed.name, "VLESS ${parsed.security.uppercase()}", parsed.host, parsed.port, raw))
                    store.save(updated)
                    dialog.dismiss()
                    refreshProfiles()
                    Toast.makeText(this, "Đã kiểm tra và lưu profile mã hóa", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    input.error = e.message ?: "URI không hợp lệ"
                }
            }
        }
        dialog.show()
    }

    private fun scanQrCode() {
        IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt("Đưa mã QR VLESS/V2Ray vào khung quét")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }.initiateScan()
    }

    private fun saveQrProfile(raw: String) {
        try {
            val parsed = VlessParser.parse(raw)
            val updated = store.load().toMutableList()
            require(updated.none { it.payload == raw }) { "Profile này đã có trong danh sách" }
            updated.add(StoredProfile(parsed.id, parsed.name, "VLESS ${parsed.security.uppercase()}", parsed.host, parsed.port, raw))
            store.save(updated)
            refreshProfiles()
            Toast.makeText(this, "Đã quét và lưu cấu hình QR mã hóa", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "QR không phải cấu hình VLESS hợp lệ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showExcludedAppsDialog() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        val selected = getExcludedApps().toMutableSet()
        val labels = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val checked = apps.map { it.packageName in selected }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Ứng dụng không dùng VPN")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selected += apps[which].packageName else selected -= apps[which].packageName
            }
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu") { _, _ ->
                getSharedPreferences("tunnelgo_connection", MODE_PRIVATE).edit()
                    .putStringSet(excludedAppsKey, selected).apply()
                Toast.makeText(this, "Đã lưu ${selected.size} ứng dụng không dùng VPN", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun getExcludedApps(): Set<String> = getSharedPreferences("tunnelgo_connection", MODE_PRIVATE)
        .getStringSet(excludedAppsKey, emptySet())?.toSet() ?: emptySet()

    @Deprecated("Legacy callback kept for compatibility with the simple MVP file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val qrResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (qrResult != null) {
            if (!qrResult.contents.isNullOrBlank()) saveQrProfile(qrResult.contents.trim())
            return
        }
        if (requestCode == vpnRequestCode) {
            if (resultCode == RESULT_OK) pendingProfileUri?.let(::startVpnService)
            else Toast.makeText(this, "Bạn đã từ chối quyền VPN; chưa tạo tunnel.", Toast.LENGTH_LONG).show()
            pendingProfileUri = null
            return
        }
        if (requestCode == fileRequestCode) {
            Toast.makeText(this, "Nhập file đã bị tắt; hãy dùng URI hoặc quét QR VLESS.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(profile: StoredProfile) {
        AlertDialog.Builder(this)
            .setTitle("Xóa profile?")
            .setMessage("Profile ‘${profile.name}’ sẽ bị xóa khỏi kho mã hóa trên thiết bị.")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa") { _, _ ->
                store.save(store.load().filterNot { it.id == profile.id })
                refreshProfiles()
            }
            .show()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(panel)
            cornerRadius = dp(16).toFloat()
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun actionButton(value: String, color: Int) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 15f
        setTextColor(if (color == mint) Color.rgb(2, 28, 21) else this@MainActivity.text)
        backgroundTintList = ColorStateList.valueOf(color)
        minHeight = dp(52)
    }

    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { if (top != 0) topMargin = dp(top); if (bottom != 0) bottomMargin = dp(bottom) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatRate(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return "0 B/s"
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSecond.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) "${bytesPerSecond} ${units[index]}" else "%.1f %s".format(value, units[index])
    }
}
