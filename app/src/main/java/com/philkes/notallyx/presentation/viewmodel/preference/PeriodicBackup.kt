package com.philkes.notallyx.presentation.viewmodel.preference

import android.content.SharedPreferences
import android.content.SharedPreferences.Editor

class PeriodicBackupsPreference(sharedPreferences: SharedPreferences) :
    BasePreference<PeriodicBackup>(sharedPreferences, PeriodicBackup()) {

    override fun getValue(sharedPreferences: SharedPreferences): PeriodicBackup {
        val periodInDays =
            sharedPreferences.getInt(BACKUP_PERIOD_DAYS_KEY, defaultValue.periodInDays)
        val maxBackups = sharedPreferences.getInt(BACKUP_MAX_KEY, defaultValue.periodInDays)
        return PeriodicBackup(periodInDays, maxBackups)
    }

    override fun Editor.put(value: PeriodicBackup) {
        putInt(BACKUP_PERIOD_DAYS_KEY, value.periodInDays)
        putInt(BACKUP_MAX_KEY, value.maxBackups)
    }

    companion object {
        const val BACKUP_PERIOD_DAYS_KEY = "autoBackupPeriodDays"
        const val BACKUP_MAX_KEY = "maxBackups"
    }
}

data class PeriodicBackup(val periodInDays: Int = 0, val maxBackups: Int = 3) {
    companion object {

        const val BACKUP_PERIOD_DAYS_MIN = 1
        const val BACKUP_PERIOD_DAYS_MAX = 31

        const val BACKUP_MAX_MIN = 1
        const val BACKUP_MAX_MAX = 10
    }
}
