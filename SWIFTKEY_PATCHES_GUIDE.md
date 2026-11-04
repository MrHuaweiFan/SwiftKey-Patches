# SwiftKey Patches — technical guide

> **Audience:** maintainers and AI agents building, extending or debugging this repository.
> It documents how each patch works, which fingerprints and resources it depends on, how the
> factory settings pairing works and how to build the bundle and its extensions.
> User-facing overview: [README.md](README.md).
>
> **Ground rule:** every constant and fingerprint below was verified against the pinned APKs.
> When a new version contradicts this document, trust the new evidence and update the doc.

## Contents

- [Project map](#project-map)
- [Pinned versions](#pinned-versions)
- [SwiftKey keyboard patches](#swiftkey-keyboard-patches)
- [SwiftKey factory settings patches](#swiftkey-factory-settings-patches)
- [Building](#building)
- [Extensions](#extensions)
- [License](#license)

## Project map

```
.github/                  CI workflows
extensions/
  swiftkey-src/           runtime Java for Debloat, Remove GIFs and translator, Add blank toolbar elements
  swiftkey-klipy-src/     runtime Java for GIF source (KLIPY client)
gradle/                   Gradle wrapper files
patches/                  the patch sources and the bundle build
  src/main/resources/extensions/swiftkey.mpe         built SwiftKey runtime extension
  src/main/resources/extensions/swiftkey-klipy.mpe   built KLIPY extension
scripts/                  Java dump/verify helpers and .mpp assembly scripts
vendor/maven-repo/        vendored Morphe Gradle plugin and patcher library
patches-bundle.json       bundle metadata for Morphe
patches-list.json         patch list metadata
.releaserc                release configuration
```

## Pinned versions

| App | Package | Pinned version |
| :--- | :--- | :--- |
| Microsoft SwiftKey | `com.touchtype.swiftkey` | 9.12.13.13 |
| SwiftKey factory settings | `com.swiftkey.swiftkeyconfigurator` | 2.2.0.314 |

Fingerprints depend on R8-renamed class names, which change between releases. The names quoted in
this guide are valid for the pinned versions only; re-verify them when bumping.

## SwiftKey keyboard patches

Default state: all enabled except **Remove GIFs and translator**.

| Patch | Default | Summary |
| :--- | :---: | :--- |
| Add blank toolbar elements | on | 1–6 blank, reorderable toolbar items |
| Change package name | on | Renames to `com.touchtype.swiftkey.morphe` |
| Debloat | on | Removes non-keyboard bloat from the item model |
| Disable cloud sign-in prompt | on | Keeps the Microsoft sign-in onboarding from hijacking the launcher |
| Disable telemetry | on | Drops telemetry records and uploads |
| GIF source | on | Tenor → KLIPY |
| Remove GIFs and translator | off | Removes GIF and Translator toolbar features |

### Add blank toolbar elements

Adds blank, transparent toolbar buttons of the same size as the regular buttons, labeled
Blank 1 to Blank 6. They are real catalog items: they appear in the customizer's reorder screen
and can be dragged, reordered and removed like any other item, and they do nothing when tapped.

- Patch option **Blank elements** chooses how many are added: 1 to 6, default 6.
- The chosen count is written to the `morphe_blank_count` integer resource, which the extension
  helper of the catalog rewrite reads at runtime.

### Change package name

Renames the keyboard to `com.touchtype.swiftkey.morphe` so the patched build installs next to
stock SwiftKey.

Renaming the keyboard's own package does not break any of the three channels to the factory
settings app (content authority, package visibility entry, `READCONFIG` permission) — see
[Compatible with .morphe SwiftKey](#compatible-with-morphe-swiftkey).

### Debloat

Removes the non-keyboard bloat from the **root of the item model**, so the removed entries are
gone from the strip, the toolgrid, the customizer and saved orderings alike.

- **Toolbar:** Rewards, Stickers, sign-in, Rich media, Search, Share, Copilot (writing assistant),
  Create and Tips, plus the two sticker pages of the rich content panel.
- **Settings:** the Account section, Microsoft Rewards, the Bing/Copilot search settings, the online
  theme Gallery tab (Themes starts on "Your themes") and the AI achievements section.
- **Smart clip:** when text is copied, the ribbon above the suggestion strip shows the copied-text
  pill with a round search button (and sometimes a second action chip) left of it. The patch
  neutralizes the chip factories, so only the pill remains, and the ribbon builder centers the
  pill between the parent edges on its own. Crash-free: the builders null-check every chip.

Typing, clipboard, themes and the rest of the keyboard stay unchanged and functional.

### Disable cloud sign-in prompt

Keeps the Microsoft cloud sign-in onboarding from hijacking the launcher.

### Disable telemetry

The original hoomans-morphe patch, restored and verified against SwiftKey 9.12.13.13:

- Records are dropped before any telemetry sink can persist or upload them.
- Both PaperBoy upload loops return without uploading.
- Adjust is never initialized.
- Crashlytics/Sessions collection is turned off by meta-data.
- The legacy Google Analytics components and Adjust's lifecycle provider are disabled in the manifest.
- The exception reporter is turned off by resource flag.
- Push messaging and the multipurpose job service stay enabled.

**Fingerprint pins (9.12.13.13):**

| Target | Pin |
| :--- | :--- |
| PaperBoy processor | `Lqs/g;`, pinned by its `paperBoyConfig` string |
| `kotlin.Unit` | `Lsr/D;` |

The 9.13.13.5 pins of the upstream patch (`LogSender`, `Lql0/h0;`) do not exist in this build.

### GIF source

Google shut the Tenor API down on 30 June 2026, so the GIF panel of the unpatched 9.12.13.13
cannot load anything anymore. This patch swaps the injected Tenor Retrofit service for an
implementation that speaks KLIPY's Tenor-compatible v2 API, the same backend Microsoft switched
the newer SwiftKey versions to.

- Create a free KLIPY app key at klipy.com (Partner Panel → API Keys) and enter it in the patch option.
- Without a key, every search returns an empty result instead of an error.
- Runtime code lives in `extensions/swiftkey-klipy-src` (`swiftkey-klipy.mpe`).

### Remove GIFs and translator

Removes the GIF feature (toolbar item `0x04` and the GIF page of the rich content panel) and the
Translator toolbar item (`0x0a`, the one that binds the external Microsoft Translator app) from the
root of the toolbar item model, the same way Debloat removes its bloat items. The GIF panel and
button are gone everywhere: strip, toolgrid, customizer and saved orderings.

Combine with **GIF source** at your own discretion: with the GIF panel gone, the GIF backend is
never reached.

## SwiftKey factory settings patches

The "SwiftKey factory settings" app (`com.swiftkey.swiftkeyconfigurator` 2.2.0.314) serves
SwiftKey's factory configuration from its bundled database through an **exported content
provider**. The keyboard reaches it through three fixed channels:

1. The content authority `com.swiftkey.swiftkeyconfigurator`, hardcoded in the keyboard's content URIs.
2. A package visibility entry in the keyboard's manifest.
3. The signature-scoped `READCONFIG` permission.

| Patch | Default | What it does |
| :--- | :---: | :--- |
| Compatible with .morphe SwiftKey | on | Keeps the factory settings link intact; re-signs it with your Morphe key |
| Change package name | off | Appends `.morphe` so it installs next to the stock app; breaks the keyboard link |

### Compatible with .morphe SwiftKey

Renaming the keyboard's own package breaks none of the three channels. What breaks the pairing is
the **signature**: `READCONFIG` is granted only to apps signed like the app that defines it, and a
Morphe-patched keyboard is signed with your Morphe key, which never matches the stock factory
settings app's original certificate.

Running the factory settings app through Morphe with this patch re-signs it with the same Morphe
key your .morphe SwiftKey was signed with, and that shared signature is exactly what `READCONFIG`
checks. The patch changes nothing else about the app: its package, permission and provider
authority stay exactly as the keyboard expects them, and the patch validates all of them strictly.

### Change package name

Renames the configurator to `com.swiftkey.swiftkeyconfigurator.morphe`. It rewrites:

- the manifest package,
- the `READCONFIG` permission definition and the ConfigProvider authority (install conflicts),
- the `UriMatcher` authority entries in code (they match incoming content URIs, so they must
  follow the manifest authority).

A renamed configurator can no longer serve the keyboard, because the keyboard hardcodes the
original authority and package name. Apply it only when you need the renamed build to coexist
with a stock one and do not rely on the factory settings pairing.

## Building

The build is self-contained: no GitHub Packages authentication and no Android SDK required.

```bash
./gradlew :patches:buildAndroid generatePatchesList
```

The Morphe Gradle plugin (`app.morphe.patches` 1.3.2) and the patcher library
(`app.morphe:morphe-patcher` 1.5.2) are vendored in [`vendor/maven-repo`](vendor/maven-repo),
rebuilt from the tagged sources of their upstream repositories, so CI works with a plain
`GITHUB_TOKEN` or no credentials at all.

## Extensions

Two extension bundles carry the runtime half of the patches. Both are merged into the app by the
patches with `extendWith`.

| Extension | Source | Used by | Output |
| :--- | :--- | :--- | :--- |
| SwiftKey runtime | [`extensions/swiftkey-src`](extensions/swiftkey-src) | Debloat, Remove GIFs and translator, Add blank toolbar elements | `patches/src/main/resources/extensions/swiftkey.mpe` |
| KLIPY client | [`extensions/swiftkey-klipy-src`](extensions/swiftkey-klipy-src) | GIF source | `patches/src/main/resources/extensions/swiftkey-klipy.mpe` |

The SwiftKey runtime is plain Java written against framework APIs and compile-only stubs of the
R8-renamed app classes. It is compiled with `javac` and dexed with `d8`, so no Android SDK is needed.

To rebuild it after changing the sources:

```bash
extensions/swiftkey-src/build-extension.sh <path-to-r8.jar> <path-to-android.jar>
```

The KLIPY extension is rebuilt the same way from `extensions/swiftkey-klipy-src`.

## License

GPL-3.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
