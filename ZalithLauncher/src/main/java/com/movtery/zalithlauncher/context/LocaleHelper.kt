package com.movtery.zalithlauncher.context

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.Settings
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import java.util.Locale

class LocaleHelper(context: Context) : ContextWrapper(context) {
    companion object {
        private const val SYSTEM = "system"

        fun setLocale(context: Context): ContextWrapper {
            PathManager.initContextConstants(context)
            Settings.refreshSettings()

            LauncherPreferences.loadPreferences()

            val code = AllSettings.launcherLanguage.getValue()
            if (code.isBlank() || code == SYSTEM) return LocaleHelper(context)

            val locale = Locale.forLanguageTag(code)
            Locale.setDefault(locale)
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(locale)
            configuration.setLayoutDirection(locale)
            return LocaleHelper(context.createConfigurationContext(configuration))
        }
    }
}
