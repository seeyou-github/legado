package io.legado.app.ui.book.read.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment

/**
 * 朗读设置（阅读界面弹框与 设置-其它 共用）
 */
class ReadAloudPreferenceFragment : PreferenceFragment(),
    SpeakEngineDialog.CallBack,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val speakEngineSummary: String
        get() = ReadAloud.getEngineName(requireContext())

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_aloud)
        upSpeakEngineSummary()
        findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
            it.isEnabled = AppConfig.ignoreAudioFocus
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
        super.onPause()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.ttsEngine -> showDialogFragment(SpeakEngineDialog())
            "sysTtsConfig" -> IntentHelp.openTTSSetting()
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?
    ) {
        when (key) {
            PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                if (BaseReadAloudService.isRun) {
                    postEvent(EventBus.MEDIA_BUTTON, false)
                }
            }

            PreferKey.ignoreAudioFocus -> {
                findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                    it.isEnabled = AppConfig.ignoreAudioFocus
                }
            }
        }
    }

    private fun upPreferenceSummary(preference: Preference?, value: String) {
        when (preference) {
            is ListPreference -> {
                val index = preference.findIndexOfValue(value)
                preference.summary = if (index >= 0) preference.entries[index] else null
            }

            else -> {
                preference?.summary = value
            }
        }
    }

    override fun upSpeakEngineSummary() {
        upPreferenceSummary(
            findPreference(PreferKey.ttsEngine),
            speakEngineSummary
        )
    }
}
