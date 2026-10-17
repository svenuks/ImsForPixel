# IMS for Pixel

> Rootless VoLTE, VoNR (5G Voice), Wi-Fi Calling, and Carrier Aggregation enabler for Google Pixel devices.

[![Platform](https://img.shields.io/badge/Platform-Android%2010--17-brightgreen.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-28%2B-blue.svg)](https://developer.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/svenuks/ImsForPixel?color=blue)](https://github.com/svenuks/ImsForPixel/releases)

---

## Overview

**IMS for Pixel** is a standalone, rootless utility engineered to enable **VoLTE**, **VoNR (5G Voice Calling)**, **VoWiFi (Wi-Fi Calling)**, and **Carrier Aggregation (LTE+)** on Google Pixel devices. It bypasses regional carrier bundle restrictions—even on **Android 17**, where modern operating system security heavily limits third-party telephony access.

Unlike traditional workarounds that depend on root access, Magisk modules, PC tethering, or external privilege managers, **IMS for Pixel is 100% self-contained**. It packages an embedded pure-Kotlin ADB client with Android's native instrumentation framework, allowing you to unlock your device's full cellular capabilities directly on the phone.

---

## 🏆 Key Advantages & Strengths

### Why IMS for Pixel?

Most existing solutions in the Android community suffer from severe operational friction: they either require unlocking the bootloader and rooting via Magisk/KernelSU, require connecting to a desktop computer with an ADB cable after every reboot, or require installing and maintaining third-party privilege services like Shizuku.

*IMS for Pixel* combines the best attributes into a unified, zero-dependency experience:

| Feature / Advantage | Magisk / Root Modules | Shizuku-based Tools | IMS for Pixel |
|---|---|---|---|
| **Root Required** | ⚠️ Yes (Breaks SafetyNet/Play Integrity) | ❌ No | ❌ **No Root Required** |
| **Requires Shizuku App** | ❌ No | ⚠️ Yes (External dependency) | ❌ **Completely Standalone** |
| **Requires PC / Cable** | ⚠️ Often during initial setup | ⚠️ Often needed to start Shizuku | ❌ **Zero Cables / Zero PC** |
| **Self-Contained Single APK** | ❌ No | ❌ No | ✅ **All-in-One APK** |
| **Modern Android 15, 16, 17** | ⚠️ Kernel/Module breaks on updates | ⚠️ Subject to API restrictions | ✅ **Tested up to Android 17** |
| **Dual SIM & eSIM Support** | ⚠️ Varies | ⚠️ Often physical slot only | ✅ **Full Physical + eSIM Mapping** |
| **Real IMS Status Feedback** | ❌ Blind reboot check | ⚠️ In-app only | ✅ **System Notification Verification** |
| **Carrier Aggregation (LTE+)** | ⚠️ Custom XML editing | ❌ Not enabled by default | ✅ **Enabled by Default** |
| **Reversibility** | ⚠️ Risk of bootloop/partition write | ⚠️ Requires manual uninstallation | ✅ **1-Tap Instant In-Memory Revert** |

### Core Architectural Strengths

1. **Fully Self-Contained Architecture:**
   The application embeds a lightweight, local-loopback ADB implementation ([Kadb](https://github.com/flyfishxu/Kadb)). It pairs with Android's built-in Wireless Debugging service directly on device without requiring any external app or daemon to be running in the background.
2. **Ephemeral & Safe (Non-Destructive):**
   Configuration changes are injected directly into system memory using Android's official `CarrierConfigManager.overrideConfig(subId, bundle, false)`. It never modifies `/system`, `/vendor`, or device partitions. If you ever want to revert, a single tap on **Restore Defaults** restores carrier stock behavior immediately.
3. **True Dual SIM & eSIM Multi-Subscriber Engine:**
   Modern Pixels utilize flexible multi-SIM and eSIM configurations. *IMS for Pixel* independently inspects and maps active subscription IDs (`subId`), allowing separate VoLTE, VoWiFi, and roaming preferences for Slot 1 and Slot 2.
4. **Live Verification & Notification Reporting:**
   Instead of leaving users guessing whether their IMS registration succeeded, the built-in runner queries modem registration states in real-time and pushes an official Android system notification reporting real per-slot registration status.
5. **Private & Auditable:**
   Every socket connection is strictly bound to `127.0.0.1` (localhost). There are zero analytics libraries, zero tracking SDKs, and zero outbound network calls.

---

## ⚡ Feature Matrix

- ✅ **VoLTE (Voice over LTE):** Crystal-clear HD voice calls over 4G LTE without dropping to 2G/3G.
- ✅ **VoNR (5G Voice):** Standalone 5G voice calling support on compatible 5G SA networks.
- ✅ **Wi-Fi Calling (VoWiFi):** Make and receive carrier calls over Wi-Fi when cellular reception is weak.
- ✅ **VoWiFi Roaming:** Keep Wi-Fi Calling enabled even when traveling internationally or roaming outside your home network.
- ✅ **Carrier Aggregation (LTE+ / 4G+):** Automatically lowers the bandwidth threshold (`lte_plus_threshold_bandwidth_khz_int = 0`), displaying the `LTE+`/`4G+` indicator and aggregating multi-carrier frequencies whenever supported by local base stations.
- ✅ **Supplementary Services (UT):** Enables carrier call forwarding, call waiting, and call barring configurations over IMS.
- ✅ **Dual SIM & eSIM:** Independent toggles and diagnostic badges for both active subscriptions.
- ✅ **Live Status Monitoring:** Real-time polling reflects active modem IMS registration states.
- ✅ **One-Tap Restore:** Clears all runtime overrides and returns immediately to factory carrier bundles.

---

## 🚀 What's New in v1.1.0

- **Native Multi-Language Support (i18n):** Full localization in English (default), Russian (ru), and Simplified Chinese (zh-CN). The app dynamically detects and matches your device's system language without manual selection.
- **Default Carrier Aggregation (LTE+):** Built-in threshold bypass unlocks status bar `LTE+`/`4G+` indicators without adding unnecessary UI complexity.
- **Optimized APK Footprint:** Trimmed out unused bloated vector libraries, reducing the package size by nearly 50% (~23MB) with zero risk to reflection stability.
- **Enhanced eSIM Resolution:** Improved subscription handling on Google Tensor hardware.

---

## 🛠️ How It Works

```
[IMS for Pixel UI] ──(127.0.0.1 ADB)──► [BrokerInstrumentation]
                                               │ adoptShellPermissionIdentity()
                                               ▼
                                 [CarrierConfigManager.overrideConfig]
                                               │
                                 [TelephonyManager.resetIms]
                                               │
                                 [Poll Status & Post Notification]
```

1. **Local Pairing:** The app auto-discovers the local Wireless Debugging port via mDNS (`_adb-tls-pairing._tcp`). You enter the 6-digit pairing code from Developer Options, establishing an authorized loopback ADB session over `127.0.0.1`.
2. **Privileged Invocation:** The app triggers its internal `BrokerInstrumentation` test runner using `am instrument`. Under the standard Android instrumentation framework, this process temporarily adopts shell permissions (`MODIFY_PHONE_STATE`).
3. **Carrier Bundle Override:** It applies in-memory overrides for your active subscription IDs using `CarrierConfigManager.overrideConfig`.
4. **IMS Re-Attachment:** The telephony stack restarts IMS signaling per active SIM. The tool polls modem registration status and delivers instant confirmation via the notification shade.

---

## 📋 Compatibility & Requirements

| Requirement | Details |
|---|---|
| **Supported OS** | Android 10, 11, 12, 13, 14, 15, 16, **Android 17** ✅ |
| **Devices** | Google Pixel 3 through Pixel 9 / 9 Pro / Fold series (and compatible AOSP devices) |
| **Network** | Connected to Wi-Fi (required by Android OS for Wireless Debugging) |
| **Developer Options** | Wireless Debugging enabled |
| **Root / Magisk / Shizuku** | ❌ **Not required** |

---

## 📖 Quick Start

### 1. Enable Wireless Debugging
1. Open **Settings → About phone** and tap **Build number** 7 times to enable Developer Options.
2. Navigate to **Settings → System → Developer options**.
3. Turn **Wireless debugging** ON.

### 2. Install the Application
Download the latest `ImsForPixel-v1.1.0.apk` from [GitHub Releases](https://github.com/svenuks/ImsForPixel/releases) and install it on your device.

### 3. Pair & Activate
1. Launch **IMS for Pixel**.
2. Tap **Go to Wireless Debugging**, tap **Pair device with pairing code**.
3. In the notification shade prompt, tap **Send Pairing Code** and enter the 6-digit code.
4. Customize toggles for SIM 1 and SIM 2 (VoLTE, VoWiFi, VoNR, Roaming).
5. Tap **One-Tap Activate**.
6. Within a few seconds, a system notification will appear confirming that IMS is registered!

---

## 💻 Building from Source

```bash
# Clone the repository
git clone https://github.com/svenuks/ImsForPixel.git
cd ImsForPixel

# Compile Release APK (JDK 17 required)
./gradlew assembleRelease
```
The compiled APK will be generated at `app/build/outputs/apk/release/app-release.apk`.

---

## 🔒 Security & Privacy

- **Zero Outbound Traffic:** No telemetry, tracking, or crash analytics.
- **Local Loopback Only:** All communications target `127.0.0.1`. No open network ports.
- **Auditable & Open:** The entire logic is transparently implemented in clean Kotlin source code.

---

## ❓ FAQ

**Q: Does it survive a device reboot?**  
A: Because `CarrierConfigManager.overrideConfig` is an in-memory modification designed by Android, overrides reset when the phone reboots. Simply open the app and tap **One-Tap Activate** once after rebooting.

**Q: Can this harm my device or void my warranty?**  
A: No. No system files or partitions are touched. You can tap **Restore Defaults** at any time to immediately revert to factory carrier configurations.

**Q: Does it support physical SIM + eSIM dual standby?**  
A: Yes. Both slots are detected and managed independently based on active subscriber IDs.

---

## 📄 License

This project is open-source software licensed under the [MIT License](LICENSE).
