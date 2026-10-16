# IMS for Pixel

[![GitHub Release](https://img.shields.io/github/v/release/svenuks/ImsForPixel?color=blue)](https://github.com/svenuks/ImsForPixel/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-brightgreen.svg)](https://developer.android.com)

**IMS for Pixel** is a lightweight, rootless utility designed to enable VoLTE (Voice over LTE), VoWiFi (Wi-Fi Calling), VoNR (5G Voice), and Carrier Aggregation (LTE+) on Google Pixel devices running modern Android versions.

Unlike traditional solutions that require Magisk root modules or third-party background services, *IMS for Pixel* is completely standalone. It embeds an ADB client and privileged test instrumentation directly inside the application, allowing you to configure telephony carrier overrides directly on device via standard Android Wireless Debugging.

---

## ✨ Features

- **Rootless & Self-Contained:** No Root, no Bootloader unlocking, and no separate Shizuku app required.
- **Dual SIM & eSIM Ready:** Automatically detects active physical SIM cards and eSIM profiles, allowing independent per-slot configuration.
- **Carrier Aggregation (LTE+ / 4G+):** Lowers bandwidth threshold overrides by default (`lte_plus_threshold_bandwidth_khz_int = 0`) to trigger LTE-Advanced aggregation whenever supported by the carrier base station.
- **Full VoLTE, VoWiFi & VoNR Stack:** Overrides carrier configs for VoLTE, VoWiFi (including roaming support), VoNR, and Supplementary Services (UT) via `CarrierConfigManager`.
- **Automatic Multi-Language (i18n):** Natively supports English (default), Russian (Русский), and Simplified Chinese (简体中文), dynamically adapting to your device's system locale.
- **100% Private & Auditable:** Zero network tracking, zero analytics. All ADB communication runs exclusively over `127.0.0.1` local loopback.
- **Non-Destructive:** Overrides live in system memory. Reverting back to factory carrier defaults is instant via a single tap on **Restore Defaults**.

---

## 🚀 What's New in v1.1.0

- **Native Multi-Language Support:** Full translation in English, Russian, and Simplified Chinese.
- **Carrier Aggregation Support:** Built-in LTE+ threshold override enabled by default during activation.
- **Optimized Footprint:** Cleaned up unused vector dependencies, cutting APK size nearly in half while preserving 100% reflection and instrumentation stability.
- **Enhanced eSIM Handling:** Robust subId resolution for eSIM profiles on Google Tensor devices.

---

## 🛠️ How It Works

1. **Local Wireless Pairing:** The app detects the system's Wireless Debugging service port via local mDNS (`_adb-tls-pairing._tcp`). You input the 6-digit pairing code from Developer Options, and the internal ADB client establishes an authorized connection over `127.0.0.1`.
2. **Privileged Instrumentation:** The app invokes an internal `BrokerInstrumentation` runner via `am instrument`. Under Android's testing framework, this process adopts shell identity permissions (`android.permission.MODIFY_PHONE_STATE`).
3. **Carrier Configuration Overrides:** It applies in-memory carrier configuration overrides via `CarrierConfigManager.overrideConfig(subId, bundle, false)`.
4. **IMS Reset & Verification:** The telephony stack receives an IMS reset signal per active subscription ID. The app polls IMS registration state in real-time and reports status directly in the notification shade and UI.

---

## 📋 Requirements

| Requirement | Details |
|---|---|
| OS Version | Android 10 (API 28) or higher |
| Compatibility | Google Pixel devices (Pixel 3 through Pixel 9 / Fold series) |
| Network | Connected to a Wi-Fi network (required for Wireless Debugging) |
| Developer Options | Wireless Debugging enabled |
| Root / Magisk / Shizuku | ❌ Not required |

---

## 📖 Quick Start

### 1. Enable Wireless Debugging
1. Open **Settings → About phone** and tap **Build number** 7 times to enable Developer Options.
2. Go to **Settings → System → Developer options**.
3. Toggle **Wireless debugging** ON.

### 2. Install the App
Download the latest `ImsForPixel-v1.1.0.apk` from [GitHub Releases](https://github.com/svenuks/ImsForPixel/releases) and install it.

### 3. Pair & Activate
1. Open **IMS for Pixel**.
2. Tap **Go to Wireless Debugging** and select **Pair device with pairing code**.
3. When the pairing prompt appears in the notification shade, tap **Send Pairing Code** and enter the 6-digit code.
4. Once paired, configure your desired options (VoLTE, VoWiFi, VoNR, Roaming) for SIM 1 and SIM 2.
5. Tap **One-Tap Activate**.
6. Within a few seconds, a system notification will confirm the IMS registration status for each active SIM card.

---

## 💻 Building from Source

### Prerequisites
- JDK 17 (or Android Studio's bundled JBR)
- Android SDK Platform 36 and Build-Tools 35

### Clone & Build

```bash
git clone https://github.com/svenuks/ImsForPixel.git
cd ImsForPixel

# Build Release APK
./gradlew assembleRelease
```

The resulting APK will be located at:
`app/build/outputs/apk/release/app-release.apk`

---

## 🔒 Security & Privacy

- **Zero Telemetry:** No tracking, no crashlytics, and no analytics SDKs.
- **Local Loopback Only:** All commands and ADB sockets target `127.0.0.1` locally. No port is exposed to the local network or the internet.
- **Auditable Open Source:** The entire logic is implemented transparently across three concise Kotlin source files.

---

## ❓ FAQ

**Q: Do settings persist across reboots?**  
A: `CarrierConfigManager.overrideConfig` is an in-memory override managed by Android OS and resets upon device reboot. Simply open the app and tap **One-Tap Activate** once after rebooting.

**Q: How do I revert all changes?**  
A: Tap **Restore Defaults** inside the app. All custom carrier config overrides will be cleared immediately, restoring your network settings to carrier defaults.

**Q: Does it work with eSIM?**  
A: Yes. Dual SIM and eSIM profiles are mapped to their respective subscription IDs automatically.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
