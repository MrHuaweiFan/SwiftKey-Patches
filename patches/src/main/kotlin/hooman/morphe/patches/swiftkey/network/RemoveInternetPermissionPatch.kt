package hooman.morphe.patches.swiftkey.network

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import hooman.morphe.patches.swiftkey.swiftKeyCompatibility
import org.w3c.dom.Element

/**
 * Removes the INTERNET permission from the decoded AndroidManifest.xml. Without it the OS blocks
 * every socket the app could try to open, so SwiftKey becomes fully offline at the system level:
 * cloud backup and sync, theme gallery downloads, Bing/Copilot web panels, model and language pack
 * downloads, telemetry uploads and push messaging all stop working. Combine with "Debloat" and
 * "Disable telemetry" for a keyboard that never talks to the network.
 */
@Suppress("unused")
val removeInternetPermissionPatch = resourcePatch(
    name = "Remove INTERNET permission",
    description = "Removes the INTERNET permission from the manifest so the OS blocks all network " +
        "access for SwiftKey. The keyboard keeps working fully offline; cloud backup and sync, " +
        "theme gallery downloads, Bing/Copilot panels and push messaging stop working.",
) {
    compatibleWith(swiftKeyCompatibility)

    execute {
        document("AndroidManifest.xml").use { document ->
            val matches = document.getElementsByTagName("uses-permission")
                .let { nodes ->
                    (0 until nodes.length)
                        .mapNotNull { nodes.item(it) as? Element }
                        .filter { it.getAttribute("android:name") == "android.permission.INTERNET" }
                }
            val permission = matches.singleOrNull() ?: throw PatchException(
                "SwiftKey: the INTERNET uses-permission was not found uniquely in the manifest. " +
                    "The manifest changed for this version.",
            )
            permission.parentNode.removeChild(permission)
        }
    }
}
