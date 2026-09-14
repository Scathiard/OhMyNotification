package io.github.prince_dulb.ohmynotification.testsource;

import android.app.Notification;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.IdentityHashMap;
import java.util.List;

/** Test-only, in-memory comparison. Never returns public-field names, contents or target values. */
public final class BoundedPublicRouteScan {
    private final String expected;
    private final String identifier;
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private final Bundle counts = new Bundle();
    private int nodes;
    private int characters;
    private BoundedPublicRouteScan(String expected) {
        if (expected == null || expected.isEmpty() || expected.length() > 65536)
            throw new IllegalArgumentException("Invalid comparison target");
        this.expected = expected;
        Uri uri = Uri.parse(expected);
        this.identifier = "bilibili".equals(uri.getScheme()) && "video".equals(uri.getHost())
                ? uri.getLastPathSegment() : null;
        for (String key : new String[]{"scannedNodes", "strings", "uris", "bundles", "intents",
                "jsonContainers", "exactTargetValues", "embeddedTargetValues", "identifierOnlyValues",
                "routeLikeValues", "unsupportedValues", "readErrors", "limitHits",
                "unsupportedIconResource", "unsupportedIconUri", "unsupportedIconBitmap", "unsupportedIconData",
                "unsupportedIconOther", "unsupportedBitmap", "unsupportedBytes", "unsupportedPrimitiveArray",
                "unsupportedPendingIntent", "unsupportedRemoteViews", "unsupportedFrameworkOther",
                "unsupportedNonFramework", "unsupportedApplicationInfo"}) counts.putInt(key, 0);
    }
    public static Bundle compare(Notification notification, String expected) {
        BoundedPublicRouteScan scan = new BoundedPublicRouteScan(expected);
        scan.visit(notification.extras, 0);
        scan.visit(notification.tickerText, 0);
        if (notification.actions != null) for (Notification.Action action : notification.actions) {
            if (scan.nodes >= 2048) { scan.add("limitHits"); break; }
            scan.visit(action.title, 0);
            scan.visit(action.getExtras(), 0);
        }
        scan.counts.putInt("scannedNodes", scan.nodes);
        // Limited to declared public fields, never claims coverage of RemoteViews or opaque handles.
        scan.counts.putBoolean("publicRouteFieldComparison", true);
        scan.counts.putBoolean("completeWithinDeclaredFields",
                scan.counts.getInt("readErrors") == 0 && scan.counts.getInt("limitHits") == 0
                        && scan.counts.getInt("unsupportedValues") == 0);
        return scan.counts;
    }
    private void add(String key) { counts.putInt(key, counts.getInt(key) + 1); }
    @SuppressWarnings("deprecation")
    private void visit(Object value, int depth) {
        if (value == null || value == JSONObject.NULL) return;
        if (++nodes > 2048 || depth > 8) { add("limitHits"); return; }
        // Reject application-defined implementations; never load or execute source-package code.
        if (!(value instanceof JSONObject) && !(value instanceof JSONArray)
                && value.getClass().getClassLoader() != String.class.getClassLoader()) {
            add("unsupportedValues"); add("unsupportedNonFramework"); return;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) return;
        if (seen.put(value, Boolean.TRUE) != null) return;
        try {
            if (value instanceof CharSequence) {
                if (((CharSequence) value).length() > 65536) { add("limitHits"); return; }
                text(value.toString(), depth);
            } else if (value instanceof Uri) { add("uris"); text(value.toString(), depth); }
            else if (value instanceof Bundle) {
                add("bundles");
                Bundle bundle = new Bundle((Bundle) value);
                bundle.setClassLoader(null);
                for (String key : bundle.keySet()) {
                    if (nodes >= 2048) { add("limitHits"); break; }
                    visit(bundle.get(key), depth + 1);
                }
            } else if (value instanceof Intent) {
                add("intents");
                Intent intent = (Intent) value;
                visit(intent.getData(), depth + 1);
                visit(intent.getAction(), depth + 1);
                visit(intent.getExtras(), depth + 1);
            } else if (value instanceof JSONObject) {
                add("jsonContainers");
                JSONObject json = (JSONObject) value;
                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    if (nodes >= 2048) { add("limitHits"); break; }
                    visit(json.opt(keys.next()), depth + 1);
                }
            } else if (value instanceof JSONArray) {
                add("jsonContainers");
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    if (nodes >= 2048) { add("limitHits"); break; }
                    visit(array.opt(i), depth + 1);
                }
            } else if (value instanceof List<?>) {
                for (Object item : (List<?>) value) {
                    if (nodes >= 2048) { add("limitHits"); break; }
                    visit(item, depth + 1);
                }
            } else if (value instanceof Object[]) {
                for (Object item : (Object[]) value) {
                    if (nodes >= 2048) { add("limitHits"); break; }
                    visit(item, depth + 1);
                }
            } else { classifyUnsupported(value); }
        } catch (RuntimeException failure) { add("readErrors"); }
    }
    private void classifyUnsupported(Object value) {
        String category;
        if (value instanceof android.graphics.drawable.Icon) {
            // Type only. Never load a drawable, read pixels, resolve a URI or print resource identifiers.
            switch (((android.graphics.drawable.Icon) value).getType()) {
                case android.graphics.drawable.Icon.TYPE_RESOURCE: category = "unsupportedIconResource"; break;
                case android.graphics.drawable.Icon.TYPE_URI:
                case android.graphics.drawable.Icon.TYPE_URI_ADAPTIVE_BITMAP: category = "unsupportedIconUri"; break;
                case android.graphics.drawable.Icon.TYPE_BITMAP:
                case android.graphics.drawable.Icon.TYPE_ADAPTIVE_BITMAP: category = "unsupportedIconBitmap"; break;
                case android.graphics.drawable.Icon.TYPE_DATA: category = "unsupportedIconData"; break;
                default: category = "unsupportedIconOther";
            }
        } else if (value instanceof android.graphics.Bitmap) category = "unsupportedBitmap";
        else if (value instanceof byte[]) category = "unsupportedBytes";
        else if (value.getClass().isArray()) category = "unsupportedPrimitiveArray";
        else if (value instanceof android.app.PendingIntent) category = "unsupportedPendingIntent";
        else if (value instanceof android.widget.RemoteViews) category = "unsupportedRemoteViews";
        else if (value instanceof android.content.pm.ApplicationInfo) category = "unsupportedApplicationInfo";
        else category = "unsupportedFrameworkOther";
        add("unsupportedValues");
        add(category);
    }
    private void text(String value, int depth) {
        characters += value.length();
        if (value.length() > 65536 || characters > 262144) { add("limitHits"); return; }
        add("strings");
        if (expected.equals(value)) add("exactTargetValues");
        else if (value.contains(expected)) add("embeddedTargetValues"); // Clue only, not an exact replay claim.
        if (identifier != null && identifier.equals(value)) add("identifierOnlyValues");
        if (value.contains("bilibili://") || value.contains("#Intent;") || value.contains("https://"))
            add("routeLikeValues");
        String trimmed = value.trim();
        // Parsing depth is bounded BEFORE using the recursive platform JSON parser.
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && jsonDepthSafe(trimmed)) {
            try { visit(trimmed.startsWith("{") ? new JSONObject(trimmed) : new JSONArray(trimmed), depth + 1); }
            catch (org.json.JSONException failure) { add("readErrors"); }
        }
    }
    private boolean jsonDepthSafe(String value) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && c == '\\') { escaped = true; continue; }
            if (c == '"') { quoted = !quoted; continue; }
            if (!quoted && (c == '{' || c == '[') && ++depth > 8) { add("limitHits"); return false; }
            if (!quoted && (c == '}' || c == ']')) depth--;
        }
        return true;
    }
}
