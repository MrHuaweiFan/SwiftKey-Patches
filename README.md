<div align="center">

# ⌨️ SwiftKey Patches

**Morphe patches that make Microsoft SwiftKey cleaner, private and working again.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android)](https://android.com)
[![Patches](https://img.shields.io/badge/Patches-7%20%2B%202-success?style=flat-square)](#-patches)
[![Morphe](https://img.shields.io/badge/Built%20for-Morphe-orange?style=flat-square)](https://morphe.software)

</div>

A set of [Morphe](https://github.com/MorpheApp) patches for **Microsoft SwiftKey 9.12.13.13** and the
**SwiftKey factory settings 2.2.0.314** companion app. Strip the bloat, switch off the telemetry,
bring the GIF panel back to life and install the result right next to the stock keyboard.

This is a fork of [hoomans-morphe-patches](https://github.com/arandomhooman/hoomans-morphe-patches).

---

## ✨ Highlights

- 🧹 **Debloated** — Rewards, Copilot, stickers, sign-in prompts, the online theme gallery and more are gone from the toolbar, settings and customizer.
- 🔒 **No telemetry** — records dropped before they are stored or uploaded; Adjust, Crashlytics and legacy Google Analytics switched off.
- 🎞️ **GIFs work again** — Tenor's API was shut down on 30 June 2026; the GIF panel now talks to KLIPY instead.
- 🧩 **Side by side with stock** — patched builds use their own package name, so nothing gets replaced.
- 🎛️ **Customizable toolbar** — add up to 6 blank, reorderable toolbar spacers.

---

## 📦 Patches

### SwiftKey keyboard (9.12.13.13)

| Patch | Default | What it does |
| :--- | :---: | :--- |
| **Add blank toolbar elements** | ✅ | Adds 1–6 blank toolbar items you can reorder, with the count set in the patch option |
| **Change package name** | ✅ | Installs as `com.touchtype.swiftkey.morphe`, next to the stock SwiftKey |
| **Debloat** | ✅ | Removes non-keyboard toolbar items, settings entries, the theme gallery, achievements and the smart clip search button |
| **Disable cloud sign-in prompt** | ✅ | Stops the Microsoft sign-in onboarding from hijacking the launcher |
| **Disable telemetry** | ✅ | Blocks telemetry records and uploads, Adjust, Crashlytics and legacy Google Analytics |
| **GIF source** | ✅ | Replaces the dead Tenor backend with KLIPY (free API key required) |
| **Remove GIFs and translator** | ⬜ | Removes the GIF panel and button and the translator toolbar item |

### SwiftKey factory settings (2.2.0.314)

| Patch | Default | What it does |
| :--- | :---: | :--- |
| **Compatible with .morphe SwiftKey** | ✅ | Keeps the factory settings link to your patched keyboard working |
| **Change package name** | ⬜ | Installs next to the stock factory settings app (breaks the link to the keyboard) |

> 💡 Want to know how each patch works under the hood? See the
> [technical guide](SWIFTKEY_PATCHES_GUIDE.md).

---

## 📋 Supported apps

| App | Package | Pinned version |
| :--- | :--- | :--- |
| Microsoft SwiftKey | `com.touchtype.swiftkey` | 9.12.13.13 |
| SwiftKey factory settings | `com.swiftkey.swiftkeyconfigurator` | 2.2.0.314 |

---

## 🚀 Getting started

1. Download the patch bundle (`.mpp`) from the [releases](https://github.com/MrHuaweiFan/SwiftKey-Patches/releases).
2. In **Morphe**, add it as a patch source.
3. Pick the **SwiftKey 9.12.13.13** APK and patch it. Defaults are a good starting point.
4. *(For GIFs)* create a free API key at [klipy.com](https://klipy.com) (Partner Panel → API Keys) and enter it in the **GIF source** patch option. Without a key, GIF searches return empty results.
5. *(Optional)* patch the **SwiftKey factory settings** APK with the same Morphe key, so it keeps working with your patched keyboard.

---

## 🛠️ Building

```bash
./gradlew :patches:buildAndroid generatePatchesList
```

No GitHub Packages login and no Android SDK needed. Full build details, including how to rebuild the
extensions, are in the [technical guide](SWIFTKEY_PATCHES_GUIDE.md#building).

---

## 🙏 Credits

- **[hoomans-morphe-patches](https://github.com/arandomhooman/hoomans-morphe-patches)** — the project this is forked from, including the original telemetry patch.
- **[Morphe](https://github.com/MorpheApp)** — the patcher framework and ecosystem.
- **[KLIPY](https://klipy.com)** — the GIF backend that replaces Tenor.

## 📄 License

GPL-3.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
