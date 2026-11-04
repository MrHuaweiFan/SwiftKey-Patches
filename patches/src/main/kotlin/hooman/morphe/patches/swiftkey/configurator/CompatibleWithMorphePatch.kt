package hooman.morphe.patches.swiftkey.configurator

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

internal const val CONFIGURATOR_PACKAGE = "com.swiftkey.swiftkeyconfigurator"
internal const val CONFIGURATOR_PERMISSION = "com.swiftkey.swiftkeyconfigurator.READCONFIG"
internal const val CONFIGURATOR_AUTHORITY = "com.swiftkey.swiftkeyconfigurator"

/**
 * Makes the factory settings app work with the renamed (.morphe) SwiftKey.
 *
 * The keyboard talks to the configurator through three fixed channels: the content authority
 * com.swiftkey.swiftkeyconfigurator (hardcoded in the keyboard's content URIs), the package
 * visibility entry for com.swiftkey.swiftkeyconfigurator in the keyboard's manifest, and the
 * signature-scoped READCONFIG permission the configurator defines. Renaming the keyboard's own
 * package breaks none of them, so this patch deliberately changes NOTHING about the app: its
 * package name, its permission and its provider authority stay exactly as the keyboard expects
 * them.
 *
 * What actually breaks the pairing is the signature: READCONFIG is signature-scoped and granted
 * only to apps signed like the app that defines it. The stock configurator is signed with
 * SwiftKey's original certificate, so a Morphe-patched keyboard (signed with your Morphe key)
 * can never be granted it - every query is rejected with a SecurityException and the keyboard
 * silently falls back to no factory settings. Running the configurator through Morphe with this
 * patch re-signs it with the same Morphe key your .morphe SwiftKey was signed with, and that
 * shared signature is exactly what READCONFIG checks - the one piece that was missing.
 *
 * The patch itself validates the pairing invariants strictly (package, permission, exported
 * provider, authority) and fails loudly if a future configurator changes them, so a broken
 * pairing can never ship silently.
 */
@Suppress("unused")
val compatibleWithMorphePatch = resourcePatch(
    name = "Compatible with .morphe SwiftKey",
    description = "Keeps the factory settings link intact; re-signs it with your Morphe key.",
    default = true,
) {
    compatibleWith(swiftKeyConfiguratorCompatibility)

    execute {
        fun Element.androidName(): String = getAttribute("android:name")

        // The patcher's resource rebuild requires res/values/ids.xml. The factory settings app is
        // an old, id-less app (no layouts, no view ids) and never shipped one, so a minimal file
        // is created here; without it the rebuilt APK cannot be produced.
        val ids = this["res/values/ids.xml"]
        if (!ids.exists()) {
            ids.parentFile?.mkdirs()
            ids.writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <!-- Minimal ids.xml written by Morphe so the patcher can rebuild the app. -->
                <resources></resources>
                """.trimIndent(),
            )
        }

        document("AndroidManifest.xml").use { document ->
            val root = document.documentElement
            if (root.getAttribute("package") != CONFIGURATOR_PACKAGE) {
                throw PatchException(
                    "Configurator: the manifest package is ${root.getAttribute("package")}, " +
                        "expected $CONFIGURATOR_PACKAGE. The app changed or the package was " +
                        "already renamed.",
                )
            }

            // The signature permission the keyboard requests must stay defined under the name
            // the keyboard requests it by.
            val permissions = document.getElementsByTagName("permission")
                .let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
                .filter { it.androidName() == CONFIGURATOR_PERMISSION }
            val permission = permissions.singleOrNull() ?: throw PatchException(
                "Configurator: the $CONFIGURATOR_PERMISSION permission definition was not found " +
                    "uniquely. The manifest changed for this version.",
            )
            if (permission.getAttribute("android:protectionLevel") != "signature") {
                throw PatchException(
                    "Configurator: READCONFIG is no longer signature-scoped " +
                        "(found '${permission.getAttribute("android:protectionLevel")}').",
                )
            }

            // The provider must stay exported, must own the authority the keyboard's content URIs
            // use, and must stay gated by READCONFIG.
            val providers = document.getElementsByTagName("provider")
                .let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
                .filter { it.androidName() == "com.swiftkey.swiftkeyconfigurator.ConfigProvider" }
            val provider = providers.singleOrNull() ?: throw PatchException(
                "Configurator: the ConfigProvider was not found uniquely. The manifest changed " +
                    "for this version.",
            )
            if (provider.getAttribute("android:authorities") != CONFIGURATOR_AUTHORITY) {
                throw PatchException(
                    "Configurator: the ConfigProvider authority is " +
                        "'${provider.getAttribute("android:authorities")}', expected " +
                        "'$CONFIGURATOR_AUTHORITY'. The keyboard would not find the provider.",
                )
            }
            if (provider.getAttribute("android:exported") != "true") {
                throw PatchException("Configurator: the ConfigProvider is no longer exported.")
            }
            if (provider.getAttribute("android:permission") != CONFIGURATOR_PERMISSION) {
                throw PatchException(
                    "Configurator: the ConfigProvider is no longer gated by READCONFIG.",
                )
            }
        }
    }
}
