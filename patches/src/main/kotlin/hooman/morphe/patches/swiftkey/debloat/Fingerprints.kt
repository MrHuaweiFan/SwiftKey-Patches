package hooman.morphe.patches.swiftkey.debloat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Lcom/touchtype/keyboard/toolbar/Toolbar;->g(ILjava/lang/Object;)V — the toolbar strip's state
 * observer. It merges the saved customizer ordering with the default item pages into one
 * ArrayList (Lrl0/r;->W0) and then rebuilds the strip's item buttons, so filtering the merged list
 * right after the merge removes items from the rendered toolbar in one place.
 */
internal object ToolbarStateObserverFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I", "Ljava/lang/Object;"),
    strings = listOf("state"),
    filters = listOf(methodCall(definingClass = "Ltd0/c1;", name = "r")),
)

/**
 * Lo70/q;->invoke(Ljava/lang/Object;)Ljava/lang/Object; — the Achievements screen LazyColumn
 * content. It appends five slots through Ls1/h;->p(...): the level card, the "AI achievements"
 * section header, the AI achievement cards, the "Typing achievements" header and the typing
 * achievement cards. Identified uniquely by registering the AI header composable Lo70/c0;->f.
 */
internal object AchievementsContentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf("\$this\$LazyColumn"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            (instruction as? ReferenceInstruction)?.reference?.toString() == "Lo70/c0;->f:Lc3/m;"
        } == true
    },
)

/**
 * Lng0/s0;->invoke(...)Ljava/lang/Object; — the Themes screen tab row. It emits the three theme
 * store tabs through Lr2/o9;->b(...), the first one being the online "Gallery" tab (Lng0/c;->a).
 */
internal object ThemesTabRowFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(methodCall(definingClass = "Lr2/o9;", name = "b")),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            (instruction as? ReferenceInstruction)?.reference?.toString() == "Lng0/c;->a:Lc3/m;"
        } == true
    },
)

/**
 * Lmg0/e1;-><init>(...)V — the Themes screen view model constructor. It builds the initial
 * themes state with Lmg0/r0;-><init> and hardcodes the starting tab as the online "Gallery" tab
 * (Lmg0/n;->a). The patch swaps that single sget-object for the offline "Your themes" tab.
 */
internal object ThemesInitialStateFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Lgd0/m0;",
        "Lgd0/b;",
        "Lk40/b;",
        "Lae0/y2;",
        "Lzb0/b;",
    ),
    strings = listOf("themeManager", "incognitoModeModel"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            (instruction as? ReferenceInstruction)?.reference?.toString() == "Lmg0/n;->a:Lmg0/n;"
        } == true
    },
)
