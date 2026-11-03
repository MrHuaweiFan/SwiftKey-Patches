package hooman.morphe.patches.swiftkey.network

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import hooman.morphe.patches.swiftkey.swiftKeyCompatibility
import org.w3c.dom.Element

/**
 * The second, softer way to remove internet access: the app keeps its INTERNET permission (so
 * WebViews the user opens on purpose can still load), but every component SwiftKey uses to wake
 * itself up and talk to the network on its own is disabled in the manifest: the telemetry services
 * and PaperBoy upload service, cloud backup and sync, the FCM push messaging pipeline, and the
 * Microsoft account token sharing components. Nothing of the app's own can start a network request
 * anymore while the keyboard itself stays completely functional.
 */
@Suppress("unused")
val disableNetworkServicesPatch = resourcePatch(
    name = "Disable network services",
    description = "Disables SwiftKey's own network-facing manifest components (telemetry services, " +
        "PaperBoy uploads, cloud backup and sync, FCM push messaging and Microsoft token sharing) " +
        "while keeping the INTERNET permission itself, so the app can no longer start network " +
        "activity on its own. The keyboard and its settings keep working.",
) {
    compatibleWith(swiftKeyCompatibility)

    execute {
        document("AndroidManifest.xml").use { document ->
            // Component tag -> manifest component name. Every entry must exist exactly once.
            val targets = mapOf(
                "service" to listOf(
                    "com.touchtype.telemetry.TelemetryService",
                    "com.touchtype.telemetry.TelemetryJobIntentService",
                    "net.swiftkey.androidlibs.paperboy.PaperBoyAvroService",
                    "com.touchtype.cloudmessaging.SwiftKeyFirebaseMessagingService",
                    "com.touchtype.cloud.sync.SyncService",
                    "com.microsoft.tokenshare.TokenSharingService",
                ),
                "receiver" to listOf(
                    "com.touchtype.cloud.sync.SyncScheduledJob",
                    "com.google.firebase.iid.FirebaseInstanceIdReceiver",
                    "com.microsoft.tokenshare.AccountChangeReceiver",
                ),
            )

            for ((tag, names) in targets) {
                for (name in names) {
                    val matches = document.getElementsByTagName(tag)
                        .let { nodes ->
                            (0 until nodes.length)
                                .mapNotNull { nodes.item(it) as? Element }
                                .filter { it.getAttribute("android:name") == name }
                        }
                    val component = matches.singleOrNull() ?: throw PatchException(
                        "SwiftKey: $tag $name was not found uniquely in the manifest. The manifest " +
                            "changed for this version.",
                    )
                    component.setAttribute("android:enabled", "false")
                }
            }
        }
    }
}
