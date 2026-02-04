package moe.chenxy.oppoheadset

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主 Activity - 显示模块状态和日志
 */
class MainActivity : Activity() {

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private val logBuffer = StringBuilder()
    private val maxLogLines = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.Action.OPPO_LOG) {
                val log = intent.getStringExtra("log") ?: return
                val time = intent.getLongExtra("time", System.currentTimeMillis())
                appendLog(log, time)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(32, 48, 32, 32)
        }

        // 标题
        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }

        // 状态
        val statusView = TextView(this).apply {
            text = if (isModuleActive()) {
                "✓ ${getString(R.string.status_active)}"
            } else {
                "✗ ${getString(R.string.status_inactive)}"
            }
            textSize = 16f
            setTextColor(if (isModuleActive()) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
            setPadding(0, 16, 0, 0)
        }

        // 描述
        val descView = TextView(this).apply {
            text = getString(R.string.module_desc)
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 8, 0, 0)
        }

        // Hook 目标说明
        val hookTargetsView = TextView(this).apply {
            text = """
                |Hook 目标:
                |• com.android.systemui - 融合设备中心
                |• com.android.bluetooth - 蓝牙连接状态
                |• com.xiaomi.bluetooth - 小米蓝牙通知
                |• com.heytap.headset - 欢律 App
            """.trimMargin()
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 24, 0, 0)
        }

        // 日志标题栏
        val logHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        val logTitleView = TextView(this).apply {
            text = "实时日志"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val clearButton = Button(this).apply {
            text = "清空"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(24, 8, 24, 8)
            setOnClickListener {
                clearLog()
            }
        }

        logHeaderLayout.addView(logTitleView)
        logHeaderLayout.addView(clearButton)

        // 日志显示区域
        scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = 8
            }
        }

        logTextView = TextView(this).apply {
            text = "等待日志...\n"
            textSize = 11f
            setTextColor(Color.parseColor("#00FF00"))
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
            movementMethod = ScrollingMovementMethod()
        }

        scrollView.addView(logTextView)

        // 操作按钮
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
            gravity = Gravity.CENTER
        }

        val openHeyTapButton = Button(this).apply {
            text = "打开欢律"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2196F3"))
            setPadding(32, 16, 32, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 16
            }
            setOnClickListener {
                openHeyTap()
            }
        }

        val testBroadcastButton = Button(this).apply {
            text = "测试广播"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF9800"))
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                sendTestBroadcast()
            }
        }

        buttonLayout.addView(openHeyTapButton)
        buttonLayout.addView(testBroadcastButton)

        // 添加所有视图
        mainLayout.addView(titleView)
        mainLayout.addView(statusView)
        mainLayout.addView(descView)
        mainLayout.addView(hookTargetsView)
        mainLayout.addView(logHeaderLayout)
        mainLayout.addView(scrollView)
        mainLayout.addView(buttonLayout)

        setContentView(mainLayout)

        // 添加初始日志
        appendLog("[App] MainActivity 已启动", System.currentTimeMillis())
        appendLog("[App] 模块状态: ${if (isModuleActive()) "已激活" else "未激活"}", System.currentTimeMillis())
    }

    override fun onResume() {
        super.onResume()
        // 注册日志接收器
        val filter = IntentFilter(Constants.Action.OPPO_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        appendLog("[App] 日志接收器已注册", System.currentTimeMillis())
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun appendLog(message: String, timestamp: Long) {
        runOnUiThread {
            val timeStr = dateFormat.format(Date(timestamp))
            val logLine = "[$timeStr] $message\n"
            logBuffer.append(logLine)

            // 限制日志行数
            val lines = logBuffer.lines()
            if (lines.size > maxLogLines) {
                logBuffer.clear()
                logBuffer.append(lines.takeLast(maxLogLines).joinToString("\n"))
            }

            logTextView.text = logBuffer.toString()

            // 滚动到底部
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun clearLog() {
        logBuffer.clear()
        logTextView.text = "日志已清空\n"
    }

    private fun openHeyTap() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(Constants.PKG_NAME_HEYTAP)
            if (intent != null) {
                startActivity(intent)
                appendLog("[App] 正在打开欢律 App", System.currentTimeMillis())
            } else {
                appendLog("[App] 未找到欢律 App", System.currentTimeMillis())
            }
        } catch (e: Exception) {
            appendLog("[App] 打开欢律失败: ${e.message}", System.currentTimeMillis())
        }
    }

    private fun sendTestBroadcast() {
        appendLog("[App] 发送测试广播...", System.currentTimeMillis())

        // 发送测试电量广播
        Intent(Constants.Action.OPPO_BATTERY_UPDATE).apply {
            putExtra("left", 85)
            putExtra("right", 90)
            putExtra("box", 100)
            putExtra("mac", "AA:BB:CC:DD:EE:FF")
            putExtra("name", "Test OPPO Headset")
            sendBroadcast(this)
        }

        appendLog("[App] 测试广播已发送 (L=85 R=90 B=100)", System.currentTimeMillis())
    }

    private fun isModuleActive(): Boolean {
        // 如果模块被 Xposed 加载，这个方法会被 Hook 返回 true
        return false
    }
}
