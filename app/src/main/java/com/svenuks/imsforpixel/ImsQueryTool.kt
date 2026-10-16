package com.svenuks.imsforpixel

import android.os.IBinder

object ImsQueryTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            
            val isubBinder = getServiceMethod.invoke(null, "isub") as? IBinder ?: return
            val isubStubClass = Class.forName("com.android.internal.telephony.ISub\$Stub")
            val isubAsInterface = isubStubClass.getMethod("asInterface", IBinder::class.java)
            val isubService = isubAsInterface.invoke(null, isubBinder)
            
            val iSubClass = Class.forName("com.android.internal.telephony.ISub")
            
            val phoneBinder = getServiceMethod.invoke(null, "phone") as IBinder
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val telephonyService = asInterfaceMethod.invoke(null, phoneBinder)
            val iTelephonyClass = Class.forName("com.android.internal.telephony.ITelephony")

            for (slot in 0..1) {
                var subId = -1
                try {
                    val getSubIdMethod = iSubClass.declaredMethods.firstOrNull { 
                        it.name == "getSubId" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
                    }
                    if (getSubIdMethod != null) {
                        getSubIdMethod.isAccessible = true
                        val res = getSubIdMethod.invoke(isubService, slot)
                        subId = when (res) {
                            is IntArray -> if (res.isNotEmpty()) res[0] else -1
                            is Int -> res
                            is Array<*> -> if (res.isNotEmpty()) (res[0] as? Int ?: -1) else -1
                            else -> -1
                        }
                    }
                } catch (e: Exception) {}

                val isImsRegistered = checkImsRegistered(telephonyService, iTelephonyClass, subId, slot)
                println("RESULT:$slot:$isImsRegistered")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkImsRegistered(telephonyService: Any, iTelephonyClass: Class<*>, subId: Int, slot: Int): Boolean {
        if (subId == -1 || subId == Int.MAX_VALUE) return false
        
        // 1. Try isImsRegisteredForSubscriber(subId)
        try {
            val method = iTelephonyClass.declaredMethods.firstOrNull {
                it.name == "isImsRegisteredForSubscriber" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (method != null) {
                method.isAccessible = true
                val res = method.invoke(telephonyService, subId) as? Boolean
                if (res != null) return res
            }
        } catch (e: Exception) {}

        // 2. Try isImsRegistered(subId)
        try {
            val method = iTelephonyClass.declaredMethods.firstOrNull {
                it.name == "isImsRegistered" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (method != null) {
                method.isAccessible = true
                val res = method.invoke(telephonyService, subId) as? Boolean
                if (res != null) return res
            }
        } catch (e: Exception) {}

        // 3. Fallback to isImsRegistered(slot)
        try {
            val method = iTelephonyClass.declaredMethods.firstOrNull {
                it.name == "isImsRegistered"
            }
            if (method != null) {
                method.isAccessible = true
                val res = method.invoke(telephonyService, slot) as? Boolean
                if (res != null) return res
            }
        } catch (e: Exception) {}

        return false
    }
}


