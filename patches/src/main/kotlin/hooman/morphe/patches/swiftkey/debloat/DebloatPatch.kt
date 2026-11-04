package hooman.morphe.patches.swiftkey.debloat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import hooman.morphe.patches.swiftkey.swiftKeyCompatibility
import org.w3c.dom.Element

// The merge that produces the rendered toolbar item list in the state observer. The merged
// ArrayList lands in register p1 of Lcom/touchtype/keyboard/toolbar/Toolbar;->e(...).
private const val TOOLBAR_MERGE =
    "Ltr/n;->b1(Ljava/lang/Iterable;Ljava/util/Collection;)Ljava/util/ArrayList;"

// The extension helper that filters the merged list. The injection is a single branch-free
// invoke-static: label-based inline smali cannot be relocated into the middle of a method, so
// all looping lives in the extension (MorpheToolbar.filterBloat).
private const val TOOLBAR_FILTER =
    "Lcom/morphe/swiftkey/toolbar/MorpheToolbar;->filterBloat(Ljava/util/ArrayList;)V"

// The extension helper that rewrites the catalog lists. Applied right after each of the three
// catalog lists is built in Lio/e;-><clinit>.
private const val TOOLBAR_WITHOUT_BLOAT =
    "Lcom/morphe/swiftkey/toolbar/MorpheToolbar;->withoutBloat(Ljava/util/List;)Ljava/util/List;"

// The catalog list builders of Lio/e;-><clinit>. The main list is built by Ltr/l;->i0, the two
// smaller groups by Ltr/o;->j0.
private const val CATALOG_MAIN_BUILD =
    "Ltr/l;->i0([Ljava/lang/Object;)Ljava/util/List;"
private const val CATALOG_GROUP_BUILD =
    "Ltr/o;->j0([Ljava/lang/Object;)Ljava/util/List;"

// The gate of the toolbar state builder that whitelists the catalog plus the account id 0x17
// (sign-in), and the forced insertion of the sign-in item into the strip's first page.
private const val ARRAY_LIST_ADD_OBJECT =
    "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z"
private const val ARRAY_LIST_ADD_AT =
    "Ljava/util/ArrayList;->add(ILjava/lang/Object;)V"

// The LazyColumn slot append of the achievements screen.
private const val LAZY_COLUMN_SLOT =
    "Lq0/g;->n(Lq0/g;Ljava/lang/String;LR0/d;I)V"

// The tab row slot append of the themes screen.
private const val TAB_ROW_SLOT =
    "LG0/m3;->b(IJJLHr/a;LJ0/o;LR0/d;LV0/n;ZZ)V"

// The themes tab enum: a = GALLERY_THEMES (online), b = YOUR_THEMES (offline).
private const val GALLERY_TAB = "Lpp/m;->a:Lpp/m;"
private const val YOUR_THEMES_TAB = "Lpp/m;->b:Lpp/m;"

/** The reference of an instruction as text, or null when it has none. */
private fun referenceOf(instruction: Instruction): String? =
    (instruction as? ReferenceInstruction)?.reference?.toString()

/**
 * Rewrites every catalog list built in Lio/e;-><clinit> through the extension helper, so the
 * bloat ids vanish from the root of the item model instead of only from the rendered strip
 * (which is all 1.1.0 did - the items stayed in the customizer and the saved orderings, and the
 * sign-in button even came back through the state builder's forced insertion).
 */
context(ctx: app.morphe.patcher.patch.BytecodePatchContext)
private fun rewriteCatalog() {
    val catalogMethod = ToolbarCatalogFingerprint.method
    val listBuilds = catalogMethod.instructions.withIndex()
        .filter { (_, instruction) ->
            instruction.opcode == Opcode.INVOKE_STATIC &&
                referenceOf(instruction) in listOf(CATALOG_MAIN_BUILD, CATALOG_GROUP_BUILD)
        }
    if (listBuilds.size != 3) {
        throw PatchException(
            "SwiftKey: expected three toolbar catalog list builds in the catalog's static " +
                "initializer, found ${listBuilds.size}. The catalog changed for this version.",
        )
    }
    // Insert after every list build's move-result-object: the register that holds the freshly
    // built list is routed through the helper and the result replaces it, so the sput-object
    // right after stores the filtered list.
    for ((index, _) in listBuilds.sortedByDescending { it.index }) {
        val moveResult = catalogMethod.instructions.elementAt(index + 1)
        if (moveResult !is OneRegisterInstruction || moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) {
            throw PatchException(
                "SwiftKey: a toolbar catalog list build is not followed by a move-result-object. " +
                    "The catalog changed for this version.",
            )
        }
        val register = (moveResult as OneRegisterInstruction).registerA
        if (register > 15) {
            throw PatchException(
                "SwiftKey: a toolbar catalog list lives in register v$register, which the " +
                    "single-call injection cannot reach. The catalog changed for this version.",
            )
        }
        catalogMethod.addInstructions(
            index + 2,
            """
                invoke-static {v$register}, $TOOLBAR_WITHOUT_BLOAT
                move-result-object v$register
            """.trimIndent().prependIndent("    "),
        )
    }
}

/**
 * Removes the sign-in item's two private paths in the toolbar state builder
 * Lio/v;->k(Z)Lio/w;:
 *
 *  - the gate that whitelists the account id 0x17 next to the catalog, which kept saved
 *    sign-in entries alive,
 *
 *  - the forced insertion of the sign-in item into the strip's first page when it is not in
 *    the saved ordering, which re-added the button even for fresh installs. This is what made
 *    the 1.1.0 "hide the rendered button" approach provably insufficient.
 */
context(ctx: app.morphe.patcher.patch.BytecodePatchContext)
private fun removeAccountItem() {
    val stateBuilder = ToolbarStateBuilderFingerprint.method

    // ---- 1. The gate's catalog copy + account add: -----------------------------
    //
    //   sget-object v13, Lio/e;->b:Ljava/util/List;
    //   new-instance v11, Ljava/util/ArrayList;
    //   invoke-direct {v11, v13}, ArrayList;-><init>(Collection)V
    //   invoke-virtual {v11, v12}, ArrayList;->add(Object)Z   <-- the account add, nop it
    //
    // The single ArrayList;->add(Object) call after the catalog field read is the account add.
    val instructions = stateBuilder.instructions
    val catalogReadIndex = instructions.withIndex()
        .indexOfFirst { (_, instruction) -> referenceOf(instruction) == "Lio/e;->b:Ljava/util/List;" }
    if (catalogReadIndex < 0) {
        throw PatchException(
            "SwiftKey: the toolbar state builder no longer reads the item catalog. The state " +
                "builder changed for this version.",
        )
    }
    val gateAddIndex = instructions.withIndex()
        .drop(catalogReadIndex)
        .take(6)
        .indexOfFirst { (_, instruction) ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL && referenceOf(instruction) == ARRAY_LIST_ADD_OBJECT
        }
    if (gateAddIndex < 0) {
        throw PatchException(
            "SwiftKey: the catalog gate's account add was not found. The state builder changed " +
                "for this version.",
        )
    }
    stateBuilder.replaceInstruction(catalogReadIndex + gateAddIndex, "nop")

    // ---- 2. The forced sign-in insertion: ---------------------------------------
    //
    //   if-nez v13, :cond_21           (sign-in item already present)
    //   invoke-virtual {v5, v12}, LXn/e;->invoke(Object)Object   <-- create the account item
    //   move-result-object v6
    //   invoke-virtual {v1, v4, v6}, ArrayList;->add(I, Object)V <-- insert into the strip page
    //   :cond_21
    //
    // The ArrayList;->add(I, Object) call is unique in the method: nop it and the two
    // instructions that produce its item argument.
    val insertAddIndex = instructions.withIndex()
        .indexOfFirst { (_, instruction) ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL && referenceOf(instruction) == ARRAY_LIST_ADD_AT
        }
    if (insertAddIndex < 0) {
        throw PatchException(
            "SwiftKey: the forced sign-in insertion was not found in the state builder. The " +
                "state builder changed for this version.",
        )
    }
    // The create call and its move-result sit directly before the insert call.
    val createIndex = insertAddIndex - 2
    val create = instructions.elementAt(createIndex)
    val moveResult = instructions.elementAt(createIndex + 1)
    if (create.opcode != Opcode.INVOKE_VIRTUAL ||
        referenceOf(create) != "LXn/e;->invoke(Ljava/lang/Object;)Ljava/lang/Object;" ||
        moveResult.opcode != Opcode.MOVE_RESULT_OBJECT
    ) {
        throw PatchException(
            "SwiftKey: the forced sign-in insertion is not preceded by the item factory call. " +
                "The state builder changed for this version.",
        )
    }
    stateBuilder.replaceInstruction(createIndex, "nop")
    stateBuilder.replaceInstruction(createIndex + 1, "nop")
    stateBuilder.replaceInstruction(insertAddIndex, "nop")
}

/**
 * Removes the non-keyboard bloat from the Settings side of the app. Kept separate from the
 * bytecode half so the whole shutdown ships as the single user-facing "Debloat" patch.
 */
internal val debloatSettingsResourcePatch = resourcePatch(
    description = "Removes the account, rewards and OneDrive notice entries from the Settings home " +
        "screen, the Bing/Copilot search settings from Rich input and the sign-in screen's " +
        "activity. Applied automatically with Debloat.",
) {
    compatibleWith(swiftKeyCompatibility)

    execute {
        fun Element.key(): String = getAttribute("android:key")

        fun removeElements(document: org.w3c.dom.Document, fileName: String, keys: Set<String>) {
            // The preference screens only ever have one root, so matching every element by its
            // android:key attribute is enough to find the entries to delete.
            val elements = document.getElementsByTagName("*")
                .let { nodes ->
                    (0 until nodes.length)
                        .mapNotNull { nodes.item(it) as? Element }
                        .filter { it.key() in keys }
                }
            val found = elements.map { it.key() }.toSet()
            val missing = keys - found
            if (missing.isNotEmpty()) {
                throw PatchException(
                    "SwiftKey: $fileName is missing preference entries $missing. The settings " +
                        "screens changed for this version.",
                )
            }
            // Remove duplicates the same way they are declared: every matched element goes.
            elements.forEach { it.parentNode.removeChild(it) }
        }

        document("res/xml/prefsx_home_screen.xml").use { document ->
            removeElements(
                document,
                "prefsx_home_screen.xml",
                setOf(
                    // OneDrive backups notice shown above the account row.
                    "@string/pref_home_notice_information_prefs",
                    // The whole Account section entry.
                    "@string/pref_home_launch_cloud_account_prefs",
                    // The Microsoft Rewards section entry.
                    "@string/pref_home_launch_rewards_prefs",
                ),
            )
        }

        document("res/xml/prefsx_rich_input_screen.xml").use { document ->
            removeElements(
                document,
                "prefsx_rich_input_screen.xml",
                setOf(
                    // "Microsoft Copilot Search" suggestions bar and its helpers.
                    "@string/pref_bing_suggestions_bar_enabled_key",
                    "@string/pref_bing_recommender_history_enabled",
                    "@string/pref_clear_bing_recommender_history",
                    // The Bing/Copilot shopping recommendations bar of 9.12.13.13.
                    "@string/pref_shopping_recommender_bar_enabled_key",
                ),
            )
        }

        // ---- Delete the sign-in screen itself from the manifest --------------------
        //
        // 1.1.0 only hid the ways into the sign-in screen; the activity stayed declared and
        // reachable, which the user proved by opening it from the keyboard's sign-in button.
        // The button is now deleted at the item model root by the bytecode half, and the
        // screen is removed from the manifest for good. Every remaining in-app launcher goes
        // through SafeIntentStartingActivity, which shows a graceful error dialog if the
        // component is gone instead of crashing.
        document("AndroidManifest.xml").use { document ->
            val activities = document.getElementsByTagName("activity")
            val toRemove = (0 until activities.length)
                .mapNotNull { activities.item(it) as? Element }
                .filter { it.getAttribute("android:name") == "com.touchtype.cloud.uiv2.CloudSetupActivity" }
            if (toRemove.size != 1) {
                throw PatchException(
                    "SwiftKey: expected exactly one CloudSetupActivity in the manifest, found " +
                        "${toRemove.size}. The manifest changed for this version.",
                )
            }
            toRemove.forEach { it.parentNode.removeChild(it) }
        }
    }
}

@Suppress("unused")
val debloatPatch = bytecodePatch(
    name = "Debloat",
    description = "Deletes SwiftKey's non-keyboard bloat from the root. Toolbar: the Rewards, " +
        "stickers, sign-in, rich media, search, share, Copilot (writing assistant), Create and " +
        "Tips items are removed from the item catalog itself, so they are gone from the strip, " +
        "the toolgrid, the customizer's reorder screen and saved orderings alike (1.1.0 only " +
        "hid them from the rendered strip, and the sign-in button even came back through the " +
        "state builder's forced insertion - both paths are cut now). 1.2.1 also deletes the two " +
        "STICKERS tabs from the emoji/GIF panel itself (they are catalog pages of their own, " +
        "which 1.2.0 missed), so the panel really only offers emojis and GIFs. Settings: the " +
        "whole Account section, the Microsoft Rewards section, the AI achievements section " +
        "inside Achievements, the Bing/Copilot search settings in Rich input, the online theme " +
        "Gallery tab in Themes, and the sign-in screen's activity is deleted from the manifest. " +
        "Typing, clipboard, themes and the rest of the keyboard stay unchanged and functional.",
) {
    compatibleWith(swiftKeyCompatibility)
    extendWith("extensions/swiftkey.mpe")
    dependsOn(debloatSettingsResourcePatch)

    execute {
        // ---- 1. Toolbar: delete the bloat items from the item catalog ----------------------
        rewriteCatalog()

        // ---- 2. Toolbar: cut the sign-in item's own paths in the state builder -------------
        removeAccountItem()

        // ---- 3. Toolbar: strip filter as a safety net for old saved orderings --------------
        //
        // The catalog gate already drops the bloat ids, but an ordering saved by an older
        // install can still slip items into the merged render list; the extension filter
        // catches those.
        val toolbarMethod = ToolbarStateObserverFingerprint.method
        val mergeCalls = toolbarMethod.instructions.withIndex()
            .filter { (_, instruction) -> referenceOf(instruction) == TOOLBAR_MERGE }
        if (mergeCalls.size != 1) {
            throw PatchException(
                "SwiftKey: expected exactly one toolbar item merge in the state observer, found " +
                    "${mergeCalls.size}.",
            )
        }
        // The call is immediately followed by its move-result-object; insert after it.
        val mergeResultIndex = mergeCalls.single().index + 1
        val mergeResult = toolbarMethod.instructions.elementAt(mergeResultIndex)
        if (mergeResult !is OneRegisterInstruction) {
            throw PatchException(
                "SwiftKey: the toolbar item merge is not followed by a move-result. The state " +
                    "observer changed for this version.",
            )
        }
        val mergedListRegister = (mergeResult as OneRegisterInstruction).registerA
        if (mergedListRegister > 15) {
            throw PatchException(
                "SwiftKey: the merged toolbar list lives in register v$mergedListRegister, which " +
                    "the single-call injection cannot reach. The state observer changed for this " +
                    "version.",
            )
        }
        toolbarMethod.addInstructions(mergeResultIndex + 1, "invoke-static {v$mergedListRegister}, $TOOLBAR_FILTER")

        // ---- 4. Achievements: drop the AI achievements section ------------------------------
        //
        // The LazyColumn appends five slots. Slot #2 is the "AI achievements" header composable
        // (Hk/D;->a) and slot #3 the AI achievement cards; both are appended through
        // Lq0/g;->n(...) and nops remove them while the level card and the typing sections stay.
        val achievementsMethod = AchievementsContentFingerprint.method
        val achievementSlots = achievementsMethod.instructions.withIndex()
            .filter { (_, instruction) -> referenceOf(instruction) == LAZY_COLUMN_SLOT }
        if (achievementSlots.size != 5) {
            throw PatchException(
                "SwiftKey: expected five LazyColumn slots in the achievements screen, found " +
                    "${achievementSlots.size}.",
            )
        }
        achievementsMethod.replaceInstruction(achievementSlots[1].index, "nop")
        achievementsMethod.replaceInstruction(achievementSlots[2].index, "nop")

        // ---- 5. Themes: drop the online Gallery tab and start on "Your themes" --------------
        //
        // The tab row emits the Gallery tab as its first LG0/m3;->b(...) slot; nop it. The view
        // model also hardcodes the initial tab as Gallery, so that single sget-object is swapped
        // for the offline "Your themes" tab. The destination register is read from the original
        // instruction instead of being pinned to this build.
        val tabRowMethod = ThemesTabRowFingerprint.method
        val tabSlots = tabRowMethod.instructions.withIndex()
            .filter { (_, instruction) -> referenceOf(instruction) == TAB_ROW_SLOT }
        if (tabSlots.size != 3) {
            throw PatchException(
                "SwiftKey: expected three theme tabs in the tab row, found ${tabSlots.size}.",
            )
        }
        tabRowMethod.replaceInstruction(tabSlots[0].index, "nop")

        val themesMethod = ThemesInitialStateFingerprint.method
        val galleryDefaults = themesMethod.instructions.withIndex()
            .filter { (_, instruction) -> referenceOf(instruction) == GALLERY_TAB }
        if (galleryDefaults.size != 1) {
            throw PatchException(
                "SwiftKey: expected exactly one Gallery default tab selection, found " +
                    "${galleryDefaults.size}.",
            )
        }
        val galleryDefault = galleryDefaults.single().index
        val tabRegister = (themesMethod.instructions.elementAt(galleryDefault)
            as OneRegisterInstruction).registerA
        themesMethod.replaceInstruction(galleryDefault, "sget-object v$tabRegister, $YOUR_THEMES_TAB")
    }
}
