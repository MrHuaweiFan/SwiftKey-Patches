package com.morphe.swiftkey.network;

import android.app.Application;
import android.content.Intent;
import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import android.content.Context;

/**
 * Runtime network guard injected into SwiftKey by the merged
 * "Network connections" Morphe patch.
 *
 * Policy (strict allow-list): the app may only connect to the hosts its three
 * remaining online features need. Everything else - Google, Firebase, Crashlytics,
 * Adjust, telemetry, experiments, the AI/Copilot backend, theme store downloads,
 * token sharing and account APIs - is blocked at the connection setup itself.
 *
 * The three features and their hosts (verified against the string resources, the
 * R8-renamed code and the live services of 9.12.13.13):
 *
 *  - GIF search: g.tenor.com and every *.tenor.com thumbnail host of the results.
 *    The Tenor API key, however, is NOT bundled with the app: the GIF panel reads
 *    it from SwiftKey's remote configuration service ("bibo",
 *    bibo.api.swiftkey.com - the Rk/b enum entry "tenor_gifs"/"key"), so that one
 *    host must be reachable as well or every search is rejected by Tenor with an
 *    empty key. 1.2.2 blocked it, which is why GIF search stopped working even
 *    with its switch on.
 *  - Translator: no HTTP hosts of its own (it binds the external Microsoft
 *    Translator app); the binding is guarded in {@link #checkedBind(Intent)}.
 *  - Language & model downloads: the catalog and the model archives are served by
 *    jenson.api.swiftkey.com (pref_global_configuration_url and the "archive" URLs
 *    of the catalog entries), with cdn.swiftkey.microsoft.com as the alternative
 *    catalog host (pref_china_configuration_url). 1.2.2 wrongly allowed the
 *    touchtype-fluency.com/azure CDN hosts here - those belong to the theme store,
 *    the auth servers and cloud sync - so language downloads were blocked even
 *    with their switch on.
 *
 * The feature switches live in the app's default shared preferences file
 * ("<package>_preferences"), written by the native SwiftKey settings switches
 * the patch adds. The keyboard, the settings UI and all services except the
 * WebView auth helper run in the app's main process, so a toggle takes effect
 * for the running keyboard immediately; the WebView auth helper's traffic
 * (account sign-in) is on the block list anyway.
 */
public final class NetworkGuard {

    /** Preference key: GIF search. */
    public static final String PREF_GIF = "morphe_network_gif";

    /** Preference key: translator (external Microsoft Translator app binding). */
    public static final String PREF_TRANSLATOR = "morphe_network_translator";

    /** Preference key: language & model downloads. */
    public static final String PREF_LANGUAGE = "morphe_network_language_downloads";

    /** The package of the external Microsoft Translator app that is bound for translation. */
    private static final String TRANSLATOR_PACKAGE = "com.microsoft.translator";

    private NetworkGuard() {
    }

    /**
     * @return the Application of the current process, or null if it cannot be
     *         determined (the guard then fails open exactly like an unpatched app).
     */
    private static Application application() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object app = threadClass.getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Read the enabled feature keys from the app's default shared preferences
     * ("<package>_preferences"). Absent keys mean the feature is on (the patch
     * ships with every switch enabled). The keyboard, the settings UI and the
     * services share one process, so changes are visible immediately; the
     * SharedPreferences instance itself keeps the values in memory.
     */
    private static Set<String> enabledFeatures() {
        Application app = application();
        if (app == null) {
            // No application context (yet): fail open, like an unpatched app.
            return allFeatures();
        }
        try {
            android.content.SharedPreferences preferences = app.getSharedPreferences(
                    app.getPackageName() + "_preferences",
                    android.content.Context.MODE_PRIVATE);
            Set<String> enabled = new HashSet<String>();
            if (preferences.getBoolean(PREF_GIF, true)) {
                enabled.add(PREF_GIF);
            }
            if (preferences.getBoolean(PREF_TRANSLATOR, true)) {
                enabled.add(PREF_TRANSLATOR);
            }
            if (preferences.getBoolean(PREF_LANGUAGE, true)) {
                enabled.add(PREF_LANGUAGE);
            }
            return enabled;
        } catch (Throwable ignored) {
            return allFeatures();
        }
    }

    private static Set<String> allFeatures() {
        Set<String> all = new HashSet<String>();
        all.add(PREF_GIF);
        all.add(PREF_TRANSLATOR);
        all.add(PREF_LANGUAGE);
        return all;
    }

    /** Normalize a host for comparisons. */
    static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String result = host.trim().toLowerCase();
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean hostMatches(String host, String suffix) {
        return host.equals(suffix) || host.endsWith("." + suffix);
    }

    /**
     * @param host the host of the attempted connection.
     * @return true when one of the enabled features needs the host.
     */
    static boolean isAllowed(String host) {
        String normalized = normalizeHost(host);
        if (normalized.length() == 0) {
            return false;
        }
        Set<String> enabled = enabledFeatures();
        // GIF search: the Tenor API (g.tenor.com, dead since 30 June 2026) or, when the
        // "GIF source" patch replaced the Tenor service with KLIPY, api.klipy.com plus the
        // static*.klipy.com hosts that serve the search results and their thumbnails. The
        // exact host bibo.api.swiftkey.com stays allowed with it because the GIF panel
        // fetches its configured API key from SwiftKey's remote configuration service.
        if (enabled.contains(PREF_GIF) &&
                (hostMatches(normalized, "tenor.com") ||
                        hostMatches(normalized, "klipy.com") ||
                        hostMatches(normalized, "bing.com") ||
                        hostMatches(normalized, "bibo.api.swiftkey.com"))) {
            return true;
        }
        // Language & fluency model downloads: the market catalog and the model
        // archives are both served by jenson.api.swiftkey.com (verified against
        // the live catalog; every "archive" URL points there), with
        // cdn.swiftkey.microsoft.com as the alternative catalog host. No other
        // host is needed for downloading languages and typing models.
        if (enabled.contains(PREF_LANGUAGE) &&
                (hostMatches(normalized, "jenson.api.swiftkey.com") ||
                        hostMatches(normalized, "cdn.swiftkey.microsoft.com"))) {
            return true;
        }
        // Everything else is not needed by the keyboard: telemetry, Firebase,
        // Crashlytics, Adjust, Google APIs, experiments, the AI backend, theme
        // store downloads, token sharing, account sign-in, store links, ...
        return false;
    }

    /**
     * Guard for the bundled OkHttp connection setup and every
     * URL.openConnection() call site of the app.
     *
     * @param host the host about to be connected to.
     * @throws java.io.IOException when the connection is not allowed. Network
     *                             stacks treat this exactly like any other
     *                             connection failure, which the app already
     *                             handles gracefully.
     */
    @SuppressWarnings("unused")
    public static void checkConnect(String host) throws IOException {
        try {
            if (!isAllowed(host)) {
                throw new IOException(
                        "Connection to " + host + " is not allowed by the SwiftKey network settings");
            }
        } catch (IOException error) {
            throw error;
        } catch (Throwable ignored) {
            // Never break the caller because of guard-internal errors.
        }
    }

    /**
     * The dead-end address blocked connections are redirected to: port 9
     * (discard) on the device itself. Nothing listens there, so every
     * connection to it fails instantly with a plain java.net.ConnectException
     * - exactly the failure every HTTP stack already handles as "no network".
     */
    private static final String DEAD_END = "http://127.0.0.1:9/";

    /** Cached dead-end URL (constant after the first construction). */
    private static volatile URL deadEndUrl;

    /**
     * Guard for URL.openConnection() call sites. Returns the same URL when the
     * connection is allowed. Blocked URLs are redirected to a dead-end address
     * instead of throwing, so the call site register can be reused as-is.
     *
     * 1.1.0 threw an IOException at the call site itself, and at least the
     * Firebase Installations helper wraps that into a java.lang.Error on its
     * blocking thread, which killed the process (an uncaught Error on ANY
     * thread terminates the app). The redirect makes blocked callers see an
     * ordinary connection failure at the moment they actually use the
     * connection - a failure mode the unpatched app already handles on
     * offline devices.
     */
    @SuppressWarnings("unused")
    public static URL checked(URL url) {
        try {
            if (url != null) {
                String host = url.getHost();
                if (host != null && host.length() > 0 && !isAllowed(host)) {
                    return deadEnd();
                }
            }
        } catch (Throwable ignored) {
            // Never break the caller because of guard-internal errors.
        }
        return url;
    }

    /** @return the cached dead-end URL, constructed on first use. */
    private static URL deadEnd() {
        URL result = deadEndUrl;
        if (result == null) {
            try {
                result = new URL(DEAD_END);
            } catch (Throwable error) {
                // Cannot happen for the constant, but never throw from here.
                result = null;
            }
            deadEndUrl = result;
        }
        return result;
    }

    /**
     * Guard for WebView loadUrl call sites. Returns "about:blank" when the
     * URL's host is not allowed, which renders an empty page.
     */
    @SuppressWarnings("unused")
    public static String checkedUrl(String url) {
        try {
            if (url != null) {
                String host = hostOfUrl(url);
                if (host.length() == 0 || !isAllowed(host)) {
                    return "about:blank";
                }
            }
        } catch (Throwable ignored) {
            // Never break the caller because of guard-internal errors.
        }
        return url;
    }

    /**
     * Guard for Context.bindService call sites. The translator feature binds
     * the external Microsoft Translator app; when its switch is off, the bind
     * intent is redirected to a class that does not exist, so bindService
     * returns false exactly as when the translator app is not installed, which
     * the app handles gracefully. Every other bind passes through untouched.
     */
    @SuppressWarnings("unused")
    public static Intent checkedBind(Intent intent) {
        try {
            if (intent != null) {
                android.content.ComponentName component = intent.getComponent();
                String componentPackage = component != null
                        ? component.getPackageName()
                        : intent.getPackage();
                if (TRANSLATOR_PACKAGE.equals(componentPackage)
                        && !enabledFeatures().contains(PREF_TRANSLATOR)) {
                    intent.setComponent(new android.content.ComponentName(
                            componentPackage, "com.morphe.swiftkey.disabled"));
                }
            }
        } catch (Throwable ignored) {
            // Never break the caller because of guard-internal errors.
        }
        return intent;
    }

    /** Extract the host of an absolute http(s) URL without java.net.URL. */
    static String hostOfUrl(String url) {
        String remainder = url.trim();
        int schemeEnd = remainder.indexOf("://");
        if (schemeEnd >= 0) {
            remainder = remainder.substring(schemeEnd + 3);
        }
        int cut = remainder.indexOf('/');
        if (cut >= 0) {
            remainder = remainder.substring(0, cut);
        }
        cut = remainder.indexOf('?');
        if (cut >= 0) {
            remainder = remainder.substring(0, cut);
        }
        cut = remainder.indexOf('#');
        if (cut >= 0) {
            remainder = remainder.substring(0, cut);
        }
        int atSign = remainder.lastIndexOf('@');
        if (atSign >= 0) {
            remainder = remainder.substring(atSign + 1);
        }
        int colon = remainder.indexOf(':');
        if (colon >= 0) {
            remainder = remainder.substring(0, colon);
        }
        if (remainder.startsWith("[")) {
            int bracket = remainder.indexOf(']');
            remainder = bracket >= 0
                    ? remainder.substring(1, bracket)
                    : remainder.substring(1);
        }
        return normalizeHost(remainder);
    }
}
