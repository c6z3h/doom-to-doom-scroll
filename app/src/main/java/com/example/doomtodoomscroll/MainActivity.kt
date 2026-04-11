package com.example.doomtodoomscroll
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("TAG_DEBUG", "onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // This "inflates" your XML
        Log.d("TAG_DEBUG", "setContentView ok")
        val recyclerView = findViewById<RecyclerView>(R.id.appRecyclerView)
        val btnSave = findViewById<Button>(R.id.btnSave)
        Log.d("TAG_DEBUG", "recyclerView ok")
        // 1. Get the list of apps
        val apps = getInstalledApps()

        // 2. Setup the list
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = AppAdapter(apps)

        // 3. Setup the Save button
        btnSave.setOnClickListener {
            saveLimits(apps)
            checkAndRequestPermissions() // Now we start the VPN/Usage process
        }
    }

    private fun saveLimits(apps: List<AppLimitModel>) {
        // Open a storage file named "AppLimits"
        val sharedPrefs = getSharedPreferences("AppLimits", Context.MODE_PRIVATE)
        val editor = sharedPrefs.edit()

        var count = 0
        for (app in apps) {
            if (app.hourLimit > 0) {
                // Save the package name (key) and the hours (value)
                editor.putInt(app.packageName, app.hourLimit)
                count++
            }
        }

        editor.apply() // Commit changes to the disk

        Log.d("TAG_DEBUG", "Saved limits for $count apps")
        Toast.makeText(this, "Saved limits for $count apps!", Toast.LENGTH_SHORT).show()
    }

    private fun getInstalledApps(): List<AppLimitModel> {
        val appList = mutableListOf<AppLimitModel>()
        try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfos = pm.queryIntentActivities(intent, 0)
            Log.d("TAG_DEBUG", "Found ${resolveInfos.size} apps via query")

            for (resolveInfo in resolveInfos) {
                // Safety Check: Make sure activityInfo isn't null
                val activityInfo = resolveInfo.activityInfo ?: continue
                val packageName = activityInfo.packageName
                val appName = resolveInfo.loadLabel(pm).toString()
                val icon = resolveInfo.loadIcon(pm)

                appList.add(AppLimitModel(appName, packageName, icon))
            }
        } catch (e: Exception) {
            Log.e("TAG_DEBUG", "Error fetching apps: ${e.message}")
        }

        return appList.sortedBy { it.appName }
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions()
    }

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d("TAG_DEBUG", "VPN Result Code: ${result.resultCode}") // Should be -1 for OK
        if (result.resultCode == RESULT_OK) {
            startThrottlingService()
        } else {
            Toast.makeText(this, "VPN permission is required to throttle data!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        Log.d("TAG_DEBUG", "Checking preparevpnpermissions...")
        if (!hasUsageStatsPermission()) {
            // Auto-direct to Usage Access settings
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Please enable Usage Access for Parental Control", Toast.LENGTH_LONG).show()
        } else {
            val vpnIntent = VpnService.prepare(this)
            Log.d("TAG_DEBUG", "VPN Intent is null: ${vpnIntent == null}")
            if (vpnIntent != null) {
                // This pops up the system "Allow VPN" dialog properly
                Log.d("TAG_DEBUG", "Launching VPN Dialog")
                vpnLauncher.launch(vpnIntent)
            } else {
                Log.d("TAG_DEBUG", "VPN already prepared, starting service")
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

    private fun startThrottlingService() {
        val intent = Intent(this, ThrottlingVpnService::class.java)
        startService(intent)
    }
}