package hooman.morphe.patches.swiftkey.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import hooman.morphe.patches.swiftkey.debloat.ToolbarCatalogFingerprint
import hooman.morphe.patches.swiftkey.debloat.ToolbarItemFactoryFingerprint
import hooman.morphe.patches.swiftkey.swiftKeyCompatibility
import org.w3c.dom.Element

// The extension helper that appends the blank element ids to the main catalog list.
private const val TOOLBAR_WITH_BLANKS =
    "Lcom/morphe/swiftkey/toolbar/MorpheToolbar;->withBlanks(Ljava/util/List;)Ljava/util/List;"

// The extension helper of the item factory hook: returns the blank item for a blank id and
// null for every other id.
private const val TOOLBAR_BLANK_ITEM =
    "Lcom/morphe/swiftkey/toolbar/MorpheToolbar;->blankItem(I)Ljava/lang/Object;"

// The main catalog list builder of Lio/e;-><clinit> (Ltr/o;->j0 builds the two smaller groups).
private const val CATALOG_MAIN_BUILD =
    "Ltr/l;->i0([Ljava/lang/Object;)Ljava/util/List;"

/** The reference of an instruction as text, or null when it has none. */
private fun referenceOf(instruction: Instruction): String? =
    (instruction as? ReferenceInstruction)?.reference?.toString()

/**
 * Adds the fully transparent toolbar icon the blank elements render with. Without a real drawable
 * the toolbar's theme change handler would crash on a null icon.
 */
private val blankElementsResourcesPatch = resourcePatch(
    description = "Adds the transparent toolbar icon used by the blank toolbar elements. Applied " +
        "automatically with Add blank toolbar elements.",
) {
    compatibleWith(swiftKeyCompatibility)

    execute {
        val drawable = this["res/drawable/morphe_toolbar_blank.xml"]
        if (!drawable.exists()) {
            drawable.parentFile?.mkdirs()
            drawable.writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <!-- Transparent icon used by the blank toolbar elements added by Morphe. -->
                <shape xmlns:android="http://schemas.android.com/apk/res/android"
                    android:shape="rectangle">
                    <solid android:color="#00000000" />
                </shape>
                """.trimIndent(),
            )
        }
    }
}

/**
 * The bytecode half of the patch: adds the blank ids to the toolbar item catalog and teaches the
 * app's item factory the blank ids. Kept separate so the user-facing resource patch can carry the
 * blank count option.
 *
 * 1.2.0 added the blanks as first-class catalog items instead of appending them to the rendered
 * strip list (the 1.1.0 approach, which pushed the real items to the left, left an empty block on
 * the right, and never showed the blanks in the customizer). The blank ids now live in the item
 * catalog itself and the app's own item factory creates them, so the blanks appear in the
 * customizer's "reorder items" screen, can be dragged into the strip, reordered and removed
 * exactly like every normal item, and render wherever the user places them.
 */
private val blankElementsBytecodePatch = bytecodePatch(
    description = "Adds the blank ids to the toolbar item catalog and teaches the item factory " +
        "the blank ids. Applied automatically with Add blank toolbar elements.",
) {
    compatibleWith(swiftKeyCompatibility)
    extendWith("extensions/swiftkey.mpe")

    execute {
        // ---- 1. Add the blank ids to the main toolbar item catalog --------------------------
        //
        // The main catalog list of Lio/e;-><clinit> is built by Ltr/l;->i0(...). Routing the
        // freshly built list through the extension helper right after the build appends the blank
        // ids, so the catalog membership gate of the state builder lets them through and the
        // customizer lists them. How many ids the helper appends is decided at runtime by the
        // morphe_blank_count resource the resource half writes from the patch option.
        val catalogMethod = ToolbarCatalogFingerprint.method
        val mainBuilds = catalogMethod.instructions.withIndex()
            .filter { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_STATIC && referenceOf(instruction) == CATALOG_MAIN_BUILD
            }
        if (mainBuilds.size != 1) {
            throw PatchException(
                "SwiftKey: expected exactly one main toolbar catalog list build, found " +
                    "${mainBuilds.size}. The catalog changed for this version.",
            )
        }
        val mainBuildIndex = mainBuilds.single().index
        val moveResult = catalogMethod.instructions.elementAt(mainBuildIndex + 1)
        if (moveResult !is com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction ||
            moveResult.opcode != Opcode.MOVE_RESULT_OBJECT
        ) {
            throw PatchException(
                "SwiftKey: the main catalog list build is not followed by a move-result-object. " +
                    "The catalog changed for this version.",
            )
        }
        val catalogRegister = (moveResult as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
        if (catalogRegister > 15) {
            throw PatchException(
                "SwiftKey: the main catalog list lives in register v$catalogRegister, which the " +
                    "single-call injection cannot reach. The catalog changed for this version.",
            )
        }
        catalogMethod.addInstructions(
            mainBuildIndex + 2,
            """
                invoke-static {v$catalogRegister}, $TOOLBAR_WITH_BLANKS
                move-result-object v$catalogRegister
            """.trimIndent().prependIndent("    "),
        )

        // ---- 2. Teach the app's item factory the blank ids ---------------------------------
        //
        // The single factory method that turns an item id into an item instance starts its case
        // 9 body by unboxing the id into a register and then casting the context. The injected
        // hook right before that cast returns a blank element for a blank id (which is returned
        // immediately, skipping the factory's own switch) and falls through to the original code
        // for every other id - one call, one branch, both inside the
        // inserted instructions (the patcher relocates labels of inserted code itself).
        val factoryMethod = ToolbarItemFactoryFingerprint.method
        val contextCastIndex = factoryMethod.instructions.withIndex()
            .indexOfFirst { (_, instruction) ->
                instruction.opcode == Opcode.CHECK_CAST && referenceOf(instruction) == "LBp/g;"
            }
        if (contextCastIndex < 0) {
            throw PatchException(
                "SwiftKey: the toolbar item factory's context cast was not found. The factory " +
                    "changed for this version.",
            )
        }
        // The id register: the move-result of the Integer;->intValue() call right before the
        // cast (the first instructions of the factory's case 9 body).
        val intValueCall = factoryMethod.instructions.elementAt(contextCastIndex - 2)
        val idMoveResult = factoryMethod.instructions.elementAt(contextCastIndex - 1)
        if (referenceOf(intValueCall) != "Ljava/lang/Integer;->intValue()I" ||
            idMoveResult !is com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
        ) {
            throw PatchException(
                "SwiftKey: the toolbar item factory does not read the item id before its context " +
                    "cast. The factory changed for this version.",
            )
        }
        val idRegister = (idMoveResult as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
        if (idRegister > 15) {
            throw PatchException(
                "SwiftKey: the item id lives in register v$idRegister, which the injection " +
                    "cannot reach. The factory changed for this version.",
            )
        }
        // Scratch register for the hook result: registers above the prologue's constant block
        // (the method declares 44 locals; only v0..v16 and v21 are live at this point, and the
        // case body reloads every one of its own working registers after the injection point).
        // v17 is dead here and far below the register limit, and move-result-object, if-eqz and
        // return-object all accept 8-bit register operands.
        val scratch = 17
        factoryMethod.addInstructionsWithLabels(
            contextCastIndex,
            """
                invoke-static {v$idRegister}, $TOOLBAR_BLANK_ITEM
                move-result-object v$scratch
                if-eqz v$scratch, :morphe_not_blank
                return-object v$scratch
                :morphe_not_blank
                nop
            """.trimIndent().prependIndent("    "),
        )
    }
}

/**
 * Adds blank elements to the keyboard's toolbar, next to the emoji, GIF or incognito buttons.
 * Each element renders as an empty, transparent button of exactly the same size as the regular
 * toolbar buttons and does nothing at all when tapped or long-pressed. They are real catalog
 * items: they appear in the customizer's reorder screen and can be dragged, reordered and removed
 * like any other item.
 *
 * How many blank elements are added is the patch option: 1 to 6, defaulting to 6 (the historical
 * behavior). The chosen count is written to the morphe_blank_count integer resource, which the
 * extension helper of the catalog rewrite reads at runtime, so the option applies without any
 * additional bytecode injection.
 */
@Suppress("unused")
val addBlankToolbarElementsPatch = resourcePatch(
    name = "Add blank toolbar elements",
    description = "Adds 1-6 blank toolbar elements you can reorder like any other item.",
    default = true,
) {
    compatibleWith(swiftKeyCompatibility)

    val blankCountOption: Option<Int> = intOption(
        key = "blankCount",
        default = 6,
        values = mapOf(
            "1" to 1,
            "2" to 2,
            "3" to 3,
            "4" to 4,
            "5" to 5,
            "6" to 6,
        ),
        title = "Blank elements",
        description = "How many blank elements to add to the toolbar (1 to 6).",
        required = false,
    ) {
        it != null && it in 1..6
    }

    dependsOn(blankElementsResourcesPatch)
    dependsOn(blankElementsBytecodePatch)

    execute {
        val count = blankCountOption.value ?: 6

        // The count the extension helper reads at runtime. integers.xml exists in SwiftKey, so
        // the entry is added to it (or updated when an older patched build is patched again).
        document("res/values/integers.xml").use { document ->
            val matches = document.getElementsByTagName("integer")
                .let { nodes ->
                    (0 until nodes.length)
                        .mapNotNull { nodes.item(it) as? Element }
                        .filter { it.getAttribute("name") == "morphe_blank_count" }
                }
            val existing = matches.singleOrNull()
            if (existing != null) {
                existing.textContent = count.toString()
            } else {
                document.documentElement.appendChild(
                    document.createElement("integer").apply {
                        setAttribute("name", "morphe_blank_count")
                        textContent = count.toString()
                    },
                )
            }
        }
    }
}
