package moe.chenxy.oppoheadset

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 主 Activity - 显示模块状态
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        }

        val statusView = TextView(this).apply {
            text = if (isModuleActive()) {
                getString(R.string.status_active)
            } else {
                getString(R.string.status_inactive)
            }
            textSize = 16f
            setPadding(0, 16, 0, 0)
        }

        val descView = TextView(this).apply {
            text = getString(R.string.module_desc)
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }

        layout.addView(titleView)
        layout.addView(statusView)
        layout.addView(descView)

        setContentView(layout)
    }

    private fun isModuleActive(): Boolean {
        // 如果模块被 Xposed 加载，这个方法会被 Hook 返回 true
        return false
    }
}
