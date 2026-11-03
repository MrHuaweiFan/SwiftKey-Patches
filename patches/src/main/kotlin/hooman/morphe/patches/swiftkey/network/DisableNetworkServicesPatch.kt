package hooman.morphe.patches.swiftkey.network

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import hooman.morphe.patches.swiftkey.swiftKeyCompatibility
import org.w3c.dom.Element

// SwiftKey enqueues these two services through androidx JobIntentService/JobScheduler at runtime
// (JobScheduler.enqueue validates the component and THROWS "No such service" if the component is
// disabled or missing, crashing the app). They must stay declared and enabled in the manifest and
// are neutralized at their static enqueue entry instead: the job is never enqueued, so the service
// never runs. Each class has exactly one static void method that calls
// androidx.core.app.JobIntentService.b (the enqueueWork bridge), which pins it without relying on
// R8-renamed parameter types.
internal object TelemetryJobIntentServiceEnqueueFingerprint : Fingerprint(
    definingClass = "Lcom/touchtype/telemetry/TelemetryJobIntentService;",
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = "Landroidx/core/app/JobIntentService;",
            name = "b",
        ),
    ),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

internal object SyncServiceEnqueueFingerprint : Fingerprint(
    definingClass = "Lcom/touchtype/cloud/sync/SyncService;",
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = "Landroidx/core/app/JobIntentService;",
            name = "b",
        ),
    ),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * The second, softer way to remove internet access: the app keeps its INTERNET permission, but every
 * component SwiftKey can use to start network activity on its own is shut down.
 *
 * Two different mechanisms are needed to keep the app crash-free:
 *
 *  - JobScheduler-enqueued services (telemetry job service, cloud sync service) must remain declared
 *    and enabled in the manifest, because JobScheduler.enqueue throws IllegalArgumentException
 *    ("No such service") for disabled components while SwiftKey's startup path enqueues them.
 *    Their static enqueue entry points are turned into no-ops instead, so nothing can ever
 *    schedule them again.
 *
 *  - Everything that is only started externally (Google Play Services bindings, other Microsoft
 *    apps' token sharing) or internally through bindService/broadcast (which fail silently for
 *    disabled components) is disabled directly in the manifest.
 */
private val disableNetworkServicesResourcesPatch = resourcePatch(
    description = "Disables SwiftKey's externally-bound and broadcast-started network components. " +
        "Applied automatically with Disable network services.",
) {
    compatibleWith(swiftKeyCompatibility)

    execute {
        document("AndroidManifest.xml").use { document ->
            fun disableComponent(tag: String, name: String) {
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

            // Bound by Google Play Services (FCM push), never by SwiftKey itself.
            disableComponent("service", "com.touchtype.cloudmessaging.SwiftKeyFirebaseMessagingService")

            // Started through Context.bindService, which returns false for a disabled component;
            // SwiftKey's telemetry manager handles that gracefully.
            disableComponent("service", "com.touchtype.telemetry.TelemetryService")

            // Not referenced by any code in this build.
            disableComponent("service", "net.swiftkey.androidlibs.paperboy.PaperBoyAvroService")

            // Bound by other Microsoft apps for account token sharing.
            disableComponent("service", "com.microsoft.tokenshare.TokenSharingService")

            // Broadcast receivers: broadcasts to a disabled receiver are dropped by the system
            // without throwing, and the pending intents used to schedule them keep working.
            disableComponent("receiver", "com.google.firebase.iid.FirebaseInstanceIdReceiver")
            disableComponent("receiver", "com.microsoft.tokenshare.AccountChangeReceiver")
            disableComponent("receiver", "com.touchtype.cloud.sync.SyncScheduledJob")
        }
    }
}

@Suppress("unused")
val disableNetworkServicesPatch = bytecodePatch(
    name = "Disable network services",
    description = "Disables SwiftKey's own network-facing components (telemetry, PaperBoy uploads, " +
        "cloud backup and sync, FCM push messaging and Microsoft token sharing) while keeping the " +
        "INTERNET permission itself. Job-enqueued services are neutralized in code instead of the " +
        "manifest, so disabling them can never crash the app; the keyboard and its settings keep " +
        "working.",
) {
    compatibleWith(swiftKeyCompatibility)
    dependsOn(disableNetworkServicesResourcesPatch)

    execute {
        // The enqueue entry points return void and are safe to cut before they build the work intent.
        // The services stay declared and enabled, so JobScheduler.enqueue can never throw for them,
        // but no job is ever enqueued either, so they never actually run.
        TelemetryJobIntentServiceEnqueueFingerprint.method.addInstructions(0, "return-void")
        SyncServiceEnqueueFingerprint.method.addInstructions(0, "return-void")
    }
}
