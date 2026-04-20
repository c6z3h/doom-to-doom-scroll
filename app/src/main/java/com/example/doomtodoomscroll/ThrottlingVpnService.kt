package com.example.doomtodoomscroll

import android.content.Context
import android.net.VpnService
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import android.app.usage.UsageStatsManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ThrottlingVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    // volatile ensures the VPN thread sees the most up-to-date value from the monitor thread
    @Volatile private var currentSqueezeLevel = 0
    @Volatile private var isThrottling = false
    @Volatile private var lastThrottledApp: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (vpnInterface !== null) {
            return START_STICKY
        }
        // This is where we will eventually start the throttling logic
        establishVpn()
        // Use a ScheduledExecutor instead of a Handler.
        // This runs on a background thread automatically!
        scheduler.scheduleWithFixedDelay({
            try {
                checkCurrentUsage()
            } catch (e: Exception) {
                Log.e("SQUEEZE_ERR", "Error in monitor: ${e.message}")
            }
        }, 0, 5, TimeUnit.SECONDS)
        return START_STICKY
    }

    private fun establishVpn(packageName: String? = null) {
        if (packageName == lastThrottledApp && vpnInterface !== null) return

        try {
            // Close the old interface if it exists
            vpnInterface?.close()

            val builder = Builder()
                .setSession("SqueezeVPN")
                .addAddress("10.0.0.1", 24)
                .addDnsServer("8.8.8.8")
            // Remove the global 0.0.0.0 route so we don't break the whole phone
            // Instead, only capture the specific app

            if (packageName != null) {
                builder.addAllowedApplication(packageName)
                // If we are targeting one app, we can use a broad route for just that app
                builder.addRoute("0.0.0.0", 0)
                Log.d("SQUEEZE", "VPN now targeting: $packageName")
            }

            vpnInterface = builder.establish()
            lastThrottledApp = packageName

            // Note: The VPN thread usually needs to be restarted
            // because the FileDescriptor changed.
            startVpnThread()

        } catch (e: Exception) {
            Log.e("SQUEEZE", "Could not establish VPN for $packageName: ${e.message}")
        }
    }
    private fun checkCurrentUsage() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 * 60 * 60 * 24 // Look at last 24 hours

        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime) ?: emptyList()

        // Find the app currently in the foreground
        val sortedStats = stats.sortedByDescending { it.lastTimeUsed }
        if (sortedStats.isNotEmpty()) {
            val topApp = sortedStats[0]
            val packageName = topApp.packageName
            val totalTimeMs = topApp.totalTimeInForeground

            val sharedPrefs = getSharedPreferences("AppLimits", Context.MODE_PRIVATE)
            val limitHours = sharedPrefs.getInt(packageName, 0)

            if (limitHours > 0) {
                // TODO temp testing with minutes instead of hours
                // 1 minute = 60,000 milliseconds
                val limitMs = limitHours * 60000L
//                val limitMs = limitHours * 3600000L
                val progress = (totalTimeMs.toDouble() / limitMs.toDouble())

                val newLevel = when {
                    progress >= 1.0  -> 4 // Blocked
                    progress >= 0.9  -> 3 // Super Lag
                    progress >= 0.75 -> 2 // Very Laggy
                    progress >= 0.5  -> 1 // Slightly annoying
                    else             -> 0           // Full speed
                }

                if (newLevel > 0) {
                    applySqueeze(newLevel)
                    establishVpn(packageName)
                } else {
                    stopSqueeze()
                    establishVpn(null)
                }

                Log.d("SQUEEZE", "$packageName is at ${(progress * 100).toInt()}%")
            } else {
                // No limit set for this app, ensure VPN is clear
                if (lastThrottledApp != null) {
                    stopSqueeze()
                    establishVpn(null)
                }
            }
        }
    }

    private fun startVpnThread() {
        Log.d("SQUEEZE", "startVpnThread")

        Thread {
            val vpnInterfaceRef = vpnInterface ?: return@Thread
            val input = FileInputStream(vpnInterfaceRef.fileDescriptor)
            val output = FileOutputStream(vpnInterfaceRef.fileDescriptor)
            val buffer = ByteBuffer.allocate(32768)

            try {
                Log.d("SQUEEZE", "currentThread not interrupted, ${Thread.currentThread().isInterrupted}")
                while (!Thread.currentThread().isInterrupted) {
                    val length = input.read(buffer.array())
                    if (length > 0) {
                        // --- THE THROTTLING LOGIC ---
                        Log.d("SQUEEZE", "Captured packet: $length bytes. Throttling: $isThrottling")
                        if (isThrottling) {
                            val delay = when (currentSqueezeLevel) {
                                1 -> 5L   // Micro-stutter
                                2 -> 20L  // Heavy lag
                                3 -> 100L // Buffering hell
                                4 -> 1000L // Functional block
                                else -> 0L
                            }
                            Log.d("SQUEEZE", "delay, ${delay}")
                            if (delay > 0) Thread.sleep(delay)
                        }

                        // Write the packet back out to the "real" internet
                        // In a simple pass-through VPN, this is where we'd send it to a socket
                        // But for a local "throttle," we are just keeping the buffer moving
                        output.write(buffer.array(), 0, length)
                        buffer.clear()
                    }
                }
            } catch (e: Exception) {
                Log.e("VPN_PUMP", "Pump error: ${e.message}")
            }
        }.start()
    }
    private fun applySqueeze(level: Int) {
        isThrottling = true
        when (level) {
            1 -> {
                Log.d("SQUEEZE", "Level 1: 50% reached. Adding jitter.")
                currentSqueezeLevel = 1
            }
            2 -> {
                Log.d("SQUEEZE", "Level 2: 75% reached. Throttling bandwidth.")
                currentSqueezeLevel = 2
            }
            3 -> {
                Log.d("SQUEEZE", "Level 3: 90% reached. Dial-up mode initiated.")
                currentSqueezeLevel = 3
            }
            4 -> {
                Log.d("SQUEEZE", "Level 4: 100% reached. HARD BLOCK.")
                currentSqueezeLevel = 4
            }
        }
        // In Phase 3, we will insert code here to actually sleep the
        // VPN thread for X milliseconds based on the level.
    }

    private fun stopSqueeze() {
        isThrottling = false
        currentSqueezeLevel = 0
    }

    override fun onDestroy() {
        scheduler.shutdownNow()
        vpnInterface?.close()
        super.onDestroy()
    }

    override fun onRevoke() {
        // Called if the user manually stops the VPN in settings
        stopSqueeze()
        vpnInterface?.close()
        vpnInterface = null
        super.onRevoke()
    }
}