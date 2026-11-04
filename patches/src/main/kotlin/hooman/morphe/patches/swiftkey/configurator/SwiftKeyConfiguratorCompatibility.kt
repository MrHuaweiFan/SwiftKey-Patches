package hooman.morphe.patches.swiftkey.configurator

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

/**
 * The compatibility of the "SwiftKey factory settings" companion app (the internal
 * com.swiftkey.swiftkeyconfigurator configurator that serves SwiftKey's factory configuration
 * from its bundled database through an exported content provider).
 */
internal val swiftKeyConfiguratorCompatibility = Compatibility(
    name = "SwiftKey factory settings",
    packageName = "com.swiftkey.swiftkeyconfigurator",
    appIconColor = 0x00A4EF,
    targets = listOf(AppTarget("2.2.0.314")),
)
