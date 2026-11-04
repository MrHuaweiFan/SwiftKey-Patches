package hooman.morphe.patches.swiftkey.gif

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import hooman.morphe.patches.swiftkey.swiftKeyCompatibility
import org.w3c.dom.Element

// LHo/G;->a(Lus/B;Lus/v;Lrj/d;LHr/a;)LHo/H; - the factory that builds the Tenor GIF service of
// SwiftKey 9.12.13.13: it wires the app's renamed OkHttp client, the base URL from
// R.string 0x7f140a59 (https://g.tenor.com/) and the kotlinx.serialization converter into a
// renamed Retrofit builder (LLp/N; mode 14) and calls its create(Class) - the app's only
// Lr6/c;->k(Ljava/lang/Class;) call with a check-cast to LHo/H;. The factory is pinned by that
// shape without relying on the R8 names of its parameters.
internal object TenorServiceFactoryFingerprint : Fingerprint(
    definingClass = "LHo/G;",
    returnType = "LHo/H;",
    parameters = listOf("Lus/B;", "Lus/v;", "Lrj/d;", "LHr/a;"),
    strings = listOf("create(...)"),
    filters = listOf(methodCall(definingClass = "Lr6/c;", name = "k")),
)

/**
 * The bytecode half of the "GIF source" patch: the Tenor service factory is replaced with the
 * injected KLIPY implementation. The dead factory body stays behind the early return, exactly
 * like the neutralized telemetry methods of the Disable telemetry patch.
 */
private val klipyGifBytecodePatch = bytecodePatch(
    description = "Replaces the dead Tenor GIF service with KLIPY. Applied automatically with " +
        "GIF source.",
) {
    extendWith("extensions/swiftkey-klipy.mpe")

    execute {
        // new-instance v0, Lcom/morphe/swiftkey/klipy/MorpheKlipyService; ... return-object v0
        // The factory is static with four reference parameters, so v0 is always a free register
        // (it aliases p0, whose value is no longer needed).
        TenorServiceFactoryFingerprint.method.addInstructions(
            0,
            """
                new-instance v0, Lcom/morphe/swiftkey/klipy/MorpheKlipyService;
                invoke-direct {v0}, Lcom/morphe/swiftkey/klipy/MorpheKlipyService;-><init>()V
                return-object v0
            """,
        )
    }
}

/**
 * Replaces SwiftKey's GIF source with KLIPY, the service the new SwiftKey versions use.
 *
 * Google shut the Tenor API down on 30 June 2026, so the GIF panel of the unpatched
 * 9.12.13.13 cannot load anything anymore. This patch swaps the injected Tenor Retrofit
 * service for an implementation that speaks KLIPY's Tenor-compatible v2 API
 * (api.klipy.com/v2/search, /v2/featured, /v2/posts and /v2/registershare), the same
 * backend Microsoft switched the newer SwiftKey versions to. The keyboard's own GIF panel,
 * paging, recents row and share flow keep running unchanged; KLIPY's Tenor v2 shaped
 * responses are adapted to the models the app expects inside the injected service, and
 * old stored Tenor ids simply resolve to nothing (the app prunes them from the recents
 * row by itself).
 *
 * KLIPY requires an app key: create one for free in the KLIPY Partner Panel
 * (https://klipy.com - API Keys), enter it below, and the GIF panel works again. In
 * testing mode the key is limited to 100 requests per hour, which is plenty for a
 * keyboard. Without a key every search returns an empty result instead of an error.
 */
@Suppress("unused")
val klipyGifPatch = resourcePatch(
    name = "GIF source",
    description = "Replaces SwiftKey's dead Tenor GIF backend with KLIPY; needs a free KLIPY " +
        "API key (klipy.com).",
) {
    compatibleWith(swiftKeyCompatibility)

    val klipyApiKeyOption: Option<String> = stringOption(
        key = "klipyApiKey",
        default = "",
        title = "KLIPY API key",
        description = "Your KLIPY app key (create one for free at klipy.com, Partner Panel - " +
            "API Keys). Leave empty if you do not want GIF search.",
        required = false,
    ) {
        it == null || (it.length <= 128 && !it.contains('\n') && !it.contains('\r'))
    }

    dependsOn(klipyGifBytecodePatch)

    execute {
        val apiKey = klipyApiKeyOption.value?.trim() ?: ""
        document("res/values/strings.xml").use { document ->
            val root = document.documentElement
            val exists = document.getElementsByTagName("string").let { nodes ->
                (0 until nodes.length).any { index ->
                    (nodes.item(index) as? Element)?.getAttribute("name") == "morphe_klipy_api_key"
                }
            }
            if (!exists) {
                root.appendChild(
                    document.createElement("string").apply {
                        setAttribute("name", "morphe_klipy_api_key")
                        textContent = apiKey
                    },
                )
            } else {
                throw PatchException(
                    "SwiftKey: morphe_klipy_api_key already exists in strings.xml. The resource " +
                        "changed for this version.",
                )
            }
        }
    }
}
