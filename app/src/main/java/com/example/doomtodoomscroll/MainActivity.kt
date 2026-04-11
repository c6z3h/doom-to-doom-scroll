package com.example.doomtodoomscroll
import androidx.appcompat.app.AppCompatActivity
import android.net.VpnService
class MainActivity : AppCompatActivity() {

    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (!hasUsageStatsPermission()) {
            // Auto-direct to Usage Access settings
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Please enable Usage Access for Parental Control", Toast.LENGTH_LONG).show()
        }
        else if (!prepareVpn()) {
            // This triggers the standard Android VPN "Allow" dialog
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, 0)
            } else {
                // If intent is null, VPN is already prepared!
                startThrottlingService()
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun prepareVpn(): Boolean {
        return VpnService.prepare(this) == null
    }

    private fun startThrottlingService() {
        val intent = Intent(this, ThrottlingVpnService::class.java)
        startService(intent)
    }
}