package io.legado.app.ui.main.my

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.AutoTaskScheduler
import io.legado.app.service.McpService
import io.legado.app.service.WebService
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.autoTask.AutoTaskActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity

/**
 * 我的-其它（定时任务、Web/MCP服务、书签、阅读记录、文件管理、关于）
 */
class MyOtherConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        putPrefBoolean(PreferKey.webService, WebService.isRun)
        putPrefBoolean(PreferKey.mcpService, McpService.isRun)
        addPreferencesFromResource(R.xml.pref_my_other)
        activity?.setTitle(R.string.other)
        findPreference<SwitchPreference>(PreferKey.webService)?.let {
            it.isChecked = WebService.isRun
            it.summary = if (WebService.isRun) {
                WebService.hostAddress
            } else {
                getString(R.string.web_service_desc)
            }
            it.onLongClick {
                if (!WebService.isRun) {
                    return@onLongClick false
                }
                context?.selector(arrayListOf("复制地址", "浏览器打开")) { _, i ->
                    when (i) {
                        0 -> context?.sendToClip(it.summary.toString())
                        1 -> context?.openUrl(it.summary.toString())
                    }
                }
                true
            }
        }
        findPreference<SwitchPreference>(PreferKey.mcpService)?.let {
            it.isChecked = McpService.isRun
            it.summary = if (McpService.isRun) {
                McpService.hostAddress
            } else {
                getString(R.string.mcp_service_desc)
            }
            it.onLongClick {
                if (!McpService.isRun) return@onLongClick false
                context?.sendToClip(it.summary.toString())
                true
            }
        }
        observeEventSticky<String>(EventBus.WEB_SERVICE) {
            findPreference<SwitchPreference>(PreferKey.webService)?.let {
                it.isChecked = WebService.isRun
                it.summary = if (WebService.isRun) {
                    WebService.hostAddress
                } else {
                    getString(R.string.web_service_desc)
                }
            }
        }
        observeEventSticky<String>(EventBus.MCP_SERVICE) {
            findPreference<SwitchPreference>(PreferKey.mcpService)?.let {
                it.isChecked = McpService.isRun
                it.summary = if (McpService.isRun) {
                    McpService.hostAddress
                } else {
                    getString(R.string.mcp_service_desc)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?
    ) {
        when (key) {
            PreferKey.webService -> {
                if (requireContext().getPrefBoolean("webService")) {
                    WebService.start(requireContext())
                } else {
                    WebService.stop(requireContext())
                }
            }

            PreferKey.mcpService -> {
                if (requireContext().getPrefBoolean(PreferKey.mcpService)) {
                    McpService.start(requireContext())
                } else {
                    McpService.stop(requireContext())
                }
            }

            PreferKey.autoTaskService -> {
                val appContext = requireContext().applicationContext
                if (appContext.getPrefBoolean(PreferKey.autoTaskService)) {
                    Coroutine.async { AutoTaskScheduler.refresh(appContext) }
                } else {
                    AutoTaskScheduler.cancelAll(appContext)
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "autoTaskManage" -> startActivity<AutoTaskActivity>()
            "bookmark" -> startActivity<AllBookmarkActivity>()
            "readRecord" -> startActivity<ReadRecordActivity>()
            "fileManage" -> startActivity<FileManageActivity>()
            "about" -> startActivity<AboutActivity>()
        }
        return super.onPreferenceTreeClick(preference)
    }

}
