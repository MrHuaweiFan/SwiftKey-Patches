package hooman.morphe.patches.swiftkey.debloat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Lcom/touchtype/keyboard/toolbar/Toolbar;->e(ILjava/lang/Object;)V — the toolbar strip's state
 * observer of SwiftKey 9.12.13.13. It merges the saved customizer ordering with the default item
 * pages into one ArrayList (Ltr/n;->b1) and then rebuilds the strip's item buttons, so filtering or
 * extending the merged list right after the merge edits the rendered toolbar in one place.
 */
internal object ToolbarStateObserverFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I", "Ljava/lang/Object;"),
    strings = listOf("state"),
    filters = listOf(methodCall(definingClass = "Ltr/n;", name = "b1")),
)

/**
 * Lio/e;-><clinit>()V — the toolbar item catalog of SwiftKey 9.12.13.13. It builds exactly three
 * lists: the main customizer catalog through Ltr/l;->i0(...) and two smaller groups through
 * Ltr/o;->j0(...). Every id that is not in the catalog is dropped by the toolbar state builder, so
 * rewriting the lists right where they are built removes an item everywhere at once - from the
 * customizer, from saved orderings and from the strip.
 */
internal object ToolbarCatalogFingerprint : Fingerprint(
    definingClass = "Lio/e;",
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = "Ltr/l;", name = "i0"),
        methodCall(definingClass = "Ltr/o;", name = "j0"),
    ),
    custom = { method, _ ->
        method.implementation?.instructions?.count { instruction ->
            instruction.opcode == Opcode.INVOKE_STATIC &&
                (instruction as? ReferenceInstruction)?.reference?.toString() ==
                "Ltr/o;->j0([Ljava/lang/Object;)Ljava/util/List;"
        } == 2
    },
)

/**
 * Lio/v;->k(Z)Lio/w; — the toolbar state builder of SwiftKey 9.12.13.13. It reads the saved
 * ordering ("readToolbarItemIds"), gates every id against the catalog plus the account id 0x17
 * (sign-in), instantiates the items and force-inserts the sign-in item into the strip's first page
 * when it is not there. The Debloat patch neutralizes both account paths in this method; the blank
 * elements patch relies on its catalog ids passing the gate.
 */
internal object ToolbarStateBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lio/w;",
    parameters = listOf("Z"),
    strings = listOf("readToolbarItemIds"),
)

/**
 * LXn/e;->invoke(Ljava/lang/Object;)Ljava/lang/Object; — the toolbar item factory of SwiftKey
 * 9.12.13.13. Its case 9 body turns an item id into the item instance (and throws
 * "No toolbar item for toolbar item type" for unknown ids). Every toolbar item of the strip, the
 * toolgrid and the customizer is created through this single method, which pins it without relying
 * on any R8-renamed class name.
 */
internal object ToolbarItemFactoryFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf("No toolbar item for toolbar item type"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.CHECK_CAST &&
                (instruction as? ReferenceInstruction)?.reference?.toString() == "LBp/g;"
        } == true
    },
)

/**
 * LGo/b;->invoke(Ljava/lang/Object;)Ljava/lang/Object; — the Achievements screen LazyColumn
 * content of 9.12.13.13. It appends five slots through Lq0/g;->n(...): the level card, the
 * "AI achievements" section header (the Hk/D;->a composable), the AI achievement cards, the
 * "Typing achievements" header (Hk/D;->b) and the typing achievement cards. Identified uniquely
 * by registering the AI header composable Hk/D;->a.
 */
internal object AchievementsContentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf("\$this\$LazyColumn"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            (instruction as? ReferenceInstruction)?.reference?.toString() == "LHk/D;->a:LR0/d;"
        } == true
    },
)

/**
 * LHk/u;->invoke(...)Ljava/lang/Object; — the Themes screen tab row of 9.12.13.13. It emits the
 * three theme store tabs through LG0/m3;->b(...), the first one being the online "Gallery" tab
 * (the Lqp/f;->a composable).
 */
internal object ThemesTabRowFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(methodCall(definingClass = "LG0/m3;", name = "b")),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            (instruction as? ReferenceInstruction)?.reference?.toString() == "Lqp/f;->a:LR0/d;"
        } == true
    },
)

/**
 * Lpp/Z;-><init>(...)V — the Themes screen view model constructor of 9.12.13.13. It builds the
 * initial themes state and hardcodes the starting tab as the online "Gallery" tab (Lpp/m;->a,
 * the enum value GALLERY_THEMES). The patch swaps that single sget-object for the offline
 * "Your themes" tab (Lpp/m;->b, YOUR_THEMES).
 */
internal object ThemesInitialStateFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("themeManager", "incognitoModeModel"),
    custom = { method, _ ->
        method.parameterTypes.firstOrNull()?.toString() == "Landroid/content/Context;" &&
            method.implementation?.instructions?.any { instruction ->
                (instruction as? ReferenceInstruction)?.reference?.toString() == "Lpp/m;->a:Lpp/m;"
            } == true
    },
)

/**
 * LYm/c;->u(Landroid/content/Context;LRl/w;LXn/h;Landroidx/lifecycle/M;LZr/x0;)Lgn/a; - the smart
 * clip view holder's factory for the FIRST action chip of the smart clip ribbon above the
 * suggestion strip: the round button that appears left of the quick paste pill when text is
 * copied (e.g. the magnifying web-search button for the copied text, or the image search for
 * copied images). Pinned by its parameter shape plus the "clip" and "themeViewModel" null checks
 * of its body.
 */
internal object SmartClipFirstActionChipFingerprint : Fingerprint(
    returnType = "Lgn/a;",
    parameters = listOf(
        "Landroid/content/Context;",
        "LRl/w;",
        "LXn/h;",
        "Landroidx/lifecycle/M;",
        "LZr/x0;",
    ),
    strings = listOf("clip", "themeViewModel"),
)

/**
 * LYm/c;->s(Landroid/content/Context;Lgn/g;LXn/h;Landroidx/lifecycle/M;LRl/w;LZr/x0;)Lgn/a; - the
 * smart clip view holder's factory for the SECOND action chip of the same ribbon (created from the
 * clip's action model; its null is what keeps the ribbon to the pill alone). Pinned by its
 * parameter shape plus the "themeViewModel" and "clip" null checks of its body.
 */
internal object SmartClipSecondActionChipFingerprint : Fingerprint(
    returnType = "Lgn/a;",
    parameters = listOf(
        "Landroid/content/Context;",
        "Lgn/g;",
        "LXn/h;",
        "Landroidx/lifecycle/M;",
        "LRl/w;",
        "LZr/x0;",
    ),
    strings = listOf("themeViewModel", "clip"),
)
