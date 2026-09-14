package io.github.prince_dulb.ohmynotification.testsource;

import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;

/** Explicitly authorized temporary diagnostic. No history, persistence, sending or callbacks work. */
public final class RouteProbeListener extends NotificationListenerService {
    private static volatile RouteProbeListener connected;
    @Override public void onListenerConnected() { connected = this; }
    @Override public void onListenerDisconnected() { connected = null; }
    @Override public void onDestroy() { if (connected == this) connected = null; super.onDestroy(); }

    public static final class Bridge extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if (Binder.getCallingUid() != 2000) throw new SecurityException("Shell-only route diagnostic");
            getContext().enforceCallingPermission("android.permission.DUMP", "Diagnostic permission required");
            if ("capture-one".equals(method) || "replay-status".equals(method)) {
                long identity = Binder.clearCallingIdentity();
                try {
                    return "capture-one".equals(method) ? RouteReplayProbeActivity.capture(getContext(), arg, extras)
                            : RouteReplayProbeActivity.snapshot(getContext());
                } catch (Exception failure) {
                    // Avoid exception text/cause potentially containing the private URI.
                    throw new IllegalStateException("Single-sample operation rejected");
                } finally { Binder.restoreCallingIdentity(identity); }
            }
            boolean comparePublic = "compare-public".equals(method);
            if (!"snapshot".equals(method) && !comparePublic) throw new IllegalArgumentException("Unsupported operation");
            boolean synthetic = "synthetic".equals(arg);
            if (!synthetic && !"bilibili".equals(arg)) throw new IllegalArgumentException("Unsupported source");
            if (synthetic && !("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))) {
                throw new SecurityException("Synthetic mode is emulator-only");
            }
            String source = synthetic ? SyntheticCarrierService.SOURCE : "tv.danmaku.bili";
            RouteProbeListener listener = connected;
            if (listener == null) throw new IllegalStateException("Temporary listener is not connected");
            ArrayList<PendingIntent> actions = new ArrayList<>();
            int matched = 0;
            int withAction = 0;
            // The incoming shell identity must not be used when calling the listener-token API.
            long identity = Binder.clearCallingIdentity();
            try {
                if (comparePublic) {
                    if (getContext().checkSelfPermission("android.permission.GET_INTENT_SENDER_INTENT")
                            != android.content.pm.PackageManager.PERMISSION_DENIED)
                        throw new SecurityException("Expected ordinary helper identity");
                    if (extras == null) throw new IllegalArgumentException("Missing comparison");
                    PendingIntent original = extras.getParcelable("original", PendingIntent.class);
                    String route = extras.getString("route");
                    if (original == null || !source.equals(original.getCreatorPackage()) || route == null
                            || route.length() > 65536 || (!synthetic && !route.startsWith("bilibili://video/")))
                        throw new IllegalArgumentException("Invalid comparison");
                    android.app.Notification selected = null;
                    for (StatusBarNotification item : listener.getActiveNotifications()) {
                        if (source.equals(item.getPackageName()) && original.equals(item.getNotification().contentIntent)) {
                            if (selected != null) throw new IllegalStateException("Ambiguous active action");
                            selected = item.getNotification();
                        }
                    }
                    if (selected == null) throw new IllegalStateException("Original notification no longer active");
                    Bundle result = BoundedPublicRouteScan.compare(selected, route);
                    result.putBoolean("ordinaryHelperUid", android.os.Process.myUid() != 2000);
                    result.putBoolean("matchedSameActiveAction", true);
                    return result;
                }
                for (StatusBarNotification item : listener.getActiveNotifications()) {
                    if (!source.equals(item.getPackageName())) continue;
                    matched++;
                    PendingIntent action = item.getNotification().contentIntent;
                    if (action != null) {
                        withAction++;
                        if (actions.size() < 32) actions.add(action);
                    }
                }
            } finally { Binder.restoreCallingIdentity(identity); }
            Bundle result = new Bundle();
            result.putParcelableArrayList("actions", actions);
            result.putInt("matched", matched);
            result.putInt("withAction", withAction);
            result.putBoolean("truncated", withAction > actions.size());
            return result;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
