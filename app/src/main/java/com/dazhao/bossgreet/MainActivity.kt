package com.dazhao.bossgreet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.dazhao.bossgreet.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: GreetStore
    private val onLog: (String) -> Unit = { text ->
        binding.logText.text = text.ifEmpty { getString(R.string.log_empty) }
        binding.statusText.text = statusLabel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = GreetStore(this)
        val config = store.load()
        binding.maxCountInput.setText(config.maxCount.toString())
        binding.intervalInput.setText(config.intervalMs.toString())
        binding.includeInput.setText(config.includeRaw)
        binding.excludeInput.setText(config.excludeRaw)
        binding.openA11yButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.overlayButton.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
        binding.startButton.setOnClickListener { startGreet() }
        binding.scrollListButton.setOnClickListener { startScrollProbe() }
        binding.stopButton.setOnClickListener { GreetRuntime.requestStop("已手动停止") }
        binding.dumpButton.setOnClickListener {
            if (!BossGreetService.connected) {
                GreetRuntime.log("无障碍服务未连接")
            } else {
                GreetRuntime.dumpRequested = true
                GreetRuntime.log("等待读取当前页面")
            }
        }
        binding.clearButton.setOnClickListener {
            store.clearSeen()
            GreetRuntime.log("已清空今日去重记录")
        }
    }

    override fun onStart() {
        super.onStart()
        GreetRuntime.addListener(onLog)
        onLog(GreetRuntime.snapshot())
    }

    override fun onResume() {
        super.onResume()
        binding.statusText.text = statusLabel()
    }

    override fun onStop() {
        GreetRuntime.removeListener(onLog)
        super.onStop()
    }

    private fun startGreet() {
        if (!BossGreetService.isEnabled(this)) {
            GreetRuntime.log("请先开启无障碍服务")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val config = readConfig()
        store.save(config)
        GreetRuntime.start(config, store.seen)
        openBoss()
    }

    private fun startScrollProbe() {
        if (!BossGreetService.isEnabled(this)) {
            GreetRuntime.log("请先开启无障碍服务")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        GreetRuntime.startScrollProbe()
        openBoss()
    }

    private fun openBoss() {
        val launch = packageManager.getLaunchIntentForPackage(BossPages.PACKAGE_NAME)
        if (launch == null) {
            GreetRuntime.log("未安装 Boss直聘")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    private fun readConfig(): GreetConfig = GreetConfig.sanitize(
        maxCount = binding.maxCountInput.text?.toString()?.toIntOrNull() ?: GreetConfig.DEFAULT.maxCount,
        intervalMs = binding.intervalInput.text?.toString()?.toIntOrNull() ?: GreetConfig.DEFAULT.intervalMs,
        includeRaw = binding.includeInput.text?.toString().orEmpty(),
        excludeRaw = binding.excludeInput.text?.toString().orEmpty(),
    )

    private fun statusLabel(): String = when {
        !BossGreetService.isEnabled(this) -> getString(R.string.status_a11y_off)
        !BossGreetService.connected -> getString(R.string.status_waiting)
        GreetRuntime.running -> getString(R.string.status_running)
        GreetRuntime.scrollProbe -> getString(R.string.status_scroll_probe)
        else -> getString(R.string.status_ready)
    }
}
