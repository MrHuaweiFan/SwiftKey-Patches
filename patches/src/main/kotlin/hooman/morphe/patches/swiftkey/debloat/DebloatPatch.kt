package hooman.morphe.patches.swiftkey.debloat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import hooman.morphe.patches.swiftkey.swiftKeyCompatibility
import org.w3c.dom.Element

// The slot appends used by the Achievements screen LazyColumn (Ls1/h;->p) and the Themes tab row
// (Lr2/o9;->b).
private const val LAZY_COLUMN_SLOT =
    "Ls1/h;->p(Ls1/h;Ljava/lang/String;Lc3/m;I)V"
private const val TAB_ROW_SLOT =
    "Lr2/o9;->b(ZLgm0/a;Li3/s;ZLgm0/f;JJLu2/t;I)V"

// The merge that produces the rendered toolbar item list in the Toolbar state observer.
private const val TOOLBAR_MERGE =
    "Lrl0/r;->W0(Ljava/lang/Iterable;Ljava/util/Collection;)Ljava/util/ArrayList;"

// The online "Gallery" tab the Themes view model selects by default; swapped for "Your themes".
private const val GALLERY_DEFAULT_TAB = "Lmg0/n;->a:Lmg0/n;"
private const val YOUR_THEMES_TAB = "Lmg0/n;->b:Lmg0/n;"

/**
 * Removes the non-keyboard bloat from the Settings side of the app. Kept separate from the
 * bytecode half so the whole shutdown ships as the single user-facing "Debloat" patch.
 */
internal val debloatSettingsResourcePatch = resourcePatch(
    description = "Removes the account, rewards and OneDrive notice entries from the Settings home " +
        "screen and the Bing/Copilot search settings from Rich input. Applied automatically with " +
        "Debloat.",
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
                    "@string/pref_compact_mode_enabled",
                    "@string/pref_bing_recommender_history_enabled",
                    "@string/pref_clear_bing_recommender_history",
                ),
            )
        }
    }
}

@Suppress("unused")
val debloatPatch = bytecodePatch(
    name = "Debloat",
    description = "Removes SwiftKey's non-keyboard bloat from the root. Settings: the whole " +
        "Account section, the Microsoft Rewards section, the AI achievements section inside " +
        "Achievements, the Bing/Copilot search settings in Rich input and the online theme " +
        "Gallery tab in Themes. Keyboard toolbar: the Writing assistant (Copilot), Create, " +
        "Rewards, Share, Search, Tips/new message and Sign in items. Typing, clipboard, themes " +
        "and the rest of the keyboard stay unchanged and functional.",
) {
    compatibleWith(swiftKeyCompatibility)
    dependsOn(debloatSettingsResourcePatch)

    execute {
        // ---- 1. Toolbar: drop the bloat items from the merged item list --------------------
        //
        // The state observer merges the saved customizer ordering with the default pages and then
        // renders one button per item. Inserting a filter loop directly after the merge removes
        // the bloat items no matter which source contributed them. Register v1 holds the merged
        // ArrayList at that point; v2/v3/v4 are re-initialized by the following code before use,
        // so the inserted loop can clobber them freely.
        val toolbarMethod = ToolbarStateObserverFingerprint.method
        val mergeCalls = toolbarMethod.instructions.withIndex()
            .filter { (_, instruction) ->
                (instruction as? ReferenceInstruction)?.reference?.toString() == TOOLBAR_MERGE
            }
        if (mergeCalls.size != 1) {
            throw PatchException(
                "SwiftKey: expected exactly one toolbar item merge in the state observer, found " +
                    "${mergeCalls.size}.",
            )
        }
        // The call is immediately followed by its move-result-object; insert after it.
        val mergeResultIndex = mergeCalls.single().index + 1
        val mergeResult = toolbarMethod.instructions.elementAt(mergeResultIndex)
        if (mergeResult !is com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction) {
            throw PatchException(
                "SwiftKey: the toolbar item merge is not followed by a move-result. The state " +
                    "observer changed for this version.",
            )
        }
        toolbarMethod.addInstructions(mergeResultIndex + 1, DEBLOAT_TOOLBAR_FILTER)

        // ---- 2. Achievements: drop the AI achievements section ------------------------------
        //
        // The LazyColumn appends five slots. Slot #2 is the "AI achievements" header and slot #3
        // the AI achievement cards; both are appended through Ls1/h;->p(...) and nops remove them
        // while the level card and the typing sections stay.
        val achievementsMethod = AchievementsContentFingerprint.method
        val achievementSlots = achievementsMethod.instructions.withIndex()
            .filter { (_, instruction) ->
                (instruction as? ReferenceInstruction)?.reference?.toString() == LAZY_COLUMN_SLOT
            }
        if (achievementSlots.size != 5) {
            throw PatchException(
                "SwiftKey: expected five LazyColumn slots in the achievements screen, found " +
                    "${achievementSlots.size}.",
            )
        }
        achievementsMethod.replaceInstruction(achievementSlots[1].index, "nop")
        achievementsMethod.replaceInstruction(achievementSlots[2].index, "nop")

        // ---- 3. Themes: drop the online Gallery tab and start on "Your themes" --------------
        //
        // The tab row emits the Gallery tab as its first Lr2/o9;->b(...) slot; nop it. The view
        // model also hardcodes the initial tab as Gallery, so that single sget-object is swapped
        // for the offline "Your themes" tab. The register (v4) is pinned by this build.
        val tabRowMethod = ThemesTabRowFingerprint.method
        val tabSlots = tabRowMethod.instructions.withIndex()
            .filter { (_, instruction) ->
                (instruction as? ReferenceInstruction)?.reference?.toString() == TAB_ROW_SLOT
            }
        if (tabSlots.size != 3) {
            throw PatchException(
                "SwiftKey: expected three theme tabs in the tab row, found ${tabSlots.size}.",
            )
        }
        tabRowMethod.replaceInstruction(tabSlots[0].index, "nop")

        val themesMethod = ThemesInitialStateFingerprint.method
        val galleryDefaults = themesMethod.instructions.withIndex()
            .filter { (_, instruction) ->
                (instruction as? ReferenceInstruction)?.reference?.toString() == GALLERY_DEFAULT_TAB
            }
        if (galleryDefaults.size != 1) {
            throw PatchException(
                "SwiftKey: expected exactly one Gallery default tab selection, found " +
                    "${galleryDefaults.size}.",
            )
        }
        themesMethod.replaceInstruction(
            galleryDefaults.single().index,
            "sget-object v4, $YOUR_THEMES_TAB",
        )
    }
}

/**
 * In-place filter for the merged toolbar item list. Runs from index 0 of v1 (the merged
 * ArrayList), checks every item's type through Ltd0/f;->getItemId() and removes the bloat items:
 * 0x2d Rewards, 0x2c Create, 0x17 Sign in, 0x11 Tips (messaging centre), 0x28 Share,
 * 0x1d Writing assistant (Copilot hub) and 0x2f Bing Search. Uses only v2/v3/v4, which the
 * surrounding method re-initializes before their next use.
 */
private const val DEBLOAT_TOOLBAR_FILTER = """
    const/4 v2, 0x0
    :goto_skdebloat_loop
    invoke-virtual {v1}, Ljava/util/ArrayList;->size()I
    move-result v3
    if-ge v2, v3, :goto_skdebloat_done
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;
    move-result-object v4
    check-cast v4, Ltd0/f;
    invoke-interface {v4}, Ltd0/f;->getItemId()I
    move-result v3
    const/16 v4, 0x2d
    if-ne v3, v4, :sskdebloat_0
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->remove(I)Ljava/lang/Object;
    move-result-object v4
    goto :goto_skdebloat_loop
    :sskdebloat_0
    const/16 v4, 0x2c
    if-ne v3, v4, :sskdebloat_1
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->remove(I)Ljava/lang/Object;
    move-result-object v4
    goto :goto_skdebloat_loop
    :sskdebloat_1
    const/16 v4, 0x17
    if-ne v3, v4, :sskdebloat_2
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->remove(I)Ljava/lang/Object;
    move-result-object v4
    goto :goto_skdebloat_loop
    :sskdebloat_2
    const/16 v4, 0x11
    if-ne v3, v4, :sskdebloat_3
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->remove(I)Ljava/lang/Object;
    move-result-object v4
    goto :goto_skdebloat_loop
    :sskdebloat_3
    const/16 v4, 0x28
    if-ne v3, v4, :sskdebloat_4
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->remove(I)Ljava/lang/Object;
    move-result-object v4
    goto :goto_skdebloat_loop
    :sskdebloat_4
    const/16 v4, 0x1d
    if-ne v3, v4, :sskdebloat_5
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->remove(I)Ljava/lang/Object;
    move-result-object v4
    goto :goto_skdebloat_loop
    :sskdebloat_5
    const/16 v4, 0x2f
    if-ne v3, v4, :sskdebloat_keep
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->remove(I)Ljava/lang/Object;
    move-result-object v4
    goto :goto_skdebloat_loop
    :sskdebloat_keep
    add-int/lit8 v2, v2, 0x1
    goto :goto_skdebloat_loop
    :goto_skdebloat_done
"""
