package hooman.morphe.patches.swiftkey.configurator

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element

/**
 * A configurator-specific replacement for the SwiftKey "Change package name" patch: renames the
 * factory settings app so a patched build installs next to a stock one, and fixes everything a
 * plain manifest rename breaks:
 *
 *  - renames the signature-scoped READCONFIG permission definition, which would otherwise collide
 *    with the stock app on install,
 *  - renames the ConfigProvider authority, which would otherwise collide with the stock app on
 *    install (INSTALL_FAILED_CONFLICTING_PROVIDER),
 *  - rewrites the hardcoded self-references in code: the UriMatcher authority entries of the
 *    query dispatcher (Lcom/swiftkey/swiftkeyconfigurator/e;) match incoming content URIs against
 *    the package string, so they must follow the manifest authority or every query would fail
 *    with "Invalid URI".
 *
 * The keyboard reaches the configurator by its original authority and package name (both are
 * hardcoded in the keyboard), so a renamed configurator can no longer serve the keyboard. Apply
 * this patch only when you need the renamed build to coexist with a stock one and do not rely on
 * the factory settings pairing; leave it off (default) to keep the pairing working.
 */
@Suppress("unused")
val changeConfiguratorPackageNamePatch = resourcePatch(
    name = "Change package name",
    description = "Renames the package (appends .morphe) so it installs next to the stock " +
        "factory settings app; breaks the keyboard link.",
    default = false,
) {
    compatibleWith(swiftKeyConfiguratorCompatibility)

    val packageNameOption: Option<String> = stringOption(
        key = "packageName",
        default = "Default",
        values = mapOf("Default" to "Default"),
        title = "Package name",
        description = "Package name for the renamed configurator. \"Default\" appends \".morphe\" " +
            "to $CONFIGURATOR_PACKAGE.",
        required = true,
    ) {
        it == "Default" ||
            (it != null && it != CONFIGURATOR_PACKAGE && it.matches(Regex("^[a-z]\\w*(\\.[a-z]\\w*)+$")))
    }

    fun resolvePackageName(): String {
        val value = packageNameOption.value
        if (value == null || value == packageNameOption.default) {
            return "$CONFIGURATOR_PACKAGE.morphe"
        }

        return value
    }

    dependsOn(
        bytecodePatch(
            description = "Rewrites the hardcoded self-references to the configurator package in " +
                "code. Applied automatically with Change package name.",
        ) {
            execute {
                val originalPackage = packageMetadata.packageName
                if (originalPackage != CONFIGURATOR_PACKAGE) {
                    throw PatchException(
                        "Configurator: expected package $CONFIGURATOR_PACKAGE but found " +
                            "$originalPackage. The patch targets a different app or the package " +
                            "was already renamed.",
                    )
                }

                val newPackageName = resolvePackageName()
                var replacedCount = 0

                // Every const-string "com.swiftkey.swiftkeyconfigurator" across all dex files. All
                // of them are the UriMatcher authority entries of the query dispatcher
                // (Lcom/swiftkey/swiftkeyconfigurator/e;), which must follow the manifest
                // authority.
                classDefByStrings(CONFIGURATOR_PACKAGE)
                    .forEach { classDef ->
                        mutableClassDefBy(classDef).methods.forEach { method ->
                            val implementation = method.implementation ?: return@forEach
                            val matches = implementation.instructions.withIndex().filter { (_, original) ->
                                (original.opcode == Opcode.CONST_STRING ||
                                    original.opcode == Opcode.CONST_STRING_JUMBO) &&
                                    ((original as? ReferenceInstruction)?.reference as? StringReference)
                                        ?.string == CONFIGURATOR_PACKAGE
                            }.toList()

                            matches.forEach { (index, original) ->
                                val register = (original as OneRegisterInstruction).registerA
                                val instruction = if (original.opcode == Opcode.CONST_STRING_JUMBO) {
                                    "const-string/jumbo v$register, \"$newPackageName\""
                                } else {
                                    "const-string v$register, \"$newPackageName\""
                                }

                                method.replaceInstruction(index, instruction)
                                replacedCount++
                            }
                        }
                    }

                if (replacedCount == 0) {
                    throw PatchException(
                        "Configurator: no hardcoded package references were found in code. The " +
                            "app code changed for this version.",
                    )
                }
            }
        },
    )

    finalize {
        val newPackageName = resolvePackageName()

        document("AndroidManifest.xml").use { document ->
            val root = document.documentElement
            if (root.getAttribute("package") != CONFIGURATOR_PACKAGE) {
                throw PatchException(
                    "Configurator: the manifest package is ${root.getAttribute("package")}, " +
                        "expected $CONFIGURATOR_PACKAGE. The package was already renamed.",
                )
            }

            // 1. The package itself.
            root.setAttribute("package", newPackageName)

            // 2. The signature-scoped permission definition. Both apps (stock and renamed) would
            //    otherwise define the same permission and the install fails with
            //    INSTALL_FAILED_DUPLICATE_PERMISSION.
            fun elements(tag: String): List<Element> =
                document.getElementsByTagName(tag)
                    .let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }

            val oldPermission = "${CONFIGURATOR_PACKAGE}.READCONFIG"
            val newPermission = "$newPackageName.READCONFIG"
            for (element in elements("permission")) {
                if (element.getAttribute("android:name") == oldPermission) {
                    element.setAttribute("android:name", newPermission)
                }
            }

            // 3. The provider: its authority must not collide with the stock app
            //    (INSTALL_FAILED_CONFLICTING_PROVIDER), and its read permission reference must
            //    follow the renamed permission definition.
            for (provider in elements("provider")) {
                if (provider.getAttribute("android:authorities") == CONFIGURATOR_AUTHORITY) {
                    provider.setAttribute("android:authorities", newPackageName)
                }
                if (provider.getAttribute("android:permission") == oldPermission) {
                    provider.setAttribute("android:permission", newPermission)
                }
            }
        }
    }
}
