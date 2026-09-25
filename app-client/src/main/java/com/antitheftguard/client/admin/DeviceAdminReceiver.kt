package com.antitheftguard.client.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "기기 관리자 권한이 활성화되었습니다.", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "보안을 위해 기기 관리자 권한 해제를 권장하지 않습니다."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "기기 관리자 권한이 해제되었습니다.", Toast.LENGTH_SHORT).show()
    }
}
