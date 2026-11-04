package com.touchtype.materialsettings;

import com.swiftkey.telemetry.TrackedAppCompatActivity;

/**
 * COMPILE-ONLY STUB of SwiftKey's container activity base of 9.12.13.13
 * (Lcom/touchtype/materialsettings/TrackedContainerActivity;). Its onCreate
 * inflates the native settings chrome (R.layout.container with R.id.toolbar,
 * the keyboard try-out field and the floating action button), which is
 * exactly why the Morphe network settings screen extends it.
 *
 * The real class also provides t(int), which inflates a layout into the
 * container's R.id.content frame - the native settings screens (like
 * SwiftKeyPreferencesActivity) call t(R.layout.prefs_activity) so that the
 * R.id.prefs_content fragment host of that layout exists.
 *
 * This class is never packaged into the extension.
 */
public abstract class TrackedContainerActivity extends TrackedAppCompatActivity {

    /** Inflates the given layout resource into the container's content frame. */
    public void t(int layoutResId) {
        throw new RuntimeException("stub");
    }
}
