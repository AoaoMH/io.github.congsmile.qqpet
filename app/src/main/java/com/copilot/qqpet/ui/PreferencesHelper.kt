package com.copilot.qqpet.ui

import android.content.Context
import android.content.SharedPreferences

object PreferencesHelper {
    private const val PREF_NAME = "qqpet_copilot_config"
   const val KEY_STUDY = "key_study"
   const val KEY_WORK = "key_work"
   const val KEY_CARE = "key_care"
   const val KEY_CARE_ENERGY_THRESHOLD = "key_care_energy_threshold"
   const val KEY_CARE_CLEAN_THRESHOLD = "key_care_clean_threshold"
   const val KEY_ADVENTURE = "key_adventure"
   const val KEY_SETTLE = "key_settle"
    const val KEY_LIKE_BACK = "key_like_back"
    const val KEY_CLAIM_COINBAG = "key_claim_coinbag"
    const val KEY_FATIGUE_TO_ADVENTURE = "key_fatigue_to_adventure"
    const val KEY_STUDY_MODE = "key_study_mode"
    const val KEY_WORK_MODE = "key_work_mode"
    const val KEY_SCHOOL_STAGE = "key_custom_school_stage"
    const val KEY_COURSE_SUBJECT = "key_custom_course_subject"
    const val KEY_COURSE_DURATION = "key_custom_course_duration"
    const val KEY_WORK_TYPE = "key_custom_work_type"
    const val KEY_WORK_DURATION = "key_custom_work_duration"

   fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
}
