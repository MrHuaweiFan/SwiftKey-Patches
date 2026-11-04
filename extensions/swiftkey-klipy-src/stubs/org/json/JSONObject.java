package org.json;

/**
 * Compile-only stub of the platform org.json.JSONObject. Only the methods the
 * KLIPY extension uses are declared; at runtime the real Android class
 * resolves.
 */
public class JSONObject {
    public JSONObject(String json) {
    }

    public String optString(String name) {
        return null;
    }

    public String optString(String name, String fallback) {
        return null;
    }

    public int optInt(String name, int fallback) {
        return 0;
    }

    public JSONObject optJSONObject(String name) {
        return null;
    }

    public JSONArray optJSONArray(String name) {
        return null;
    }
}
