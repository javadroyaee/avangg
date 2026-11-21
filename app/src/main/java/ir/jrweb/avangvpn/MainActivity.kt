package ir.jrweb.avangvpn

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var btnAddMenu: ImageButton
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSubTitle: TextView
    private lateinit var tvSubDays: TextView
    private lateinit var tvSubTraffic: TextView
    private lateinit var tvSubPercent: TextView
    private lateinit var timelineProgress: ProgressBar
    private lateinit var rvConfigs: RecyclerView

    private val configList = mutableListOf<VpnConfig>()
    private lateinit var adapter: ConfigAdapter
    private var currentSub = SubscriptionInfo()
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selected = configList.firstOrNull { it.isSelected } ?: configList.firstOrNull()
            selected?.let { launchVpnService(it) }
        } else {
            tvStatus.text = "دسترسی VPN توسط کاربر لغو شد"
        }
    }

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scanResult = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
        if (scanResult != null && !scanResult.contents.isNullOrBlank()) {
            handleImportedContent(scanResult.contents.trim())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupRecyclerView()
        loadSavedData()
        setupListeners()
    }

    private fun initViews() {
        btnAddMenu = findViewById(R.id.btnAddMenu)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvSubTitle = findViewById(R.id.tvSubTitle)
        tvSubDays = findViewById(R.id.tvSubDays)
        tvSubTraffic = findViewById(R.id.tvSubTraffic)
        tvSubPercent = findViewById(R.id.tvSubPercent)
        timelineProgress = findViewById(R.id.timelineProgress)
        rvConfigs = findViewById(R.id.rvConfigs)
    }

    private fun setupRecyclerView() {
        adapter = ConfigAdapter(configList) { selectedConfig ->
            configList.forEach { it.isSelected = (it.id == selectedConfig.id) }
            adapter.notifyDataSetChanged()
            prepareAndStartVpn(selectedConfig)
        }
        rvConfigs.layoutManager = LinearLayoutManager(this)
        rvConfigs.adapter = adapter
    }

    private fun setupListeners() {
        btnAddMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "📋 کپی از کلیپ‌بورد")
            popup.menu.add(0, 2, 1, "📷 اسکن کیو‌آر کد (QR)")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> importFromClipboard()
                    2 -> startQrScanner()
                }
                true
            }
            popup.show()
        }

        btnConnect.setOnClickListener {
            if (isConnected) {
                disconnectVpn()
            } else {
                smartConnectFlow()
            }
        }
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "کلیپ‌بورد خالی است!", Toast.LENGTH_SHORT).show()
            return
        }
        handleImportedContent(text)
    }

    private fun startQrScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("کد QR اشتراک یا کانفیگ را داخل مربع قرار دهید")
        integrator.setBeepEnabled(true)
        integrator.setOrientationLocked(true)
        qrLauncher.launch(integrator.createScanIntent())
    }

    private fun handleImportedContent(rawText: String) {
        if (rawText.startsWith("http://") || rawText.startsWith("https://")) {
            currentSub.url = rawText
            saveSubUrl(rawText)
            scope.launch {
                tvStatus.text = "در حال دریافت اطلاعات گروه اشتراک..."
                updateSubscription(rawText)
                pingAllConfigs()
            }
        } else {
            val parsed = parseMultiLineConfigs(rawText)
            if (parsed.isNotEmpty()) {
                configList.clear()
                configList.addAll(parsed)
                saveConfigsLocal()
                adapter.notifyDataSetChanged()
                scope.launch { pingAllConfigs() }
            } else {
                Toast.makeText(this, "فرمت لینک معتبر نیست", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun smartConnectFlow() {
        scope.launch {
            btnConnect.isEnabled = false
            if (currentSub.url.isNotEmpty()) {
                tvStatus.text = "۱/۳ در حال به‌روزرسانی لینک‌های اشتراک..."
                updateSubscription(currentSub.url)
            }
            if (configList.isEmpty()) {
                tvStatus.text = "هیچ کانفیگی یافت نشد. از منوی + اضافه کنید."
                btnConnect.isEnabled = true
                return@launch
            }
            tvStatus.text = "۲/۳ در حال گرفتن پینگ سرورها..."
            pingAllConfigs()

            val bestConfig = configList.filter { it.pingMs in 1..4999 }.minByOrNull { it.pingMs }
            if (bestConfig != null) {
                configList.forEach { it.isSelected = (it.id == bestConfig.id) }
                adapter.notifyDataSetChanged()
                tvStatus.text = "۳/۳ در حال اتصال به سریع‌ترین سرور (${bestConfig.pingMs}ms)..."
                prepareAndStartVpn(bestConfig)
            } else {
                tvStatus.text = "پاسخی از سرورها دریافت نشد!"
            }
            btnConnect.isEnabled = true
        }
    }

    private suspend fun updateSubscription(subUrl: String) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(subUrl)
                .header("User-Agent", "AvangVPN/1.3")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use

                val userInfoHeader = response.header("Subscription-Userinfo") ?: ""
                parseSubscriptionUserInfo(userInfoHeader)

                val bodyStr = response.body?.string()?.trim() ?: ""
                val decoded = try {
                    String(Base64.decode(bodyStr, Base64.DEFAULT))
                } catch (_: Exception) {
                    bodyStr
                }

                val parsedConfigs = parseMultiLineConfigs(decoded)
                withContext(Dispatchers.Main) {
                    if (parsedConfigs.isNotEmpty()) {
                        configList.clear()
                        configList.addAll(parsedConfigs)
                        saveConfigsLocal()
                        adapter.notifyDataSetChanged()
                    }
                    updateTimelineUI()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                tvStatus.text = "خطا در آپدیت اشتراک: ${e.localizedMessage}"
            }
        }
    }

    private fun parseSubscriptionUserInfo(header: String) {
        val parts = header.split(";")
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        for (p in parts) {
            val kv = p.trim().split("=")
            if (kv.size == 2) {
                when (kv[0].lowercase()) {
                    "upload" -> upload = kv[1].toLongOrNull() ?: 0L
                    "download" -> download = kv[1].toLongOrNull() ?: 0L
                    "total" -> total = kv[1].toLongOrNull() ?: 0L
                    "expire" -> expire = kv[1].toLongOrNull() ?: 0L
                }
            }
        }
        currentSub.usedBytes = upload + download
        currentSub.totalBytes = total
        currentSub.expireTimestamp = expire
    }

    private fun updateTimelineUI() {
        val totalGb = currentSub.totalBytes / (1024.0 * 1024.0 * 1024.0)
        val usedGb = currentSub.usedBytes / (1024.0 * 1024.0 * 1024.0)
        val remainGb = (totalGb - usedGb).coerceAtLeast(0.0)

        val percent = if (totalGb > 0) ((remainGb / totalGb) * 100).toInt().coerceIn(0, 100) else 100
        timelineProgress.progress = percent

        tvSubTraffic.text = if (totalGb > 0) {
            String.format("حجم باقی‌مانده: %.1f از %.1f گیگ", remainGb, totalGb)
        } else {
            "حجم باقی‌مانده: نامحدود"
        }

        if (currentSub.expireTimestamp > 0) {
            val nowSec = System.currentTimeMillis() / 1000L
            val remainDays = ((currentSub.expireTimestamp - nowSec) / 86400L).coerceAtLeast(0L)
            tvSubDays.text = "مدت باقی‌مانده: $remainDays روز"
        } else {
            tvSubDays.text = "مدت باقی‌مانده: نامحدود"
        }
        tvSubPercent.text = "$percent% باقی‌مانده"
    }

    private fun parseMultiLineConfigs(raw: String): List<VpnConfig> {
        val list = mutableListOf<VpnConfig>()
        if (raw.contains("[Interface]") && raw.contains("[Peer]")) {
            val endpointLine = raw.lines().firstOrNull { it.trim().startsWith("Endpoint", true) }
            val ep = endpointLine?.substringAfter("=")?.trim() ?: "1.1.1.1:51820"
            val host = ep.substringBefore(":")
            val port = ep.substringAfter(":", "51820").toIntOrNull() ?: 51820
            list.add(VpnConfig(UUID.randomUUID().toString(), "WireGuard Tunnel", "WIREGUARD", host, port, raw))
            return list
        }

        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            parseSingleUri(trimmed)?.let { list.add(it) }
        }
        return list
    }

    private fun parseSingleUri(uriStr: String): VpnConfig? {
        return try {
            when {
                uriStr.startsWith("vless://") || uriStr.startsWith("trojan://") -> {
                    val proto = uriStr.substringBefore("://").uppercase()
                    val uri = URI(uriStr)
                    val name = if (!uri.fragment.isNullOrEmpty()) URLDecoder.decode(uri.fragment, "UTF-8") else "$proto Server"
                    VpnConfig(UUID.randomUUID().toString(), name, proto, uri.host ?: "", if (uri.port > 0) uri.port else 443, uriStr)
                }
                uriStr.startsWith("vmess://") -> {
                    val b64 = uriStr.removePrefix("vmess://")
                    val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
                    val name = json.optString("ps", "VMess Server")
                    val host = json.optString("add", "")
                    val port = json.optInt("port", 443)
                    VpnConfig(UUID.randomUUID().toString(), name, "VMESS", host, port, uriStr)
                }
                uriStr.startsWith("wireguard://") || uriStr.startsWith("wg://") -> {
                    val normalized = uriStr.replace("wg://", "wireguard://")
                    val uri = URI(normalized)
                    val name = if (!uri.fragment.isNullOrEmpty()) URLDecoder.decode(uri.fragment, "UTF-8") else "WireGuard Server"
                    VpnConfig(UUID.randomUUID().toString(), name, "WIREGUARD", uri.host ?: "", if (uri.port > 0) uri.port else 51820, uriStr)
                }
                uriStr.startsWith("ss://") -> {
                    val uri = URI(uriStr)
                    val name = if (!uri.fragment.isNullOrEmpty()) URLDecoder.decode(uri.fragment, "UTF-8") else "Shadowsocks"
                    VpnConfig(UUID.randomUUID().toString(), name, "SS", uri.host ?: "", if (uri.port > 0) uri.port else 8388, uriStr)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun pingAllConfigs() = withContext(Dispatchers.IO) {
        val jobs = configList.map { cfg ->
            async {
                cfg.pingMs = tcpPing(cfg.host, cfg.port)
            }
        }
        jobs.awaitAll()
        configList.sortBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
        withContext(Dispatchers.Main) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun tcpPing(host: String, port: Int): Int {
        if (host.isBlank() || port <= 0) return -1
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2500)
            }
            (System.currentTimeMillis() - start).toInt()
        } catch (_: Exception) {
            -1
        }
    }

    private fun prepareAndStartVpn(config: VpnConfig) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            launchVpnService(config)
        }
    }

    private fun launchVpnService(config: VpnConfig) {
        try {
            val jsonConfig = ConfigConverter.buildXrayConfig(config)
            val intent = Intent(this, AvangVpnService::class.java).apply {
                putExtra("CONFIG_NAME", config.name)
                putExtra("JSON_CONFIG", jsonConfig)
            }
            ContextCompat.startForegroundService(this, intent)
            isConnected = true
            btnConnect.text = "قطع اتصال (${config.name})"
            tvStatus.text = "متصل به ${config.name} | پینگ: ${config.pingMs} ms"
        } catch (e: Exception) {
            tvStatus.text = "خطا در اجرای هسته: ${e.localizedMessage}"
        }
    }

    private fun disconnectVpn() {
        val intent = Intent(this, AvangVpnService::class.java).apply {
            action = "STOP_VPN"
        }
        startService(intent)
        isConnected = false
        btnConnect.text = "اتصال هوشمند (کمترین پینگ)"
        tvStatus.text = "اتصال قطع شد"
    }

    private fun saveSubUrl(url: String) {
        getSharedPreferences("avang_prefs", Context.MODE_PRIVATE).edit().putString("sub_url", url).apply()
    }

    private fun saveConfigsLocal() {
        val joined = configList.joinToString("\n") { it.rawUri }
        getSharedPreferences("avang_prefs", Context.MODE_PRIVATE).edit().putString("saved_configs", joined).apply()
    }

    private fun loadSavedData() {
        val prefs = getSharedPreferences("avang_prefs", Context.MODE_PRIVATE)
        currentSub.url = prefs.getString("sub_url", "") ?: ""
        val saved = prefs.getString("saved_configs", "") ?: ""
        if (saved.isNotEmpty()) {
            configList.addAll(parseMultiLineConfigs(saved))
            adapter.notifyDataSetChanged()
        }
    }

    inner class ConfigAdapter(
        private val items: List<VpnConfig>,
        private val onClick: (VpnConfig) -> Unit
    ) : RecyclerView.Adapter<ConfigAdapter.Holder>() {

        inner class Holder(val root: LinearLayout, val tvName: TextView, val tvProto: TextView, val tvPing: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(36, 28, 36, 28)
                background = ContextCompat.getDrawable(context, R.drawable.bg_card_pastel)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 18 }
            }

            val tvPing = TextView(parent.context).apply {
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.ping_green))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val infoBox = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvName = TextView(parent.context).apply {
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.pastel_white))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val tvProto = TextView(parent.context).apply {
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.accent_pastel_orange))
            }

            infoBox.addView(tvName)
            infoBox.addView(tvProto)
            row.addView(tvPing)
            row.addView(infoBox)
            return Holder(row, tvName, tvProto, tvPing)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvProto.text = "${item.protocol} • ${item.host}"
            holder.tvPing.text = when {
                item.pingMs > 0 -> "${item.pingMs} ms"
                item.pingMs == -1 -> "تست نشده"
                else -> "تایم‌اوت"
            }
            holder.tvPing.setTextColor(
                if (item.pingMs > 0) ContextCompat.getColor(this@MainActivity, R.color.ping_green)
                else Color.parseColor("#F87171")
            )
            holder.root.alpha = if (item.isSelected) 1.0f else 0.88f
            holder.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}