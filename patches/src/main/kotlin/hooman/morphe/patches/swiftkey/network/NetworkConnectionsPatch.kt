package hooman.morphe.patches.swiftkey.network

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import hooman.morphe.patches.swiftkey.swiftKeyCompatibility
import org.w3c.dom.Element

// The action that opens the injected Network connections settings screen. An action-based intent
// (instead of an explicit targetPackage) keeps working even when the "Change package name" patch
// renames the app.
private const val SETTINGS_ACTION = "com.morphe.swiftkey.OPEN_NETWORK_CONNECTIONS"

private const val SETTINGS_ACTIVITY = "com.morphe.swiftkey.network.MorpheNetworkSettingsActivity"
private const val GUARD_CLASS = "Lcom/morphe/swiftkey/network/NetworkGuard;"

// The R8-renamed kotlin.Unit singleton of SwiftKey 9.12.13.13. Both suspend upload methods return
// it on completion, so the neutralized methods return it as well.
private const val UNIT_SINGLETON = "Lsr/D;->a:Lsr/D;"

// The bundled OkHttp stack is fully R8-renamed in 9.12.13.13. Lys/e; is its RealConnection and its
// connect method is the ONLY method of the whole app that creates a java.net.InetSocketAddress,
// which pins it without relying on any obfuscated name.
internal object OkHttpConnectFingerprint : Fingerprint(
    returnType = "Lys/k;",
    filters = listOf(methodCall(definingClass = "Ljava/net/InetSocketAddress;", name = "<init>")),
    custom = { method, _ ->
        val parameters = method.parameterTypes.map { it.toString() }
        parameters.size == 5 &&
            parameters.take(3).all { it == "I" } &&
            parameters.drop(3).all { it == "Z" }
    },
)

// SwiftKey enqueues the telemetry job service through androidx JobIntentService at runtime
// (JobScheduler.enqueue validates the component and THROWS "No such service" if the component is
// disabled or missing, crashing the app). It must stay declared and enabled in the manifest and
// is neutralized at its static enqueue entry instead: the job is never enqueued, so the service
// never runs. The class has exactly one static void method that calls
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

// The cloud sync service of 9.12.13.13 is enqueued through the generic job scheduler wrapper
// (LNj/f;->b(Class, I, String, Bundle)) from its single static void enqueue entry, so that entry
// point is pinned and turned into a no-op.
internal object SyncServiceEnqueueFingerprint : Fingerprint(
    definingClass = "Lcom/touchtype/cloud/sync/SyncService;",
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = "LNj/f;",
            name = "b",
        ),
    ),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

// SwiftKey's first-party analytics records all pass through
// Lcom/touchtype/telemetry/handlers/l;->send(GenericRecord), which fans each Avro record out to
// the configured telemetry sinks (the Lgq/f; interface). The class keeps its name in this build
// and is pinned by its (GenericRecord)V method plus the abstract class's Set constructor and
// no-arg abstract lifecycle method.
internal object TelemetryRecordDispatchFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lorg/apache/avro/generic/GenericRecord;"),
    filters = listOf(
        methodCall(
            definingClass = "Lgq/f;",
            name = "a",
        ),
    ),
    custom = { _, classDef ->
        AccessFlags.ABSTRACT.isSet(classDef.accessFlags) &&
            classDef.methods.any { method ->
                method.name == "<init>" &&
                    method.parameterTypes.map { it.toString() } == listOf("Ljava/util/Set;")
            } &&
            classDef.methods.any { method ->
                AccessFlags.ABSTRACT.isSet(method.accessFlags) &&
                    method.returnType == "V" &&
                    method.parameterTypes.isEmpty()
            }
    },
)

// The PaperBoy record processor of 9.12.13.13 (Lqs/g;). It owns the paperboy configuration string
// and the three suspend methods around record uploads; pinned by the configuration string plus
// its (GenericRecord, Continuation) coroutine method.
private val LOG_SENDER_CLASS = Fingerprint(
    strings = listOf("paperBoyConfig"),
    custom = { _, classDef ->
        classDef.methods.any { method ->
            method.returnType == "Ljava/lang/Object;" &&
                method.parameterTypes.map { it.toString() } ==
                listOf("Lorg/apache/avro/generic/GenericRecord;", "Lyr/c;")
        }
    },
)

// Primary PaperBoy upload loop (Lqs/g;->a(Z, Continuation) in this build). It drains queued
// records and uploads them; the continuation parameter class is pinned by the record enqueue
// method of the same class. No access flags are declared: the patcher compares access flags for
// exact equality, and the method is public final.
internal object PrimaryTelemetryUploadFingerprint : Fingerprint(
    classFingerprint = LOG_SENDER_CLASS,
    returnType = "Ljava/lang/Object;",
    custom = { method, _ ->
        val parameters = method.parameterTypes.map { it.toString() }
        parameters.size == 2 && parameters[0] == "Z" && parameters[1] == "Lyr/c;"
    },
)

// Secondary PaperBoy uploader in the same class. Patch it independently so no direct caller can
// reach the fallback endpoint after the primary loop has been disabled.
internal object SecondaryTelemetryUploadFingerprint : Fingerprint(
    classFingerprint = LOG_SENDER_CLASS,
    returnType = "Ljava/lang/Object;",
    custom = { method, _ ->
        method.parameterTypes.map { it.toString() } == listOf("Lyr/c;")
    },
)

// FullApplicationDelegate initializes Adjust from the app-specific token. Pinning the token and the
// SDK call selects the exact startup method without relying on its R8 class or method names.
internal object AdjustInitializationFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("f30sdsr9mha8"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/adjust/sdk/Adjust;",
            name = "initSdk",
        ),
    ),
)


/**
 * The resource half of the merged "Network connections" patch:
 *
 *  1. A native SwiftKey settings screen ("Network connections" right after "Privacy" on the home
 *     screen) with one switch per remaining online feature - GIF search, the translator
 *     and language/model downloads. The screen is hosted by an activity that extends the app's own
 *     TrackedContainerActivity and a fragment that extends the app's own SwiftKeyPreferenceFragment,
 *     and its switches are the app's own TrackedMaterialSwitchPreference entries, so the UI is
 *     pixel-identical to the built-in settings. The switches are persistent and store their state
 *     in the app's default shared preferences, which is exactly what the runtime NetworkGuard reads.
 *  2. The root-level network shutdown in the manifest: every component that can start network
 *     activity on its own is disabled, and Firebase Crashlytics/Sessions collection is turned off
 *     by meta-data. Job-enqueued services must stay declared and enabled (JobScheduler.enqueue
 *     throws for disabled components), so they are neutralized in code by the bytecode half.
 *  3. The telemetry collection shutdown of the former "Disable telemetry" patch: the legacy
 *     Google Analytics components and Adjust's lifecycle provider are disabled in the manifest and
 *     SwiftKey's exception reporter is turned off by resource flag, so no crash, session,
 *     attribution or legacy analytics record is ever collected or sent.
 */
private val networkConnectionsResourcesPatch = resourcePatch(
    description = "Adds the native Network connections settings screen, disables SwiftKey's " +
        "network-facing components and turns telemetry collection off. Applied automatically " +
        "with Network connections.",
) {
    compatibleWith(swiftKeyCompatibility)

    execute {
        // ---- 1. Register the settings screen in the manifest -------------------------------
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application")
                .let { nodes -> if (nodes.length == 1) nodes.item(0) as? Element else null }
                ?: throw PatchException("SwiftKey: expected exactly one application element.")

            fun elements(tag: String): List<Element> =
                document.getElementsByTagName(tag)
                    .let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }

            fun disableComponent(tag: String, name: String) {
                val component = elements(tag).filter { it.getAttribute("android:name") == name }
                    .singleOrNull() ?: throw PatchException(
                    "SwiftKey: $tag $name was not found uniquely in the manifest. The manifest " +
                        "changed for this version.",
                )
                component.setAttribute("android:enabled", "false")
            }

            val exists = elements("activity").any {
                it.getAttribute("android:name") == SETTINGS_ACTIVITY
            }
            if (!exists) {
                val activity = document.createElement("activity").apply {
                    setAttribute("android:name", SETTINGS_ACTIVITY)
                    setAttribute("android:exported", "false")
                    setAttribute("android:label", "@string/morphe_network_title")
                    setAttribute("android:theme", "@style/ContainerTheme")
                    setAttribute(
                        "android:parentActivityName",
                        "com.touchtype.materialsettingsx.NavigationActivity",
                    )
                }
                val intentFilter = document.createElement("intent-filter").apply {
                    appendChild(document.createElement("action").apply {
                        setAttribute("android:name", SETTINGS_ACTION)
                    })
                    appendChild(document.createElement("category").apply {
                        setAttribute("android:name", "android.intent.category.DEFAULT")
                    })
                }
                activity.appendChild(intentFilter)
                application.appendChild(activity)
            }

            // ---- 2. Root-cut the components that start network activity on their own -------
            // Bound by Google Play Services (FCM push), never by SwiftKey itself.
            disableComponent("service", "com.touchtype.cloudmessaging.SwiftKeyFirebaseMessagingService")

            // 1.2.2: FirebaseInitProvider must STAY ENABLED. Disabling it (the 1.2.1 approach)
            // keeps FirebaseApp uninitialized, and SwiftKey's own exception reporter
            // (LXi/a;->a -> LE5/p;->w -> FirebaseApp.getInstance) then kills the process with
            // "Default FirebaseApp is not initialized in this process" on every pool thread that
            // reports any error - a constant crash loop, verified against the LIO-L29 bugreport
            // (16 fatal crashes within 3 minutes of the 07:54 install). Firebase is instead cut
            // off at the network layer: every Firebase host is refused by the runtime guard, and
            // the guard's dead-end redirect turns the Installations id fetch into an instant
            // local ConnectException, which Firebase handles exactly like a missing network
            // ("Firebase Installations Service is unavailable. Please try again later." - logged,
            // not thrown). The blocking-thread crash of the 1.1.0 bundle was caused by the guard
            // THROWING at the URL.openConnection() call site, which the 1.2.x redirect no longer
            // does - so Firebase can stay initialized without any crash risk, while its
            // collection flags below keep Analytics, Crashlytics, Sessions and Performance off.

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

            // Legacy Google Analytics components (separate from Firebase Messaging; disabling
            // them does not remove SwiftKey's FCM listener or Firebase installation support).
            disableComponent("receiver", "com.google.android.gms.analytics.AnalyticsReceiver")
            disableComponent("service", "com.google.android.gms.analytics.AnalyticsService")
            disableComponent("service", "com.google.android.gms.analytics.AnalyticsJobService")

            // Adjust's automatic lifecycle startup; the bytecode half also removes the single
            // Adjust.initSdk call of FullApplicationDelegate.
            disableComponent("provider", "com.adjust.sdk.SystemLifecycleContentProvider")

            // ---- 3. Turn Firebase collection off at the root -------------------------------
            fun setMetaData(name: String, value: String) {
                val matches = elements("meta-data").filter {
                    it.getAttribute("android:name") == name
                }
                if (matches.size > 1) {
                    throw PatchException(
                        "SwiftKey: manifest has ${matches.size} $name meta-data entries; refusing " +
                            "to choose one.",
                    )
                }
                val element = matches.singleOrNull() ?: document.createElement("meta-data").also {
                    it.setAttribute("android:name", name)
                    application.appendChild(it)
                }
                element.setAttribute("android:value", value)
            }

            setMetaData("firebase_analytics_collection_enabled", "false")
            setMetaData("firebase_crashlytics_collection_enabled", "false")
            setMetaData("firebase_sessions_enabled", "false")
            setMetaData("firebase_performance_collection_enabled", "false")
            setMetaData("google_analytics_default_allow_analytics_personalization_signals", "false")
        }

        // ---- 4. Turn SwiftKey's own exception reporter off ---------------------------------
        document("res/values/bools.xml").use { document ->
            val matches = document.getElementsByTagName("bool")
                .let { nodes ->
                    (0 until nodes.length)
                        .mapNotNull { nodes.item(it) as? Element }
                        .filter { it.getAttribute("name") == "exceptions_report_enabled" }
                }
            val exceptionsEnabled = matches.singleOrNull() ?: throw PatchException(
                "SwiftKey: exceptions_report_enabled was not found uniquely. The exception reporter " +
                    "resource changed for this version.",
            )
            exceptionsEnabled.textContent = "false"
        }

        // ---- 5. Add the home screen entry right after the Privacy entry ---------------------
        document("res/xml/prefsx_home_screen.xml").use { document ->
            val preferences = document.getElementsByTagName("Preference")
            var privacyEntry: Element? = null
            (0 until preferences.length).forEach { index ->
                val element = preferences.item(index) as? Element
                if (element?.getAttribute("android:key") == "@string/pref_home_launch_privacy_prefs") {
                    privacyEntry = element
                }
            }
            val anchor = privacyEntry ?: throw PatchException(
                "SwiftKey: the Privacy entry was not found in prefsx_home_screen.xml. The settings " +
                    "home screen changed for this version.",
            )

            val entry = document.createElement("Preference").apply {
                setAttribute("android:icon", "@drawable/ic_home_privacy")
                setAttribute("android:title", "@string/morphe_network_title")
                setAttribute("android:summary", "@string/morphe_network_summary")
                setAttribute("android:key", "@string/morphe_network_prefs_key")
            }
            entry.appendChild(document.createElement("intent").apply {
                setAttribute("android:action", SETTINGS_ACTION)
            })
            anchor.parentNode.insertBefore(entry, anchor.nextSibling)
        }

        // ---- 6. The settings screen itself ----------------------------------------------------
        // New resource files are written directly (document() is for editing existing ones).
        // 1.2.3: the header ("Allowed connections") and footer explanation preferences are gone -
        // the screen is just the three switches, exactly like the built-in feature screens.
        val screenXml = this["res/xml/prefsx_morphe_network.xml"]
        screenXml.parentFile?.mkdirs()
        screenXml.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- Network connections screen added by Morphe. The switches are the app's own
                 TrackedMaterialSwitchPreference entries, so they render and behave exactly like
                 the built-in settings switches, and they persist to the app's default shared
                 preferences, which the NetworkGuard reads on every connection attempt. -->
            <PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto">

                <com.touchtype.materialsettingsx.custompreferences.TrackedMaterialSwitchPreference
                    android:persistent="true"
                    android:title="@string/morphe_network_gif_title"
                    android:key="morphe_network_gif"
                    android:summary="@string/morphe_network_gif_summary"
                    android:defaultValue="true" />

                <com.touchtype.materialsettingsx.custompreferences.TrackedMaterialSwitchPreference
                    android:persistent="true"
                    android:title="@string/morphe_network_translator_title"
                    android:key="morphe_network_translator"
                    android:summary="@string/morphe_network_translator_summary"
                    android:defaultValue="true" />

                <com.touchtype.materialsettingsx.custompreferences.TrackedMaterialSwitchPreference
                    android:persistent="true"
                    android:title="@string/morphe_network_language_title"
                    android:key="morphe_network_language_downloads"
                    android:summary="@string/morphe_network_language_summary"
                    android:defaultValue="true" />
            </PreferenceScreen>
            """.trimIndent(),
        )

        // ---- 7. All strings of the section -----------------------------------------------------
        document("res/values/strings.xml").use { document ->
            val root = document.documentElement
            for ((name, value) in listOf(
                "morphe_network_title" to "Network connections",
                "morphe_network_summary" to
                    "Choose which online features SwiftKey is allowed to use",
                "morphe_network_prefs_key" to "morphe_network_prefs",
                "morphe_network_gif_title" to "GIF search",
                "morphe_network_gif_summary" to
                    "Search GIFs in the keyboard's GIF panel (api.klipy.com, or g.tenor.com when " +
                        "the Tenor service is used)",
                "morphe_network_translator_title" to "Translator",
                "morphe_network_translator_summary" to
                    "Translate with the Microsoft Translator app (requires the Microsoft Translator " +
                        "app to be installed)",
                "morphe_network_language_title" to "Language & model downloads",
                "morphe_network_language_summary" to
                    "Download languages and typing models in Settings (jenson.api.swiftkey.com)",
            )) {
                val exists = document.getElementsByTagName("string").let { nodes ->
                    (0 until nodes.length).any { index ->
                        (nodes.item(index) as? Element)?.getAttribute("name") == name
                    }
                }
                if (!exists) {
                    root.appendChild(
                        document.createElement("string").apply {
                            setAttribute("name", name)
                            textContent = value
                        },
                    )
                }
            }
        }
    }
}

/**
 * The merged network patch: everything SwiftKey does over the network in one place.
 *
 *  - Google, Firebase, Crashlytics, Adjust, Google Analytics, the experiment/AI/theme store and
 *    account APIs are cut off at the root: their manifest components are disabled by the resource
 *    half, their job services are no-oped in code, and the runtime guard refuses every connection
 *    to a host no remaining feature needs. There is no switch for them; they are gone.
 *
 *  - First-party telemetry is dead on arrival as well (the former "Disable telemetry" patch is
 *    merged into this one): records are dropped before they reach any sink, both upload loops
 *    return without uploading, Adjust is never initialized, and the legacy Analytics components
 *    and the exception reporter are disabled by the resource half.
 *
 *  - The three remaining online features keep working and each gets a native SwiftKey settings
 *    switch: GIF search (g.tenor.com and the config service its API key comes from), the
 *    translator (the external Microsoft Translator app binding) and language/model downloads
 *    (jenson.api.swiftkey.com). Turning a feature off blocks it everywhere - in the settings app,
 *    in the keyboard while only the keyboard is running, and in every background component -
 *    because the checks live at the connection setup and the service binding itself.
 *
 *  - The bundled OkHttp stack, every URL.openConnection() call site, every WebView page load and
 *    every service binding is routed through the injected NetworkGuard, so nothing can bypass the
 *    policy, no matter which obfuscated SDK path it takes.
 */
@Suppress("unused")
val networkConnectionsPatch = bytecodePatch(
    name = "Network connections",
    description = "Cuts SwiftKey's telemetry and tracking network access off at the root " +
        "(telemetry records and uploads, Firebase, Crashlytics, Adjust, Google Analytics, " +
        "experiments, the Copilot backend, theme store downloads, token sharing and account APIs - " +
        "without any switch, they can never connect) and adds a native Network connections section " +
        "to SwiftKey's settings with a switch for every remaining online feature: GIF search, the " +
        "translator and language/model downloads. The switches apply immediately everywhere - the " +
        "keyboard, the settings app and the background components - because the guard lives at the " +
        "connection setup itself, so it keeps working while only the keyboard is in use.",
) {
    compatibleWith(swiftKeyCompatibility)
    extendWith("extensions/swiftkey.mpe")
    dependsOn(networkConnectionsResourcesPatch)

    execute {
        // ---- 1. Neutralize the job-enqueued services in code --------------------------------
        //
        // The enqueue entry points return void and are safe to cut before they build the work
        // intent. The services stay declared and enabled in the manifest, so JobScheduler.enqueue
        // can never throw for them, but no job is ever enqueued either, so they never actually run.
        TelemetryJobIntentServiceEnqueueFingerprint.method.addInstructions(0, "return-void")
        SyncServiceEnqueueFingerprint.method.addInstructions(0, "return-void")

        // ---- 2. Kill the first-party telemetry at its sources --------------------------------
        //
        // Drop records before the telemetry sinks can persist or upload them, and make both
        // suspend upload loops of the PaperBoy processor return kotlin.Unit (Lsr/D; in this
        // build) without uploading anything. The upload methods reuse their Continuation
        // parameter registers rather than assuming spare local registers exist.
        TelemetryRecordDispatchFingerprint.method.addInstructions(0, "return-void")

        PrimaryTelemetryUploadFingerprint.method.addInstructions(
            0,
            """
                sget-object p2, $UNIT_SINGLETON
                return-object p2
            """,
        )
        SecondaryTelemetryUploadFingerprint.method.addInstructions(
            0,
            """
                sget-object p1, $UNIT_SINGLETON
                return-object p1
            """,
        )

        // The app initializes Adjust once from FullApplicationDelegate. Remove only that call; the
        // manifest dependency disables Adjust's automatic lifecycle startup as the second half.
        val adjustMethod = AdjustInitializationFingerprint.method
        val adjustCalls = adjustMethod.instructions.withIndex().filter { (_, instruction) ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.toString() ==
                "Lcom/adjust/sdk/Adjust;->initSdk(Lcom/adjust/sdk/AdjustConfig;)V"
        }.toList()
        if (adjustCalls.size != 1) {
            throw PatchException(
                "SwiftKey: expected exactly one Adjust.initSdk call in the token-pinned initializer, " +
                    "found ${adjustCalls.size}.",
            )
        }
        adjustMethod.replaceInstruction(adjustCalls.single().index, "nop")

        // ---- 3. Guard the bundled HTTP client at its connection setup ------------------------
        //
        // Every request, download and websocket of the keyboard and the settings app funnels
        // through the connect method of the bundled client. The host is read from the route's
        // address URL (Lys/e;->b -> Lus/a;->h -> Lus/v;->d). v0 is dead at the method entry.
        val connectMethod = OkHttpConnectFingerprint.method
        connectMethod.addInstructions(
            0,
            """
                iget-object v0, p0, Lys/e;->b:Lus/a;
                iget-object v0, v0, Lus/a;->h:Lus/v;
                iget-object v0, v0, Lus/v;->d:Ljava/lang/String;
                invoke-static {v0}, $GUARD_CLASS->checkConnect(Ljava/lang/String;)V
            """,
        )

        // ---- 4. Guard every URL.openConnection() call site ----------------------------------
        //
        // SDKs that use the platform HttpURLConnection (Adjust, Google Analytics, the image
        // pipeline, Microsoft token sharing, ...) connect through these call sites. Inserting a
        // checked(URL) call in front of openConnection reuses the URL register, so the patch works
        // at every call site shape.
        guardCallSites(
            this,
            OPEN_CONNECTION,
            "$GUARD_CLASS->checked(Ljava/net/URL;)Ljava/net/URL;",
        ) { instruction -> instruction.registerC }

        // ---- 5. Guard every WebView.loadUrl(...) call site ----------------------------------
        //
        // Web views (Bing auth, tips, clipboard cloud) fetch through their own engine; blocking
        // the page load itself keeps them from reaching hosts that are not allowed.
        guardCallSites(
            this,
            LOAD_URL,
            "$GUARD_CLASS->checkedUrl(Ljava/lang/String;)Ljava/lang/String;",
        ) { instruction -> instruction.registerD }

        guardCallSites(
            this,
            LOAD_URL_HEADERS,
            "$GUARD_CLASS->checkedUrl(Ljava/lang/String;)Ljava/lang/String;",
        ) { instruction -> instruction.registerD }

        // ---- 6. Guard every Context.bindService(...) call site -------------------------------
        //
        // The translator feature binds the external Microsoft Translator app. Routing the bind
        // intent through the guard lets the translator switch disable the binding; every other
        // service binding passes through untouched.
        guardCallSites(
            this,
            BIND_SERVICE,
            "$GUARD_CLASS->checkedBind(Landroid/content/Intent;)Landroid/content/Intent;",
        ) { instruction -> instruction.registerD }
    }
}

private const val OPEN_CONNECTION = "Ljava/net/URL;->openConnection()Ljava/net/URLConnection;"
private const val LOAD_URL = "Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V"
private const val LOAD_URL_HEADERS = "Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;Ljava/util/Map;)V"
private const val BIND_SERVICE =
    "Landroid/content/Context;->bindService(Landroid/content/Intent;Landroid/content/ServiceConnection;I)Z"

/** Smali for a single-register static call that works for both low and high registers. */
private fun staticCall(register: Int, descriptor: String): String {
    val invocation = if (register <= 15) {
        "invoke-static {v$register}, $descriptor"
    } else {
        "invoke-static/range {v$register}, $descriptor"
    }
    return """
        $invocation
        move-result-object v$register
    """
}

/**
 * Rewrite every call site of the given method reference so that the argument register is routed
 * through the guard first. The inserted static call returns its argument unchanged when the
 * connection is allowed, so the original call proceeds with the very same register.
 */
private fun guardCallSites(
    context: BytecodePatchContext,
    reference: String,
    guardDescriptor: String,
    registerSelector: (Instruction35c) -> Int,
) {
    context.classDefForEach { classDef: ClassDef ->
        // Check the immutable class first: converting a class to mutable marks it as modified,
        // which would rebuild every class of the app into the new DEX files for no reason.
        val hasCallSite = classDef.methods.any { method ->
            method.implementation?.instructions?.any { instruction ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    referenceOf(instruction) == reference &&
                    instruction is Instruction35c
            } == true
        }
        if (!hasCallSite) {
            return@classDefForEach
        }

        val mutableClass = context.mutableClassDefBy(classDef)
        for (method in mutableClass.methods) {
            val implementation = method.implementation ?: continue
            val callSites = implementation.instructions.withIndex()
                .filter { (_, instruction) ->
                    instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                        referenceOf(instruction) == reference &&
                        instruction is Instruction35c
                }
            if (callSites.isEmpty()) {
                continue
            }
            // Insert from last to first so the earlier indices stay valid.
            for ((index, instruction) in callSites.sortedByDescending { it.index }) {
                val register = registerSelector(instruction as Instruction35c)
                method.addInstructions(index, staticCall(register, guardDescriptor))
            }
        }
    }
}

private fun referenceOf(instruction: Instruction): String? =
    (instruction as? ReferenceInstruction)?.reference?.toString()
