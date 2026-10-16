package com.svenuks.imsforpixel

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.system.Os
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import android.os.Build
import android.app.UiAutomation
import android.content.Intent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager

class BrokerInstrumentation : Instrumentation() {
    companion object {
        private const val TAG = "VoLTEBrokerInst"
    }

    private fun findMethod(obj: Any, name: String): Method? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val method = clazz.declaredMethods.firstOrNull { it.name == name }
                if (method != null) {
                    method.isAccessible = true
                    return method
                }
            } catch (e: Exception) {
                // Ignore
            }
            clazz = clazz.superclass
        }
        
        for (iface in obj.javaClass.interfaces) {
            try {
                val method = iface.declaredMethods.firstOrNull { it.name == name }
                if (method != null) {
                    method.isAccessible = true
                    return method
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return null
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Log.d(TAG, "BrokerInstrumentation starting...")
        
        val queryOnly = arguments?.getString("query_only") == "true" || arguments?.getBoolean("query_only") == true
        Log.d(TAG, "queryOnly mode: $queryOnly")
        
        val clearArg = arguments?.getString("clear") == "true" || arguments?.getBoolean("clear") == true
        Log.d(TAG, "clearArg: $clearArg")

        Thread {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
                Log.d(TAG, "Successfully applied HiddenApiBypass exemptions in instrumentation")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply HiddenApiBypass exemptions in instrumentation", e)
            }
            
            try {
                var uiAutomation: UiAutomation? = null
                var retries = 5
                while (retries > 0) {
                    try {
                        uiAutomation = getUiAutomation()
                        if (uiAutomation != null) {
                            Log.d(TAG, "Successfully connected UiAutomation")
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to connect UiAutomation, retries left: ${retries - 1}", e)
                        retries--
                        if (retries > 0) {
                            try { Thread.sleep(500) } catch (ignored: Exception) {}
                        } else {
                            throw e
                        }
                    }
                }

                if (uiAutomation != null) {
                    uiAutomation.adoptShellPermissionIdentity()
                    Log.d(TAG, "Successfully adopted shell permission identity via UiAutomation")
                } else {
                    Log.e(TAG, "UiAutomation is null, cannot adopt shell permission identity")
                }
                
                try {
                    if (queryOnly) {
                        queryStatusOnly()
                    } else {
                        patchAllSimsAndPoll(arguments)
                        showImsStatusNotification(!clearArg)
                    }
                } finally {
                    if (uiAutomation != null) {
                        try {
                            uiAutomation.dropShellPermissionIdentity()
                            Log.d(TAG, "Released shell permission identity via UiAutomation")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to drop shell permission identity", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run instrumentation patch", e)
            } finally {
                finish(0, Bundle())
            }
        }.start()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        Log.d(TAG, "Instrumentation finish() called")
        try {
            super.finish(resultCode, results)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Instrumentation.finish() ignored safely", e)
        }
    }

    private fun getActiveSimTargets(subManager: SubscriptionManager): List<Pair<Int, Int>> {
        val targets = mutableListOf<Pair<Int, Int>>() // List of (slotIndex, subId)
        
        // Method 1: Check slot indices 0 and 1 directly via SubscriptionManager
        for (slot in 0..1) {
            try {
                val info = subManager.getActiveSubscriptionInfoForSimSlotIndex(slot)
                if (info != null && info.subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    targets.add(Pair(slot, info.subscriptionId))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get active sub info for slot $slot", e)
            }
        }

        // Method 2: Inspect activeSubscriptionInfoList for eSIM profiles that report slotIndex < 0
        try {
            val list = subManager.activeSubscriptionInfoList ?: emptyList()
            for (info in list) {
                val subId = info.subscriptionId
                if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) continue
                
                var slotIndex = info.simSlotIndex
                if (slotIndex < 0) {
                    // eSIM profile with -1 slot index: map to an unused slot index (prefer slot 1 if 0 is taken, or vice-versa)
                    val existingSlots = targets.map { it.first }.toSet()
                    slotIndex = if (!existingSlots.contains(1)) 1 else if (!existingSlots.contains(0)) 0 else 1
                }
                
                if (targets.none { it.first == slotIndex || it.second == subId }) {
                    targets.add(Pair(slotIndex, subId))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect activeSubscriptionInfoList", e)
        }

        Log.d(TAG, "Resolved SIM targets: $targets")
        return targets
    }

    private fun queryStatusOnly() {
        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val simTargets = getActiveSimTargets(subManager)
        Log.d(TAG, "QueryStatusOnly: Found ${simTargets.size} active SIM targets")

        val sharedPrefs = context.getSharedPreferences("volte_settings", Context.MODE_PRIVATE)

        for ((slotIndex, subId) in simTargets) {
            val isImsRegistered = checkImsRegistered(subId, slotIndex)
            Log.d(TAG, "QueryStatusOnly: SIM slot $slotIndex (subId $subId) IMS Registered: $isImsRegistered")
            
            sharedPrefs.edit().putBoolean("ims_registered_slot_$slotIndex", isImsRegistered).commit()
            
            try {
                val statusFile = java.io.File(context.filesDir, "ims_status_$slotIndex.txt")
                statusFile.writeText(isImsRegistered.toString())
                Log.d(TAG, "QueryStatusOnly: Saved status file for slot $slotIndex")
            } catch (e: Exception) {
                Log.e(TAG, "QueryStatusOnly: Failed to save status file", e)
            }
        }
    }

    private fun checkImsRegistered(subId: Int, slotIndex: Int): Boolean {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, Context.TELEPHONY_SERVICE) as android.os.IBinder
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val telephonyService = asInterfaceMethod.invoke(null, binder)
            
            val iTelephonyClass = Class.forName("com.android.internal.telephony.ITelephony")
            
            // 1. Try isImsRegisteredForSubscriber(subId)
            try {
                val method = iTelephonyClass.declaredMethods.firstOrNull {
                    it.name == "isImsRegisteredForSubscriber" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                if (method != null) {
                    method.isAccessible = true
                    return method.invoke(telephonyService, subId) as Boolean
                }
            } catch (e: Exception) {}

            // 2. Try isImsRegistered(subId)
            try {
                val method = iTelephonyClass.declaredMethods.firstOrNull {
                    it.name == "isImsRegistered" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                if (method != null) {
                    method.isAccessible = true
                    return method.invoke(telephonyService, subId) as Boolean
                }
            } catch (e: Exception) {}

            // 3. Try isImsRegistered(slotIndex)
            try {
                val method = iTelephonyClass.declaredMethods.firstOrNull {
                    it.name == "isImsRegistered"
                }
                if (method != null) {
                    method.isAccessible = true
                    return method.invoke(telephonyService, slotIndex) as Boolean
                }
            } catch (e: Exception) {}

            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check IMS registration for subId=$subId, slot=$slotIndex reflectively", e)
            false
        }
    }

    private fun patchAllSimsAndPoll(arguments: Bundle?) {
        val sharedPrefs = context.getSharedPreferences("volte_settings", Context.MODE_PRIVATE)
        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val carrierConfigManager = context.getSystemService(CarrierConfigManager::class.java) ?: return
        val telephonyManager = context.getSystemService(TelephonyManager::class.java) ?: return

        val simTargets = getActiveSimTargets(subManager)
        Log.d(TAG, "Processing ${simTargets.size} active SIM targets")

        val hasClearArg = arguments?.containsKey("clear") == true
        val clearArg = arguments?.getString("clear") == "true" || arguments?.getBoolean("clear") == true

        // Phase 1: Apply overrides or Clear overrides, and trigger IMS reset
        for ((slotIndex, subId) in simTargets) {
            Log.d(TAG, "Processing SIM slot $slotIndex (SubID $subId)")

            val clear = if (hasClearArg) {
                clearArg
            } else {
                sharedPrefs.getBoolean("clear_slot_$slotIndex", false)
            }

            if (clear) {
                Log.d(TAG, "Clearing config for slot $slotIndex (subId $subId)")
                try {
                    val appliedFile = java.io.File(context.filesDir, "config_applied_$slotIndex.txt")
                    appliedFile.writeText("false")
                } catch (e: Exception) {}
                try {
                    val overrideMethod = carrierConfigManager.javaClass.declaredMethods.firstOrNull {
                        it.name == "overrideConfig" && it.parameterTypes.size == 3
                    } ?: carrierConfigManager.javaClass.declaredMethods.firstOrNull {
                        it.name == "overrideConfig"
                    }
                    if (overrideMethod != null) {
                        overrideMethod.isAccessible = true
                        if (overrideMethod.parameterTypes.size == 3) {
                            overrideMethod.invoke(carrierConfigManager, subId, null, false)
                        } else {
                            overrideMethod.invoke(carrierConfigManager, subId, null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear carrier config reflectively for subId $subId", e)
                }
            } else {
                val volte = sharedPrefs.getBoolean("volte_slot_$slotIndex", true)
                val vonr = sharedPrefs.getBoolean("vonr_slot_$slotIndex", true)
                val vowifi = sharedPrefs.getBoolean("vowifi_slot_$slotIndex", true)
                val crossSim = sharedPrefs.getBoolean("cross_sim_slot_$slotIndex", false)
                val wfcRoaming = sharedPrefs.getBoolean("wfc_roaming_slot_$slotIndex", true)
                val ssUt = sharedPrefs.getBoolean("ss_ut_slot_$slotIndex", true)
                val showIms = sharedPrefs.getBoolean("show_ims_slot_$slotIndex", true)
                val allowApn = sharedPrefs.getBoolean("allow_apn_slot_$slotIndex", false)
                val bundle = PersistableBundle()

                // VoLTE enabling & provisioning & roaming overrides
                bundle.putBoolean("carrier_volte_available_bool", volte)
                bundle.putBoolean("carrier_volte_roaming_available_bool", volte)
                bundle.putBoolean("carrier_default_volte_roaming_enabled_bool", volte)
                bundle.putBoolean("enhanced_4g_lte_on_by_default_bool", volte)
                bundle.putBoolean("hide_enhanced_4g_lte_bool", !volte)
                bundle.putBoolean("editable_enhanced_4g_lte_bool", volte)
                bundle.putBoolean("carrier_volte_provisioned_bool", volte)
                bundle.putBoolean("carrier_volte_provisioning_required_bool", false)

                // VoNR (5G Calling) overrides
                bundle.putBoolean("vonr_enabled_bool", vonr)
                bundle.putBoolean("vonr_setting_visibility_bool", vonr)

                // VoWiFi (Wi-Fi Calling) & roaming overrides
                bundle.putBoolean("carrier_wfc_ims_available_bool", vowifi)
                bundle.putBoolean("carrier_wfc_ims_roaming_available_bool", vowifi)
                bundle.putBoolean("carrier_default_wfc_ims_enabled_bool", vowifi)
                bundle.putBoolean("carrier_wfc_ims_provisioned_bool", vowifi)
                bundle.putBoolean("editable_wfc_mode_bool", vowifi)
                bundle.putBoolean("editable_wfc_roaming_mode_bool", vowifi)
                bundle.putBoolean("carrier_default_wfc_ims_roaming_enabled_bool", wfcRoaming)

                // Other settings
                bundle.putBoolean("carrier_cross_sim_ims_available_bool", crossSim)
                bundle.putBoolean("enable_cross_sim_calling_on_opportunistic_data_bool", crossSim)
                bundle.putBoolean("carrier_supports_ss_over_ut_bool", ssUt)
                bundle.putBoolean("show_ims_registration_status_bool", showIms)
                bundle.putBoolean("allow_adding_apns_bool", allowApn)

                Log.d(TAG, "Applying config for slot $slotIndex (subId $subId): VoLTE=$volte, VoNR=$vonr, VoWiFi=$vowifi")
                
                try {
                    val overrideMethod = carrierConfigManager.javaClass.declaredMethods.firstOrNull {
                        it.name == "overrideConfig" && it.parameterTypes.size == 3
                    } ?: carrierConfigManager.javaClass.declaredMethods.firstOrNull {
                        it.name == "overrideConfig"
                    }
                    if (overrideMethod != null) {
                        overrideMethod.isAccessible = true
                        if (overrideMethod.parameterTypes.size == 3) {
                            overrideMethod.invoke(carrierConfigManager, subId, bundle, false)
                        } else {
                            overrideMethod.invoke(carrierConfigManager, subId, bundle)
                        }
                        Log.d(TAG, "Applied config reflectively for subId $subId")
                        try {
                            val appliedFile = java.io.File(context.filesDir, "config_applied_$slotIndex.txt")
                            appliedFile.writeText("true")
                        } catch (e: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply config reflectively for subId $subId", e)
                }
            }

            // Reset IMS registration for subId using sub-bound TelephonyManager instance
            try {
                val subTelephonyManager = telephonyManager.createForSubscriptionId(subId)
                val resetImsMethod = findMethod(subTelephonyManager, "resetIms")
                if (resetImsMethod != null) {
                    if (resetImsMethod.parameterTypes.isNotEmpty()) {
                        resetImsMethod.invoke(subTelephonyManager, slotIndex)
                    } else {
                        resetImsMethod.invoke(subTelephonyManager)
                    }
                    Log.d(TAG, "IMS reset sent for slot $slotIndex subId $subId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset IMS reflectively for subId $subId", e)
            }
        }

        // Phase 2: Poll and update status for up to 30 seconds
        Log.d(TAG, "Entering status polling loop...")
        var pollsLeft = 30
        val totalPolls = 30
        while (pollsLeft > 0) {
            var allRegistered = true
            for ((slotIndex, subId) in simTargets) {
                val isImsRegistered = checkImsRegistered(subId, slotIndex)
                Log.d(TAG, "Poll $pollsLeft: SIM slot $slotIndex (subId $subId) IMS Registered: $isImsRegistered")
                
                sharedPrefs.edit().putBoolean("ims_registered_slot_$slotIndex", isImsRegistered).commit()
                try {
                    val statusFile = java.io.File(context.filesDir, "ims_status_$slotIndex.txt")
                    statusFile.writeText(isImsRegistered.toString())
                } catch (e: Exception) {}

                if (!isImsRegistered) {
                    allRegistered = false
                }
            }

            val secondsElapsed = totalPolls - pollsLeft
            if (hasClearArg && clearArg) {
                if (!allRegistered) {
                    Log.d(TAG, "IMS successfully unregistered. Stopping poll.")
                    break
                }
            } else {
                if (allRegistered && secondsElapsed >= 5) {
                    Log.d(TAG, "IMS successfully registered. Stopping poll.")
                    break
                }
            }

            try {
                Thread.sleep(1000)
            } catch (ignored: Exception) {}
            pollsLeft--
        }
    }

    private fun showImsStatusNotification(isActivate: Boolean) {
        val channelId = "ims_status_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, context.getString(R.string.notification_channel_ims_status), NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(ch)
        }
        
        val slot0 = try { java.io.File(context.filesDir, "ims_status_0.txt").readText().trim().toBoolean() } catch (e: Exception) { false }
        val slot1 = try { java.io.File(context.filesDir, "ims_status_1.txt").readText().trim().toBoolean() } catch (e: Exception) { false }
        val anyRegistered = slot0 || slot1
        val title = if (isActivate) {
            if (anyRegistered) context.getString(R.string.notification_title_volte_success) else context.getString(R.string.notification_title_volte_done)
        } else {
            context.getString(R.string.notification_title_config_restored)
        }
        val body = if (isActivate) {
            buildString {
                if (slot0) append(context.getString(R.string.notification_sim1_registered)) else append(context.getString(R.string.notification_sim1_not_registered))
                if (slot1) append(context.getString(R.string.notification_sim2_registered)) else append(context.getString(R.string.notification_sim2_not_registered))
            }
        } else {
            context.getString(R.string.notification_config_cleared)
        }
        
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(203, notification)
    }
}
