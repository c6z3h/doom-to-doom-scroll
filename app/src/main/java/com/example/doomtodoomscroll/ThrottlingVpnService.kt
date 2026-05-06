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

    private var vpnThread: Thread? = null
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
        if (packageName == lastThrottledApp && vpnInterface !== null) {
            Log.v("ESTABLISH_VPN", "does not reset after 5 seconds")
            return
        }

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
                builder.addRoute("::", 0)
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
        val calendar = java.util.Calendar.getInstance()
        val endTime = calendar.timeInMillis

        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis // Start of today (Midnight)

        // 2. USE AGGREGATE: This sums up all sessions for the day into one Map
        val statsMap = usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)

        // 3. IDENTIFY THE CURRENT APP: Find what the user is looking at right now
        // Note: We still use queryUsageStats just to find the "lastTimeUsed" order
        val rawStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        val topApp = rawStats?.maxByOrNull { it.lastTimeUsed }

        if (topApp !== null) {
            val packageName = topApp.packageName
            // Use the aggregated Map to get the ACTUAL total time used today
            val totalTimeMs = statsMap[packageName]?.totalTimeInForeground ?: 0L

            val sharedPrefs = getSharedPreferences("AppLimits", Context.MODE_PRIVATE)
            val limitMins = sharedPrefs.getInt(packageName, 0)
            if (limitMins > 0) {
                // TODO temp testing with minutes instead of hours
                // 1 minute = 60,000 milliseconds
                val limitMs = limitMins * 60000L
//                val limitMs = limitHours * 3600000L
                val progress = (totalTimeMs.toDouble() / limitMs.toDouble())
                Log.v("SQUEEZE", "totalTimeMs ${totalTimeMs / 60000L}, limitMins $limitMins, progress $progress")
                val newLevel = when {
                    progress >= 1.0  -> 4 // Blocked
                    progress >= 0.9  -> 3 // Super Lag
                    progress >= 0.75 -> 2 // Very Laggy
                    progress >= 0.5  -> 1 // Slightly annoying
                    else             -> 0           // Full speed
                }

                if (newLevel > 0) {
                    applySqueeze(newLevel)
                    // Only call this if the targeted app has actually changed
                    if (lastThrottledApp != packageName) {
                        establishVpn(packageName)
                    }
                } else {
                    establishVpn(null)
                    stopSqueeze()
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
        // Kill any existing thread before starting a new one
        vpnThread?.interrupt()
        Log.d("SQUEEZE", "startVpnThread")

        vpnThread = Thread {
            val vpnInterfaceRef = vpnInterface ?: return@Thread
            val input = FileInputStream(vpnInterfaceRef.fileDescriptor)
            val output = FileOutputStream(vpnInterfaceRef.fileDescriptor)
            val buffer = ByteBuffer.allocate(32768)

            var bucket = 0L
            var lastCheck = System.currentTimeMillis()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val length = input.read(buffer.array())
                    if (length <= 0) continue // Skip empty reads

                    Log.v("SQUEEZE_INIT", "Length >0 check: $length bytes, isThrottling $isThrottling and currentSqueezeLevel, $currentSqueezeLevel")

                    if (length > 64 && isThrottling) { // only throttle the data packets (>64), not the heartbeat (<64) ones
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastCheck

                        val bytesPerMs = when (currentSqueezeLevel) {
                            1 -> 400.0 // Give it enough air to breathe, but barely
                            2 -> 100.0
                            3 -> 20.0
                            4 -> 0.0
                            else -> 20000.0
                        }
                        val maxBurst = 10000L
                        // Add "earned" bytes to the bucket based on time passed
                        bucket += (elapsed * bytesPerMs).toLong()
                        lastCheck = now
                        if (bucket > maxBurst) bucket = maxBurst

                        // If the packet is bigger than our budget, wait until we can afford it
                        if (length > bucket) {
                            Log.v("SQUEEZE_MATH", "$length length exceeded $bucket bucket")
                            val waitTime = ((length - bucket) / bytesPerMs).toLong()
                            Thread.sleep(waitTime)
                            // Pay the debt and reset
                            bucket = 0
                            lastCheck = System.currentTimeMillis() // Update again after sleep!
                        } else {
                            bucket -= length
                            Log.v("SQUEEZE_MATH", "PASSED: $length bytes, bucket now at $bucket")
                        }
                    }
                    Log.v("SQUEEZE_BYPASS", "Heartbeat passed: $length bytes")
                    // 3. Always write back
                    output.write(buffer.array(), 0, length)
                    buffer.clear()
                }
            } catch (e: Exception) {
                // EBADF is expected here when vpnInterface.close() is called from another thread
                if (e.message?.contains("EBADF") == true) {
                    Log.d("SQUEEZE", "VPN Interface closed, thread exiting safely.")
                } else {
                    Log.e("VPN_PUMP", "Pump error: ${e.message}")
                }
            }
        }.apply {
            name = "VPN-Pump-Thread"
            start()
        }
    }
    private fun applySqueeze(level: Int) {
        this.isThrottling = true
        this.currentSqueezeLevel = level
        Log.d("SQUEEZE", "squeeze level $level")
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