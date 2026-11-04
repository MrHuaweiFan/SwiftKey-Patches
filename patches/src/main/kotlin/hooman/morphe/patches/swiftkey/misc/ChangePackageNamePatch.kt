package hooman.morphe.patches.swiftkey.misc

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
import hooman.morphe.patches.swiftkey.swiftKeyCompatibility
import org.w3c.dom.Element

private const val ORIGINAL_PACKAGE = "com.touchtype.swiftkey"

/**
 * Classes whose hardcoded "com.touchtype.swiftkey" string constant must stay untouched, because it
 * is matched against DATA that still carries the original package name:
 *
 *  - Lc7/a;: the build flavor resolver. "com.touchtype.swiftkey" maps to "market",
 *    everything unknown maps to "dev". Renaming here would report the clone as a dev build.
 *  - LLm/g;: passes the constant into the flavor resolver.
 *  - LIo/F; and LMq/d;: append package_name=com.touchtype.swiftkey as a query parameter to the
 *    theme/sticker store API requests; the backend expects the original id.
 *  - LNk/N;: serializes the constant as "packageName" into the cloud sync device identity JSON;
 *    the backend expects the original id.
 *  - LTl/h;: adds the constant to the request parameter map of the language services API.
 */
private val classesKeepingOriginalPackage = setOf(
    "Lc7/a;",
    "LLm/g;",
    "LIo/F;",
    "LMq/d;",
    "LNk/N;",
    "LTl/h;",
)

private const val DYNAMIC_RECEIVER_PERMISSION_SUFFIX = "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

/**
 * A SwiftKey-specific replacement for Morphe's universal "Clone app" patch. Renames the package so
 * the patched build installs next to the stock SwiftKey (for example when SwiftKey is the
 * preinstalled default keyboard), and fixes everything a plain manifest rename breaks:
 *
 *  - renames the signature-scoped DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION definition and its
 *    uses-permission, which would otherwise collide with the stock app on install
 *    (INSTALL_FAILED_DUPLICATE_PERMISSION),
 *  - renames the four package-prefixed provider authorities, which would otherwise collide with
 *    the stock app on install (INSTALL_FAILED_CONFLICTING_PROVIDER),
 *  - updates the application_id resource, which builds the FileProvider authority in code,
 *  - rewrites the hardcoded self-references in code: the process name check in
 *    SwiftKeyApplication (a mismatch skips the entire main process initialization), the
 *    FileProvider authority, editor/ComponentName self-checks, installer and first-install-time
 *    lookups and resource-package lookups.
 *
 * Strings that refer to other apps or to bundled data (Cesar/Beta companion packages, deep link
 * schemes, static config selectors, the MSAL redirect scheme, the build flavor resolver) keep the
 * original value on purpose, so the clone behaves exactly like the original.
 */
@Suppress("unused")
val changePackageNamePatch = resourcePatch(
    name = "Change package name",
    description = "Renames the package (appends .morphe) so the patched build installs next to " +
        "the stock SwiftKey.",
) {
    compatibleWith(swiftKeyCompatibility)

    val packageNameOption: Option<String> = stringOption(
        key = "packageName",
        default = "Default",
        values = mapOf("Default" to "Default"),
        title = "Package name",
        description = "Package name for the renamed SwiftKey. \"Default\" appends \".morphe\" to " +
            "com.touchtype.swiftkey.",
        required = true,
    ) {
        it == "Default" ||
            (it != null && it != ORIGINAL_PACKAGE && it.matches(Regex("^[a-z]\\w*(\\.[a-z]\\w*)+$")))
    }

    fun resolvePackageName(): String {
        val value = packageNameOption.value
        if (value == null || value == packageNameOption.default) {
            return "$ORIGINAL_PACKAGE.morphe"
        }

        return value
    }

    dependsOn(
        bytecodePatch(
            description = "Rewrites the hardcoded self-references to the SwiftKey package in code. " +
                "Applied automatically with Change package name.",
        ) {
            execute {
                val originalPackage = packageMetadata.packageName
                if (originalPackage != ORIGINAL_PACKAGE) {
                    throw PatchException(
                        "SwiftKey: expected package $ORIGINAL_PACKAGE but found $originalPackage. " +
                            "The patch targets a different app or the package was already renamed.",
                    )
                }

                val newPackageName = resolvePackageName()
                var replacedCount = 0

                // Every const-string "com.touchtype.swiftkey" across all dex files. The classes in
                // classesKeepingOriginalPackage compare the constant against bundled data or feed
                // the flavor resolver and must keep the original value.
                classDefByStrings(ORIGINAL_PACKAGE)
                    .filter { it.type !in classesKeepingOriginalPackage }
                    .forEach { classDef ->
                        mutableClassDefBy(classDef).methods.forEach { method ->
                            val implementation = method.implementation ?: return@forEach
                            val matches = implementation.instructions.withIndex().filter { (_, original) ->
                                (original.opcode == Opcode.CONST_STRING ||
                                    original.opcode == Opcode.CONST_STRING_JUMBO) &&
                                    ((original as? ReferenceInstruction)?.reference as? StringReference)
                                        ?.string == ORIGINAL_PACKAGE
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
                        "SwiftKey: no hardcoded package references were found in code. The app " +
                            "code changed for this version.",
                    )
                }
            }
        },
    )

    finalize {
        val newPackageName = resolvePackageName()

        document("AndroidManifest.xml").use { document ->
            val root = document.documentElement
            if (root.getAttribute("package") != ORIGINAL_PACKAGE) {
                throw PatchException(
                    "SwiftKey: the manifest package is ${root.getAttribute("package")}, expected " +
                        "$ORIGINAL_PACKAGE. The package was already renamed.",
                )
            }

            // 1. The package itself. All component names in this manifest are fully qualified, so
            //    no relative name has to be rewritten for the components to keep resolving.
            root.setAttribute("package", newPackageName)

            // 2. The signature-scoped permission definition and its uses-permission reference.
            // Both apps (stock and renamed) would otherwise define the same permission and the
            // install fails with INSTALL_FAILED_DUPLICATE_PERMISSION.
            val dynamicReceiverPermission = "$ORIGINAL_PACKAGE.$DYNAMIC_RECEIVER_PERMISSION_SUFFIX"
            val newDynamicReceiverPermission = "$newPackageName.$DYNAMIC_RECEIVER_PERMISSION_SUFFIX"
            for (tag in listOf("permission", "uses-permission")) {
                document.getElementsByTagName(tag)
                    .let { nodes ->
                        (0 until nodes.length)
                            .mapNotNull { nodes.item(it) as? Element }
                            .filter { it.getAttribute("android:name") == dynamicReceiverPermission }
                            .forEach { it.setAttribute("android:name", newDynamicReceiverPermission) }
                }
            }

            // 3. Provider authorities prefixed with the old package: fileprovider,
            // androidx-startup, firebaseinitprovider and the adjust lifecycle provider. Renamed to
            // the same suffix under the new package so the code-built FileProvider authority
            // (application_id resource) keeps matching.
            document.getElementsByTagName("provider")
                .let { nodes ->
                    (0 until nodes.length)
                        .mapNotNull { nodes.item(it) as? Element }
                        .forEach { provider ->
                            val authorities = provider.getAttribute("android:authorities")
                                .split(";")
                            val renamed = authorities.map { authority ->
                                if (authority.startsWith("$ORIGINAL_PACKAGE.")) {
                                    "$newPackageName.${authority.substring(ORIGINAL_PACKAGE.length + 1)}"
                                } else {
                                    authority
                                }
                            }

                            if (renamed != authorities) {
                                provider.setAttribute("android:authorities", renamed.joinToString(";"))
                            }
                        }
                }
        }

        // 4. The application_id resource is formatted with "%1$s.fileprovider" to build the
        // FileProvider authority at runtime, so it has to follow the manifest authority.
        document("res/values/strings.xml").use { document ->
            val matches = document.getElementsByTagName("string")
                .let { nodes ->
                    (0 until nodes.length)
                        .mapNotNull { nodes.item(it) as? Element }
                        .filter { it.getAttribute("name") == "application_id" }
                }
            val applicationId = matches.singleOrNull() ?: throw PatchException(
                "SwiftKey: the application_id string was not found uniquely in res/values/strings.xml.",
            )
            applicationId.textContent = newPackageName
        }
    }
}
