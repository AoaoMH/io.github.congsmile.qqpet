package com.copilot.qqpet.ui

import android.content.Context
import android.content.SharedPreferences

object PreferencesHelper {
    private const val PREF_NAME = "qqpet_copilot_config"
    const val KEY_STUDY = "key_study"
    const val KEY_WORK = "key_work"
   const val KEY_CARE = "key_care"
   const val KEY_ADVENTURE = "key_adventure"
   const val KEY_SETTLE = "key_settle"
    const val KEY_STUDY_MODE = "key_study_mode"
    const val KEY_WORK_MODE = "key_work_mode"

   fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
}
