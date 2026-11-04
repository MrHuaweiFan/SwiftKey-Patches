package com.morphe.swiftkey.network;

import android.os.Bundle;
import android.view.MenuItem;
import androidx.fragment.app.a;
import com.swiftkey.avro.telemetry.sk.android.PageName;
import com.touchtype.materialsettings.TrackedContainerActivity;

/**
 * Hosts the "Network connections" settings screen.
 *
 * It extends the app's own TrackedContainerActivity base, so it gets the exact
 * native settings chrome of SwiftKey's container screens: the toolbar with the
 * back arrow, the divider, the keyboard try-out field and the floating action
 * button, themed with the app's ContainerTheme.
 *
 * The screen is assembled exactly like the native container screens (the
 * pattern of SwiftKeyPreferencesActivity):
 *
 *  - d(): TrackedAppCompatActivity.onCreate calls the abstract telemetry page
 *    name method before doing anything else; every native settings screen
 *    implements it, and so does this activity (with PageName.SETTINGS).
 *
 *  - t(R.layout.prefs_activity): the container layout alone has no fragment
 *    host; the native screens inflate the app's prefs_activity layout into
 *    the container's content frame first, which provides R.id.prefs_content.
 *
 *  - the fragment gets its preference XML through the "prefs_file" argument
 *    and is added with the public FragmentTransaction constructor, add and
 *    commit - the same renamed calls the native screens use. The app's own
 *    final u(String, Bundle) of SwiftKeyPreferenceFragment reads the argument
 *    and inflates the XML (1.2.0 overrode u(), which is final in the real
 *    class; the override made the fragment fail verification, which left the
 *    screen empty).
 */
public final class MorpheNetworkSettingsActivity extends TrackedContainerActivity {

    public MorpheNetworkSettingsActivity() {
    }

    @Override
    public PageName d() {
        // The page name every native settings screen reports for telemetry.
        return PageName.SETTINGS;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            String packageName = getPackageName();
            int containerId = getResources().getIdentifier("prefs_content", "id", packageName);
            int layoutId = getResources().getIdentifier("prefs_activity", "layout", packageName);
            int titleId = getResources().getIdentifier("morphe_network_title", "string", packageName);
            int prefsXmlId = getResources().getIdentifier("prefsx_morphe_network", "xml", packageName);

            // Inflate the app's own preference activity layout into the
            // container's content frame, exactly like SwiftKeyPreferencesActivity
            // does: it provides the prefs_content fragment host.
            if (layoutId != 0) {
                t(layoutId);
            }

            if (containerId != 0 && prefsXmlId != 0) {
                MorpheNetworkFragment fragment = new MorpheNetworkFragment();
                // The preference XML is passed through the arguments, exactly
                // like the native screens do: the app's own (final) preference
                // loading hook reads "prefs_file" and inflates it.
                Bundle arguments = new Bundle();
                arguments.putInt("prefs_file", prefsXmlId);
                fragment.setArguments(arguments);

                // The app itself builds transactions through the public
                // FragmentTransaction constructor (beginTransaction is renamed
                // away in this build), so the extension does the same.
                a transaction = new a(getSupportFragmentManager());
                transaction.m(containerId, fragment, "morphe_network");
                transaction.f();
            }
            i.a actionBar = getSupportActionBar();
            if (actionBar != null) {
                if (titleId != 0) {
                    actionBar.v(getString(titleId));
                }
                actionBar.n(true);
            }
        } catch (Throwable ignored) {
            // Never crash the settings app because of extension-internal errors.
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // android.R.id.home (16908332): behave like the app's own screens.
        if (item != null && item.getItemId() == 16908332) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
