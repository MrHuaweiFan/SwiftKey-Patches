# SwiftKey Patches

Patches for Microsoft SwiftKey **9.12.13.13** and **SwiftKey factory settings 2.2.0.314**, built on
the [Morphe](https://github.com/MorpheApp) patcher.

Load the patch bundle (`.mpp` from the [releases](../../releases)) into Morphe as a patch source,
pick the SwiftKey 9.12.13.13 APK (or the factory settings APK) and patch it.

## Patches

| Patch | Default | What it does |
| --- | --- | --- |
| **Add blank toolbar elements** | on | Adds 1-6 blank toolbar elements (real catalog items you can reorder); pick the count in the patch option. |
| **Change package name** | on | Renames the package (`com.touchtype.swiftkey.morphe`) so the patched build installs next to the stock SwiftKey. |
| **Debloat** | on | Removes the non-keyboard bloat: toolbar items, settings entries, theme gallery, achievements and the smart clip search button. |
| **Disable cloud sign-in prompt** | on | Keeps the Microsoft cloud sign-in onboarding from hijacking the launcher. |
| **Disable telemetry** | on | Stops SwiftKey's telemetry records and uploads, Adjust, Crashlytics and legacy Google Analytics. |
| **GIF source** | on | Replaces SwiftKey's dead Tenor GIF backend with KLIPY; needs a free KLIPY API key (klipy.com). |
| **Remove GIFs and translator** | off | Removes the GIF panel and button and the translator toolbar item. |

All SwiftKey patches are enabled by default except **Remove GIFs and translator**.

### Add blank toolbar elements

Adds blank, transparent toolbar buttons of the same size as the regular buttons, labeled
Blank 1 to Blank 6. They are real catalog items: they appear in the customizer's reorder screen
and can be dragged, reordered and removed like any other item, and they do nothing when tapped.

The patch option **Blank elements** chooses how many are added: 1 to 6, defaulting to 6. The
chosen count is written to the `morphe_blank_count` integer resource, which the extension helper
of the catalog rewrite reads at runtime.

### Debloat

Removes the non-keyboard bloat from the root of the item model, so the removed entries are gone
from the strip, the toolgrid, the customizer and saved orderings alike:

- Toolbar: Rewards, Stickers, sign-in, Rich media, Search, Share, Copilot (writing assistant),
  Create and Tips, plus the two sticker pages of the rich content panel.
- Settings: the Account section, Microsoft Rewards, the Bing/Copilot search settings, the online
  theme Gallery tab (Themes starts on "Your themes") and the AI achievements section.
- Smart clip: when text is copied, the ribbon above the suggestion strip shows the copied-text
  pill with a round search button (and sometimes a second action chip) left of it. The patch
  neutralizes the chip factories, so only the pill remains - and the ribbon builder centers the
  pill between the parent edges on its own. Crash-free: the builders null-check every chip.

Typing, clipboard, themes and the rest of the keyboard stay unchanged and functional.

### Disable telemetry

The original hoomans-morphe patch, restored and verified against SwiftKey 9.12.13.13: records are
dropped before any telemetry sink can persist or upload them, both PaperBoy upload loops return
without uploading, Adjust is never initialized, Crashlytics/Sessions collection is turned off by
meta-data, the legacy Google Analytics components and Adjust's lifecycle provider are disabled in
the manifest, and the exception reporter is turned off by resource flag. Push messaging and the
multipurpose job service stay enabled. The fingerprints are pinned for 9.12.13.13 - the PaperBoy
processor is `Lqs/g;` pinned by its `paperBoyConfig` string, kotlin.Unit is `Lsr/D;` - the 9.13.13.5
pins of the upstream patch (`LogSender`, `Lql0/h0;`) do not exist in this build.

### GIF source

Google shut the Tenor API down on 30 June 2026, so the GIF panel of the unpatched 9.12.13.13
cannot load anything anymore. This patch swaps the injected Tenor Retrofit service for an
implementation that speaks KLIPY's Tenor-compatible v2 API, the same backend Microsoft switched
the newer SwiftKey versions to. Create a free KLIPY app key at klipy.com (Partner Panel - API
Keys) and enter it in the patch option; without a key every search returns an empty result
instead of an error.

### Remove GIFs and translator

Removes the GIF feature (toolbar item 0x04 and the GIF page of the rich content panel) and the
Translator toolbar item (0x0a, the one that binds the external Microsoft Translator app) from the
root of the toolbar item model, the same way Debloat removes its bloat items. The GIF panel and
the GIF button are gone everywhere - strip, toolgrid, customizer and saved orderings. Combine with
**GIF source** at your own discretion: with the GIF panel gone, the GIF backend is never reached.

## SwiftKey factory settings

The "SwiftKey factory settings" app (`com.swiftkey.swiftkeyconfigurator` 2.2.0.314) serves
SwiftKey's factory configuration from its bundled database through an exported content provider.
The keyboard reaches it through three fixed channels: the content authority
`com.swiftkey.swiftkeyconfigurator` (hardcoded in the keyboard's content URIs), a package
visibility entry in the keyboard's manifest, and the signature-scoped `READCONFIG` permission.

| Patch | Default | What it does |
| --- | --- | --- |
| **Compatible with .morphe SwiftKey** | on | Keeps the factory settings link intact; re-signs it with your Morphe key. |
| **Change package name** | off | Renames the package (appends .morphe) so it installs next to the stock factory settings app; breaks the keyboard link. |

### Compatible with .morphe SwiftKey

Renaming the keyboard's own package breaks none of the three channels. What breaks the pairing is
the signature: `READCONFIG` is granted only to apps signed like the app that defines it, and a
Morphe-patched keyboard is signed with your Morphe key - which never matches the stock factory
settings app's original certificate. Running the factory settings app through Morphe with this
patch re-signs it with the same Morphe key your .morphe SwiftKey was signed with, and that shared
signature is exactly what `READCONFIG` checks. The patch itself changes nothing about the app: its
package, permission and provider authority stay exactly as the keyboard expects them, and the
patch validates all of them strictly.

### Change package name

Renames the configurator to `com.swiftkey.swiftkeyconfigurator.morphe` - the manifest package, the
`READCONFIG` permission definition and the ConfigProvider authority (install conflicts), and the
UriMatcher authority entries in code (they match incoming content URIs, so they must follow the
manifest authority). A renamed configurator can no longer serve the keyboard, because the keyboard
hardcodes the original authority and package name; apply it only when you need the renamed build
to coexist with a stock one and do not rely on the factory settings pairing.

## Building

The build is self-contained - no GitHub Packages authentication and no Android SDK required:

```bash
./gradlew :patches:buildAndroid generatePatchesList
```

The Morphe Gradle plugin (`app.morphe.patches` 1.3.2) and the patcher library
(`app.morphe:morphe-patcher` 1.5.2) are vendored in [`vendor/maven-repo`](vendor/maven-repo)
(rebuilt from the tagged sources of their upstream repositories) so CI works with a plain
`GITHUB_TOKEN` or no credentials at all.

The runtime half of the *Debloat*, *Remove GIFs and translator* and *Add blank toolbar elements*
patches lives in [`extensions/swiftkey-src`](extensions/swiftkey-src) (plain Java against framework
APIs and compile-only stubs of the R8-renamed app classes). It is compiled with `javac` and dexed
with `d8` - no Android SDK needed - into `patches/src/main/resources/extensions/swiftkey.mpe`,
which the patches merge into the app with `extendWith`. To rebuild it after changing the sources:

```bash
extensions/swiftkey-src/build-extension.sh <path-to-r8.jar> <path-to-android.jar>
```

The *GIF source* patch has its own extension,
[`extensions/swiftkey-klipy-src`](extensions/swiftkey-klipy-src) (`swiftkey-klipy.mpe`), rebuilt the
same way.

## License

See [LICENSE](LICENSE) and [NOTICE](NOTICE).
