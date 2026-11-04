# SwiftKey Patches

Patches for Microsoft SwiftKey **9.12.13.13**, built on the [Morphe](https://github.com/MorpheApp) patcher.

Load the patch bundle (`.mpp` from the [releases](../../releases)) into Morphe as a patch source,
pick the SwiftKey 9.12.13.13 APK and patch it.

## Patches

| Patch | Default | What it does |
| --- | --- | --- |
| **Add blank toolbar elements** | on | Adds six blank elements to the keyboard's toolbar, like the emoji, GIF or incognito buttons, that are empty and do nothing when tapped. They are real catalog items: they appear in the customizer's reorder screen and can be dragged, reordered and removed like any other item. |
| **Change package name** | on | Renames the package (`com.touchtype.swiftkey.morphe`) so the patched build installs next to the stock SwiftKey, and fixes everything a plain rename breaks (signature permission, provider authorities, `application_id`, hardcoded self references). |
| **Debloat** | on | Removes the non-keyboard bloat: Account and Rewards settings, AI achievements, Bing/Copilot rich-input settings and the online theme Gallery; toolbar items Writing assistant (Copilot), Create, Rewards, Share, Search, Tips and Sign in; the two sticker tabs of the emoji/GIF panel. |
| **Disable cloud sign-in prompt** | on | Keeps the optional Microsoft cloud sign-in onboarding from hijacking the launcher. |
| **Network connections** | on | Cuts every tracking and telemetry connection off **at the root** - the merged telemetry shutdown (first-party telemetry records and uploads, Adjust, Crashlytics, Firebase Sessions, legacy Google Analytics, exception reporting) needs no switch, those hosts can never connect - and adds a native **Network connections** section to SwiftKey's settings with a switch for each remaining online feature: **GIF search**, the **translator** and **language & model downloads**. |
| **Remove internet permission** | off | Removes `android.permission.INTERNET` from the manifest, so Android itself refuses every network connection - the strongest possible block, and fully offline. Optional: it also takes down GIF search and language/model downloads (the Network connections switches then switch nothing). |

All patches are enabled by default except **Remove internet permission**.

### Network connections

**Cut off at the root (no switch, they are gone):** telemetry records and uploads (PaperBoy),
Firebase, Crashlytics, Adjust, Google Analytics/Tag Manager/Ads, Firebase Installations, Google
account/games APIs, the Copilot/AI backend, theme store downloads, Microsoft token sharing and
the cloud sync/connect APIs. Their manifest components are disabled, their job services are
neutralized in code (crash-free), telemetry records are dropped before any sink can persist or
upload them, Firebase collection is turned off by meta-data, and the runtime guard refuses every
connection to a host no remaining feature needs.

**Kept, each with its own switch** (Settings → Network connections, right after Privacy):

- **GIF search** — the keyboard's GIF panel: `g.tenor.com` and the `*.tenor.com` result thumbnails,
  plus `bibo.api.swiftkey.com`, the SwiftKey configuration service the panel fetches its Tenor API
  key from (1.2.2 blocked it, so GIF search stopped working even with the switch on; 1.2.3 fixes it).
- **Translator** — translation through the external Microsoft Translator app; the switch disables
  the app binding itself.
- **Language & model downloads** — languages and typing models from Settings: the catalog and the
  model archives are served by `jenson.api.swiftkey.com` (with `cdn.swiftkey.microsoft.com` as the
  alternative catalog host; 1.2.2 wrongly allowed the theme-store `touchtype-fluency.com`/azure
  hosts here, which is why downloads were blocked even with the switch on - 1.2.3 fixes it).

The switches are the app's own `TrackedMaterialSwitchPreference` entries in a screen hosted by the
app's own container activity and preference fragment, so the section looks and behaves exactly like
the built-in settings. They persist to the app's default shared preferences - the very state the
runtime guard reads on every connection attempt - and the keyboard, the settings UI and the
background services share one process, so a toggle applies immediately, everywhere.

**Nothing can bypass it**: the checks live at the connection setup of the bundled HTTP client
(every request, download and websocket), in front of every `URL.openConnection()` call site, in
front of every WebView page load and in front of every service binding. A blocked connection fails
exactly like being offline, which SwiftKey already handles gracefully.

### Remove internet permission

Deletes the `INTERNET` uses-permission from the manifest, so the platform itself denies every
socket: the app cannot even resolve a name or open a connection to send a single byte. Apply it
only for a fully offline keyboard - with it, GIF search and language/model downloads stop working
too (their switches remain but have nothing to allow). For everything else, the Network
connections patch already blocks every tracking and telemetry host at the root while keeping the
allowed features working.

### Add blank toolbar elements

Adds six empty, transparent buttons of the same size as the regular toolbar buttons. They render
blank, ignore taps (not even telemetry fires) and never appear in the toolbar customizer.

## Building

The build is self-contained - no GitHub Packages authentication and no Android SDK required:

```bash
./gradlew :patches:buildAndroid generatePatchesList
```

The Morphe Gradle plugin (`app.morphe.patches` 1.3.2) and the patcher library
(`app.morphe:morphe-patcher` 1.5.2) are vendored in [`vendor/maven-repo`](vendor/maven-repo)
(rebuilt from the tagged sources of their upstream repositories) so CI works with a plain
`GITHUB_TOKEN` or no credentials at all.

The runtime half of the *Network connections*, *Debloat* and *Add blank toolbar elements* patches
lives in [`extensions/swiftkey-src`](extensions/swiftkey-src) (plain Java against framework APIs
and compile-only stubs of the R8-renamed app classes). It is compiled with `javac` and dexed with
`d8` - no Android SDK needed - into `patches/src/main/resources/extensions/swiftkey.mpe`, which
the patches merge into the app with `extendWith`. To rebuild it after changing the sources:

```bash
extensions/swiftkey-src/build-extension.sh <path-to-r8.jar> <path-to-android.jar>
```

## License

See [LICENSE](LICENSE) and [NOTICE](NOTICE).
