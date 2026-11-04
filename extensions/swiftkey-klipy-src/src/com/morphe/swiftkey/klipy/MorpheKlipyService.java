package com.morphe.swiftkey.klipy;

import Ho.H;
import Ho.K;
import Ho.Q;
import Ho.U;
import Ho.Y;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import wr.c;
import xr.a;

/**
 * The KLIPY-backed replacement of SwiftKey 9.12.13.13's Tenor GIF service.
 *
 * Google shut the Tenor API down on 30 June 2026, so every GIF search of the
 * unpatched keyboard fails. The "GIF source" Morphe patch replaces the
 * factory that builds the Tenor Retrofit service (LHo/G;->a) with this class,
 * which speaks KLIPY's Tenor-compatible v2 API instead - the same service the
 * new SwiftKey versions use (SwiftKey is listed as a KLIPY integration
 * partner). The app's own GIF panel, paging, recents row and share flow keep
 * running unchanged; they just talk to this implementation:
 *
 *  - a(key, q, locale, limit, pos, cont): GET /v2/search (empty query falls
 *    back to GET /v2/featured, the trending feed).
 *  - b(key, id, locale, q, cont): GET /v2/registershare (best effort).
 *  - c(ids, key, limit, cont): GET /v2/posts (the recents row; old stored
 *    Tenor ids simply resolve to nothing and are pruned by the app itself).
 *
 * KLIPY returns Tenor v2 shaped JSON: results[].media_formats is a flat map
 * of format -&gt; media object, while the keyboard's models expect Tenor v1's
 * media: [ { tinygif: {...} } ] one-element array. This class builds the
 * model objects directly, so no JSON string is ever rewritten.
 *
 * The KLIPY app key is read from the "morphe_klipy_api_key" string resource
 * the patch adds (its value comes from the patch's API key option). Without a
 * key every call fails soft with an empty result, exactly like a network
 * hiccup.
 *
 * Suspend protocol: the methods may be called from any thread. On a worker
 * thread the result is returned synchronously (a suspend function completing
 * without suspending is legal); on the main thread the request runs on a
 * helper thread and COROUTINE_SUSPENDED is returned, resuming the caller with
 * the raw success value when done. Failures are swallowed and surfaced as
 * empty responses, so the GIF panel shows "no results" instead of crashing.
 *
 * Network connections patch interaction: before every request the injected
 * NetworkGuard (when the Network connections patch is applied) is consulted
 * reflectively, so the GIF search switch keeps gating the feature. The
 * guard's allow-list admits api.klipy.com and the static*.klipy.com media
 * hosts under the GIF switch, which covers the Fresco thumbnail loads the
 * app itself performs.
 */
public final class MorpheKlipyService implements H {

    /** KLIPY's Tenor-compatible API base. */
    private static final String API_BASE = "https://api.klipy.com";

    /** Connect/read timeouts of the GIF requests, in milliseconds. */
    private static final int TIMEOUT_MILLIS = 8000;

    public MorpheKlipyService() {
    }

    // ---- Ho/H.a: the GIF search ------------------------------------------------------

    @Override
    public Object a(String key, String q, String locale, Integer limit, String pos, c continuation) {
        return MorpheKlipyService.run(
                new SearchCall(q == null ? "" : q, locale, limit, pos),
                continuation);
    }

    // ---- Ho/H.b: share tracking -------------------------------------------------------

    @Override
    public Object b(String key, String id, String locale, String q, c continuation) {
        return MorpheKlipyService.run(new ShareCall(id, locale, q), continuation);
    }

    // ---- Ho/H.c: GIFs by ids (the recents row) ----------------------------------------

    @Override
    public Object c(String ids, String key, Integer limit, c continuation) {
        return MorpheKlipyService.run(new PostsCall(ids, limit), continuation);
    }

    // ---- the suspend protocol ---------------------------------------------------------

    /**
     * Runs the task and delivers its result following Kotlin's suspend
     * calling convention: synchronously when already on a worker thread,
     * asynchronously (through COROUTINE_SUSPENDED plus resumeWith) when
     * called on the main thread, where network access is forbidden anyway.
     */
    private static Object run(Task task, c continuation) {
        try {
            if (continuation == null) {
                return task.run();
            }
            boolean mainThread = false;
            try {
                android.os.Looper main = android.os.Looper.getMainLooper();
                mainThread = main != null && main == android.os.Looper.myLooper();
            } catch (Throwable ignored) {
                // Without a Looper answer assume a worker thread.
            }
            if (!mainThread) {
                return task.run();
            }
            Thread worker = new Thread(new Resume(task, continuation));
            worker.setName("morphe-klipy");
            worker.setDaemon(true);
            worker.start();
            return a.a; // COROUTINE_SUSPENDED
        } catch (Throwable ignored) {
            // Never let guard- or thread-internal errors escape into the app.
            return task.fallback();
        }
    }

    /** Resumes the caller with the (never failing) task result. */
    private static final class Resume implements Runnable {
        private final Task task;
        private final c continuation;

        Resume(Task task, c continuation) {
            this.task = task;
            this.continuation = continuation;
        }

        @Override
        public void run() {
            Object result;
            try {
                result = task.run();
            } catch (Throwable ignored) {
                result = task.fallback();
            }
            try {
                continuation.resumeWith(result);
            } catch (Throwable ignored) {
                // The caller is gone - nothing to do.
            }
        }
    }

    /**
     * A unit of (possibly blocking) GIF work returning a suspend result. Both
     * methods must never throw; infrastructure errors fall back to
     * {@link #fallback()}, which returns the type-correct empty answer.
     */
    private interface Task {
        Object run();

        Object fallback();
    }

    // ---- the three requests -----------------------------------------------------------

    /** GET /v2/search (or /v2/featured for an empty query). */
    private static final class SearchCall implements Task {
        private final String query;
        private final String locale;
        private final Integer limit;
        private final String pos;

        SearchCall(String query, String locale, Integer limit, String pos) {
            this.query = query;
            this.locale = locale;
            this.limit = limit;
            this.pos = pos;
        }

        @Override
        public Object run() {
            try {
                if (!networkAllowed()) {
                    return emptyResponse();
                }
                String apiKey = apiKey();
                if (apiKey.length() == 0) {
                    return emptyResponse();
                }
                StringBuilder url = new StringBuilder(API_BASE);
                if (query.length() == 0) {
                    url.append("/v2/featured");
                } else {
                    url.append("/v2/search");
                }
                url.append("?key=").append(encode(apiKey));
                if (query.length() != 0) {
                    url.append("&q=").append(encode(query));
                }
                url.append("&locale=").append(encode(locale(locale)));
                url.append("&limit=").append(pageSize(limit));
                if (query.length() != 0 && pos != null && pos.length() != 0) {
                    url.append("&pos=").append(encode(pos));
                }
                url.append("&contentfilter=medium&media_filter=tinygif,gif");
                return parse(fetch(url.toString()));
            } catch (Throwable ignored) {
                return emptyResponse();
            }
        }

        @Override
        public Object fallback() {
            return emptyResponse();
        }
    }

    /** GET /v2/registershare - reports a shared GIF, best effort. */
    private static final class ShareCall implements Task {
        private final String id;
        private final String locale;
        private final String query;

        ShareCall(String id, String locale, String query) {
            this.id = id;
            this.locale = locale;
            this.query = query;
        }

        @Override
        public Object run() {
            try {
                if (!networkAllowed()) {
                    return sr.D.a;
                }
                String apiKey = apiKey();
                if (apiKey.length() == 0 || id == null || id.length() == 0) {
                    return sr.D.a;
                }
                StringBuilder url = new StringBuilder(API_BASE);
                url.append("/v2/registershare");
                url.append("?key=").append(encode(apiKey));
                url.append("&id=").append(encode(id));
                url.append("&locale=").append(encode(locale(locale)));
                if (query != null && query.length() != 0) {
                    url.append("&q=").append(encode(query));
                }
                fetch(url.toString());
            } catch (Throwable ignored) {
                // Share tracking must never break sharing.
            }
            return sr.D.a;
        }

        @Override
        public Object fallback() {
            return sr.D.a;
        }
    }

    /** GET /v2/posts - resolves the recently used GIF ids. */
    private static final class PostsCall implements Task {
        private final String ids;
        private final Integer limit;

        PostsCall(String ids, Integer limit) {
            this.ids = ids;
            this.limit = limit;
        }

        @Override
        public Object run() {
            try {
                if (ids == null || ids.length() == 0) {
                    return emptyResponse();
                }
                if (!networkAllowed()) {
                    return emptyResponse();
                }
                String apiKey = apiKey();
                if (apiKey.length() == 0) {
                    return emptyResponse();
                }
                StringBuilder url = new StringBuilder(API_BASE);
                url.append("/v2/posts");
                url.append("?key=").append(encode(apiKey));
                url.append("&ids=").append(encode(ids));
                url.append("&limit=").append(pageSize(limit));
                url.append("&contentfilter=medium&media_filter=tinygif,gif");
                return parse(fetch(url.toString()));
            } catch (Throwable ignored) {
                return emptyResponse();
            }
        }

        @Override
        public Object fallback() {
            return emptyResponse();
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    /**
     * @return true when the GIF feature is allowed to connect. When the
     * Network connections patch is applied, its injected NetworkGuard is
     * consulted (it throws exactly when the GIF search switch is off); when
     * it is not applied nothing blocks the request.
     */
    private static boolean networkAllowed() {
        try {
            Class<?> guard = Class.forName("com.morphe.swiftkey.network.NetworkGuard");
            Method check = guard.getMethod("checkConnect", String.class);
            check.invoke(null, "api.klipy.com");
            return true;
        } catch (InvocationTargetException blocked) {
            // The guard refused the host: the GIF search switch is off.
            return false;
        } catch (Throwable notInstalled) {
            // No guard - the Network connections patch was not applied.
            return true;
        }
    }

    /**
     * @return the KLIPY app key configured through the patch option, or "".
     */
    private static String apiKey() {
        try {
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            if (!(application instanceof android.content.Context)) {
                return "";
            }
            android.content.Context context = (android.content.Context) application;
            int id = context.getResources()
                    .getIdentifier("morphe_klipy_api_key", "string", context.getPackageName());
            if (id == 0) {
                return "";
            }
            String key = context.getString(id);
            return key == null ? "" : key.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Performs the GET request and parses the JSON body.
     *
     * @return the parsed object, or null for every non-200 answer.
     */
    private static JSONObject fetch(String urlText) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Morphe-SwiftKey/1.2.4");
            int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            InputStream stream = connection.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return new JSONObject(new String(out.toByteArray(), "UTF-8"));
            } finally {
                stream.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Builds the Tenor v1 shaped search response model out of KLIPY's
     * Tenor v2 shaped JSON: every result's flat media_formats map becomes
     * the one-element media array the keyboard's models expect.
     */
    private static Y parse(JSONObject root) {
        List<Object> gifs = new ArrayList<Object>();
        String next = "";
        if (root != null) {
            next = root.optString("next", "");
            if (next == null) {
                next = "";
            }
            JSONArray results = root.optJSONArray("results");
            if (results != null) {
                for (int index = 0; index < results.length(); index++) {
                    JSONObject item = results.optJSONObject(index);
                    if (item == null) {
                        continue;
                    }
                    JSONObject formats = item.optJSONObject("media_formats");
                    if (formats == null) {
                        continue;
                    }
                    JSONObject tiny = formats.optJSONObject("tinygif");
                    if (tiny == null || tiny.optString("url", "").length() == 0) {
                        // Fall back to the full-size gif so the tile still renders.
                        JSONObject gif = formats.optJSONObject("gif");
                        if (gif != null && gif.optString("url", "").length() != 0) {
                            tiny = gif;
                        }
                    }
                    if (tiny == null || tiny.optString("url", "").length() == 0) {
                        continue;
                    }
                    String tinyUrl = tiny.optString("url");
                    U media = new U(
                            0,
                            tinyUrl,
                            dims(tiny.optJSONArray("dims")),
                            tiny.optString("preview", ""),
                            tiny.optInt("size", 0));
                    List<Object> mediaList = new ArrayList<Object>(1);
                    mediaList.add(new Q(0, media));
                    String full = item.optString("url", "");
                    if (full.length() == 0) {
                        full = tinyUrl;
                    }
                    gifs.add(new K(
                            0,
                            item.optString("id", ""),
                            mediaList,
                            tags(item.optJSONArray("tags")),
                            item.optString("title", ""),
                            full,
                            "",
                            ""));
                }
            }
        }
        return new Y(0, next, gifs);
    }

    /** @return the empty response: no results, no next page. */
    private static Y emptyResponse() {
        return new Y(0, "", new ArrayList<Object>(0));
    }

    /** @return the [width, height] pair of a media object, 1x1 when missing. */
    private static List<Object> dims(JSONArray array) {
        List<Object> result = new ArrayList<Object>(2);
        if (array != null) {
            for (int index = 0; index < array.length() && index < 2; index++) {
                result.add(Integer.valueOf(array.optInt(index, 1)));
            }
        }
        if (result.size() != 2 || ((Integer) result.get(0)).intValue() < 1
                || ((Integer) result.get(1)).intValue() < 1) {
            result.clear();
            result.add(Integer.valueOf(1));
            result.add(Integer.valueOf(1));
        }
        return result;
    }

    /** @return the string tags of a result. */
    private static List<Object> tags(JSONArray array) {
        List<Object> result = new ArrayList<Object>();
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                String tag = array.optString(index, null);
                if (tag != null && tag.length() != 0) {
                    result.add(tag);
                }
            }
        }
        return result;
    }

    /** Clamps the requested page size into KLIPY's 1..50 window. */
    private static int pageSize(Integer requested) {
        int size = requested == null ? 24 : requested.intValue();
        if (size < 1) {
            size = 1;
        }
        if (size > 50) {
            size = 50;
        }
        return size;
    }

    /** Normalizes the locale, defaulting to en_US. */
    private static String locale(String locale) {
        if (locale == null || locale.length() == 0) {
            return "en_US";
        }
        return locale;
    }

    /** URL-encodes a query value. */
    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }
}
