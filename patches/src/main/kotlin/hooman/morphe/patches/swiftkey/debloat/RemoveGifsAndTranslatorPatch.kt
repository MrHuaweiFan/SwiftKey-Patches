package hooman.morphe.patches.swiftkey.debloat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import hooman.morphe.patches.swiftkey.swiftKeyCompatibility

// The extension helper that rewrites the catalog lists. Applied right after each of the three
// catalog lists is built in Lio/e;-><clinit>, exactly like the Debloat catalog rewrite.
private const val TOOLBAR_WITHOUT_GIF_TRANSLATOR =
    "Lcom/morphe/swiftkey/toolbar/MorpheToolbar;->withoutGifTranslator(Ljava/util/List;)Ljava/util/List;"

// The catalog list builders of Lio/e;-><clinit>. The main list is built by Ltr/l;->i0 (it holds the
// GIF toolbar item 0x04 and the Translator item 0x0a), the two smaller groups by Ltr/o;->j0 (the
// second one is the rich content panel page list Lio/e;->d, which holds the GIF panel page 0x04).
private const val CATALOG_MAIN_BUILD =
    "Ltr/l;->i0([Ljava/lang/Object;)Ljava/util/List;"
private const val CATALOG_GROUP_BUILD =
    "Ltr/o;->j0([Ljava/lang/Object;)Ljava/util/List;"

/** The reference of an instruction as text, or null when it has none. */
private fun referenceOf(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction): String? =
    (instruction as? ReferenceInstruction)?.reference?.toString()

/**
 * Removes the GIF and the Translator features from the root of the toolbar item model, the same way
 * the Debloat patch removes its bloat items:
 *
 *  - 0x04 (4) GIF: the toolbar's GIF button and the GIF page of the rich content panel (the panel
 *    tab list Lio/e;->d holds the same id as its page). The button and the panel tab vanish from the
 *    strip, the toolgrid, the customizer's reorder screen and saved orderings alike, and without
 *    the panel page the GIF search (Tenor or, with the GIF source patch, KLIPY) is never opened.
 *
 *  - 0x0a (10) Translator: the toolbar item that binds the external Microsoft Translator app.
 *
 * Everything else - typing, clipboard, themes, the emoji panel - stays unchanged and functional.
 */
@Suppress("unused")
val removeGifsAndTranslatorPatch = bytecodePatch(
    name = "Remove GIFs and translator",
    description = "Removes the GIF panel and button and the translator toolbar item.",
    default = false,
) {
    compatibleWith(swiftKeyCompatibility)
    extendWith("extensions/swiftkey.mpe")

    execute {
        // Rewrite every catalog list built in Lio/e;-><clinit> through the extension helper, so
        // the two ids vanish from the root of the item model instead of only from one list. This
        // mirrors the Debloat patch's catalog rewrite; if both patches are applied, the rewrites
        // nest harmlessly (each helper call filters the list it receives).
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
        // built list is routed through the helper and the result replaces it. Inserting from last
        // to first keeps the earlier indices valid.
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
                    invoke-static {v$register}, $TOOLBAR_WITHOUT_GIF_TRANSLATOR
                    move-result-object v$register
                """.trimIndent().prependIndent("    "),
            )
        }
    }
}
