package com.antitheftguard.client.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

object AutoStartHelper {
    /** 특정 제조사의 자동 시작 및 배터리 최적화 화면으로 이동하는 인텐트를 반환합니다. */
    fun getAutoStartIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") -> {
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            }
            manufacturer.contains("oppo") -> {
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
            }
            manufacturer.contains("vivo") -> {
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
            }
            manufacturer.contains("huawei") -> {
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            }
            else -> null
        }
    }
}
