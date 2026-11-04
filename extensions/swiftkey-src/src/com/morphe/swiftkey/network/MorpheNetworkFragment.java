package com.morphe.swiftkey.network;

import com.touchtype.materialsettings.SwiftKeyPreferenceFragment;

/**
 * The "Network connections" settings screen of the patched SwiftKey.
 *
 * It is a real SwiftKey settings screen: the fragment extends the app's own
 * SwiftKeyPreferenceFragment base, so the list, the dividers, the Material
 * switches and the day/night theming render exactly like the app's built-in
 * screens (Typing, Emoji, Sound &amp; vibration, ...).
 *
 * 1.2.1 note: this class deliberately overrides NOTHING. The real
 * SwiftKeyPreferenceFragment of 9.12.13.13 implements the renamed
 * onCreatePreferences hook u(String, Bundle) as final, reading the preference
 * XML resource id from the fragment's arguments under the "prefs_file" key -
 * exactly how every native settings screen loads its XML. The 1.2.0 fragment
 * overrode u() (legal against the compile-only stub, illegal against the real
 * final method), so the class failed verification at runtime, the fragment
 * transaction silently failed and the screen rendered as an empty background.
 * The MorpheNetworkSettingsActivity now sets the "prefs_file" argument instead,
 * and the app's own final u() loads the patched-in preference XML.
 */
public final class MorpheNetworkFragment extends SwiftKeyPreferenceFragment {

    public MorpheNetworkFragment() {
    }
}
