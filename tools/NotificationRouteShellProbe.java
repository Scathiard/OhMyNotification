import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.Looper;
import android.os.Bundle;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only shell diagnostic, deliberately outside the product build. Never sends an action.
 * Default uses the authorized test listener bridge. Optional direct mode preserves the
 * ACCESS_NOTIFICATIONS negative control. Explicit capture-one stores just one authorized
 * target in the test helper's no-backup directory. This shell process never launches targets.
 */
public final class NotificationRouteShellProbe {
    private static String stage = "identity";
    public static void main(String[] args) {
        try {
            if (Process.myUid() != 2000) throw new SecurityException("Expected existing ADB shell identity");
            if (args.length < 1 || args.length > 2 || !(args[0].equals("bilibili") || args[0].equals("synthetic"))
                    || (args.length == 2 && !args[1].equals("direct") && !args[1].equals("capture-one")
                        && !args[1].equals("replay-status") && !args[1].equals("compare-public"))) {
                throw new IllegalArgumentException("Usage: NotificationRouteShellProbe bilibili|synthetic [direct|capture-one|replay-status|compare-public]");
            }
            boolean synthetic = args[0].equals("synthetic");
            if (synthetic && !(android.os.Build.HARDWARE.equals("ranchu") || android.os.Build.HARDWARE.equals("goldfish"))) {
                throw new SecurityException("Synthetic mode is emulator-only");
            }
            String source = synthetic ? "io.github.prince_dulb.ohmynotification.sourcefixture" : "tv.danmaku.bili";
            Looper.prepareMainLooper();
            stage = "systemContext";
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            Context context = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
            context = context.createPackageContext("com.android.shell", 0);
            if (args.length == 2 && args[1].equals("replay-status")) {
                printReplayStatus(bridgeCall("replay-status", args[0], null));
                return;
            }
            boolean captureOne = args.length == 2 && args[1].equals("capture-one");
            boolean comparePublic = args.length == 2 && args[1].equals("compare-public");
            String selectedRoute = null;
            PendingIntent selectedOriginal = null;
            java.util.ArrayList<PendingIntent> actions = new java.util.ArrayList<>();
            int matched = 0;
            int withAction = 0;
            if (args.length == 2 && args[1].equals("direct")) {
            stage = "notificationService";
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                    .invoke(null, "notification");
            Object service = Class.forName("android.app.INotificationManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            stage = "activeNotifications";
            StatusBarNotification[] items = (StatusBarNotification[]) Class.forName("android.app.INotificationManager")
                    .getMethod("getActiveNotifications", String.class).invoke(service, "com.android.shell");
            for (StatusBarNotification item : items) {
                if (!source.equals(item.getPackageName())) continue;
                matched++;
                if (item.getNotification().contentIntent != null) { withAction++; actions.add(item.getNotification().contentIntent); }
            }
            } else {
                stage = "listenerBridge";
                String authority = "io.github.prince_dulb.ohmynotification.test.routeprobe";
                Bundle snapshot;
                Class<?> managerApi = Class.forName("android.app.IActivityManager");
                Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
                IBinder token = new android.os.Binder();
                Object holder = managerApi.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                        .invoke(manager, authority, 0, token, "omn-route-probe");
                if (holder == null) throw new IllegalStateException("Missing test bridge");
                try {
                    // Same external-client path as the system content shell command; no bound Application.
                    Object provider = holder.getClass().getField("provider").get(holder);
                    snapshot = (Bundle) Class.forName("android.content.IContentProvider").getMethod("call",
                            android.content.AttributionSource.class, String.class, String.class, String.class, Bundle.class)
                            .invoke(provider, new android.content.AttributionSource.Builder(Process.myUid())
                                    .setPackageName("com.android.shell").build(), authority, "snapshot", args[0], null);
                } finally {
                    managerApi.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                            .invoke(manager, authority, token, 0);
                }
                if (snapshot == null || snapshot.getBoolean("truncated")) throw new IllegalStateException("Incomplete snapshot");
                matched = snapshot.getInt("matched");
                withAction = snapshot.getInt("withAction");
                actions = snapshot.getParcelableArrayList("actions", PendingIntent.class);
                if (actions == null) throw new IllegalStateException("Missing action collection");
            }
            Method extract = PendingIntent.class.getMethod("getIntent");
            stage = "inspect";
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String name : new String[]{"matched", "withAction", "creatorMatches", "extracted", "withScheme",
                    "bilibiliUri", "videoUri", "liveUri", "otherBilibiliUri", "exportedUriHandlers",
                    "primitiveUriRoundTrip", "syntheticExactTarget", "activityActions", "broadcastActions",
                    "dataPresent", "stringExtras", "bundleExtras", "otherExtras", "intentUriExtras",
                    "bilibiliTextExtras", "httpTextExtras", "jsonExtras", "failures"}) counts.put(name, 0);
            counts.put("matched", matched);
            counts.put("withAction", withAction);
            for (PendingIntent action : actions) {
                if (action.isActivity()) add(counts, "activityActions");
                if (action.isBroadcast()) add(counts, "broadcastActions");
                if (!source.equals(action.getCreatorPackage())) continue;
                add(counts, "creatorMatches");
                try {
                    Intent original = (Intent) extract.invoke(action);
                    if (original == null) continue;
                    add(counts, "extracted");
                    if (original.getData() != null) add(counts, "dataPresent");
                    // Component class is software structure, never include its data/extras in output.
                    if (original.getComponent() != null) {
                        String name = original.getComponent().getClassName();
                        if (name.matches("[A-Za-z0-9_.$]{1,220}")) counts.merge("componentClass:" + name, 1, Integer::sum);
                    }
                    Bundle extras = original.getExtras();
                    if (extras != null && extras.get("mipush_serviceIntent") instanceof Intent) {
                        Intent nested = (Intent) extras.get("mipush_serviceIntent");
                        Bundle nestedExtras = nested.getExtras();
                        if (nestedExtras != null) for (String nestedKey : nestedExtras.keySet()) {
                            Object nestedValue = nestedExtras.get(nestedKey);
                            if (nestedValue != null) counts.merge("nestedType:" + nestedValue.getClass().getName(), 1, Integer::sum);
                            if (nestedValue instanceof String) {
                                String text = (String) nestedValue;
                                if (text.contains("bilibili://")) counts.merge("nestedBilibiliText", 1, Integer::sum);
                            }
                            if ("mipush_payload".equals(nestedKey) && nestedValue instanceof byte[]) {
                                byte[] bytes = (byte[]) nestedValue;
                                if (bytes.length <= 1048576) {
                                    String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                                    counts.merge("boundedMipushPayload", 1, Integer::sum);
                                    if (text.contains("bilibili://")) counts.merge("payloadBilibiliText", 1, Integer::sum);
                                    if (text.contains("intent_uri")) counts.merge("payloadIntentUriKey", 1, Integer::sum);
                                    try {
                                        java.util.List<MipushRouteBinaryProbe.Candidate> routes = MipushRouteBinaryProbe.parse(bytes);
                                        counts.merge("payloadFullyParsed", 1, Integer::sum);
                                        counts.merge("payloadRouteEntries", routes.size(), Integer::sum);
                                        for (MipushRouteBinaryProbe.Candidate candidate : routes) {
                                            counts.merge("payloadRoutePath:" + candidate.path, 1, Integer::sum);
                                            Intent target = Intent.parseUri(candidate.route, Intent.URI_INTENT_SCHEME);
                                            counts.merge("payloadIntentParsed", 1, Integer::sum);
                                            if (target.getComponent() != null) {
                                                String name = target.getComponent().getClassName();
                                                if (name.matches("[A-Za-z0-9_.$]{1,220}"))
                                                    counts.merge("payloadComponentClass:" + name, 1, Integer::sum);
                                            }
                                            String innerScheme = target.getStringExtra("scheme");
                                            Uri innerRoute = innerScheme == null ? target.getData() : Uri.parse(innerScheme);
                                            if (innerRoute != null && "bilibili".equals(innerRoute.getScheme())) {
                                                counts.merge("payloadBilibiliRoute", 1, Integer::sum);
                                                String kind = "video".equals(innerRoute.getHost()) ? "Video"
                                                        : "live".equals(innerRoute.getHost()) ? "Live" : "Other";
                                                counts.merge("payload" + kind + "Route", 1, Integer::sum);
                                                Intent view = new Intent(Intent.ACTION_VIEW, innerRoute).setPackage(source);
                                                Intent copy = Intent.parseUri(view.toUri(Intent.URI_INTENT_SCHEME), Intent.URI_INTENT_SCHEME);
                                                if (innerRoute.equals(copy.getData()) && source.equals(copy.getPackage()))
                                                    counts.merge("payloadScalarRoundTrip", 1, Integer::sum);
                                                boolean exported = false;
                                                for (ResolveInfo match : context.getPackageManager().queryIntentActivities(view, 0)) {
                                                    if (match.activityInfo != null && match.activityInfo.exported && match.activityInfo.enabled
                                                            && source.equals(match.activityInfo.packageName) && match.activityInfo.permission == null)
                                                        exported = true;
                                                }
                                                if (exported) counts.merge("payloadExportedHandler", 1, Integer::sum);
                                                if ((captureOne || comparePublic) && selectedRoute == null && exported && "video".equals(innerRoute.getHost())) {
                                                    selectedRoute = innerRoute.toString();
                                                    selectedOriginal = action;
                                                }
                                            }
                                        }
                                    } catch (Exception parseFailure) { counts.merge("payloadParseRejected", 1, Integer::sum); }
                                }
                            }
                        }
                    }
                    if (extras != null) for (String key : extras.keySet()) {
                        Object value = extras.get(key);
                        if (value instanceof String) {
                            add(counts, "stringExtras");
                            String text = (String) value;
                            if (text.length() > 65536) continue;
                            if (text.contains("#Intent;")) add(counts, "intentUriExtras");
                            if (text.contains("bilibili://")) add(counts, "bilibiliTextExtras");
                            if (text.contains("https://") || text.contains("http://")) add(counts, "httpTextExtras");
                            if (text.trim().startsWith("{")) add(counts, "jsonExtras");
                        } else if (value instanceof Bundle) add(counts, "bundleExtras");
                        else {
                            add(counts, "otherExtras");
                            if (value != null) counts.merge("extraType:" + value.getClass().getName(), 1, Integer::sum);
                        }
                        for (String known : new String[]{"aid", "avid", "bvid", "id", "mipush_serviceIntent", "mipush_payload",
                                "intent_uri", "web_uri", "url", "notify_effect", "scheme"}) {
                            if (known.equals(key)) counts.merge("knownField:" + known, 1, Integer::sum);
                        }
                    }
                    if (synthetic) {
                        String token = original.getStringExtra("omn.synthetic.target_token");
                        if ("action".equals(original.getStringExtra("omn.synthetic.case_id")) && token != null
                                && token.startsWith("OMN_SYNTHETIC_REBOOT_") && original.getComponent() != null
                                && source.equals(original.getComponent().getPackageName())) add(counts, "syntheticExactTarget");
                        if ((captureOne || comparePublic) && selectedRoute == null && original.getComponent() != null
                                && original.getComponent().getClassName().endsWith(".ControlledRebootTarget")) {
                            selectedRoute = original.toUri(Intent.URI_INTENT_SCHEME);
                            selectedOriginal = action;
                        }
                    }
                    String scheme = original.getStringExtra("scheme");
                    if (scheme != null && !scheme.isEmpty()) add(counts, "withScheme");
                    Uri route = scheme == null ? original.getData() : Uri.parse(scheme);
                    if (route == null || !"bilibili".equals(route.getScheme())) continue;
                    add(counts, "bilibiliUri");
                    if ("video".equals(route.getHost())) add(counts, "videoUri");
                    else if ("live".equals(route.getHost())) add(counts, "liveUri");
                    else add(counts, "otherBilibiliUri");
                    Intent view = new Intent(Intent.ACTION_VIEW, route).setPackage(source);
                    Intent decoded = Intent.parseUri(view.toUri(Intent.URI_INTENT_SCHEME), Intent.URI_INTENT_SCHEME);
                    if (route.equals(decoded.getData()) && source.equals(decoded.getPackage())) add(counts, "primitiveUriRoundTrip");
                    boolean publicHandler = false;
                    for (ResolveInfo match : context.getPackageManager().queryIntentActivities(view, 0)) {
                        if (match.activityInfo != null && match.activityInfo.exported && match.activityInfo.enabled
                                && source.equals(match.activityInfo.packageName) && match.activityInfo.permission == null) {
                            publicHandler = true;
                        }
                    }
                    if (publicHandler) add(counts, "exportedUriHandlers");
                } catch (Throwable failure) { add(counts, "failures"); }
            }
            for (Map.Entry<String, Integer> entry : counts.entrySet()) System.out.println(entry.getKey() + "=" + entry.getValue());
            if (comparePublic) {
                if (selectedRoute == null || selectedOriginal == null)
                    throw new IllegalStateException("No comparison reference");
                Bundle request = new Bundle();
                request.putString("route", selectedRoute);
                request.putParcelable("original", selectedOriginal);
                stage = "comparePublic";
                Bundle result = bridgeCall("compare-public", args[0], request);
                if (result == null || !result.getBoolean("matchedSameActiveAction") || !result.getBoolean("ordinaryHelperUid"))
                    throw new IllegalStateException("Missing ordinary-UID comparison receipt");
                for (String key : new String[]{"publicRouteFieldComparison", "ordinaryHelperUid", "matchedSameActiveAction", "completeWithinDeclaredFields"})
                    System.out.println(key + "=" + result.getBoolean(key));
                for (String key : new String[]{"scannedNodes", "strings", "uris", "bundles", "intents", "jsonContainers",
                        "exactTargetValues", "embeddedTargetValues", "identifierOnlyValues", "routeLikeValues",
                        "unsupportedValues", "readErrors", "limitHits", "unsupportedIconResource", "unsupportedIconUri",
                        "unsupportedIconBitmap", "unsupportedIconData", "unsupportedIconOther", "unsupportedBitmap",
                        "unsupportedBytes", "unsupportedPrimitiveArray", "unsupportedPendingIntent", "unsupportedRemoteViews",
                        "unsupportedFrameworkOther", "unsupportedNonFramework", "unsupportedApplicationInfo"})
                    System.out.println(key + "=" + result.getInt(key));
            }
            if (captureOne) {
                if (selectedRoute == null || selectedOriginal == null || counts.get("failures") != 0)
                    throw new IllegalStateException("No unambiguous authorized candidate");
                Bundle request = new Bundle();
                request.putString("route", selectedRoute);
                request.putParcelable("original", selectedOriginal);
                stage = "captureOne";
                Bundle saved = bridgeCall("capture-one", args[0], request);
                if (saved == null || !saved.getBoolean("saved")) throw new IllegalStateException("No save receipt");
                printReplayStatus(saved);
            }
            System.out.println("actionLaunches=0");
            System.out.println("savedRealTargets=" + (captureOne && !synthetic ? 1 : 0));
            if (counts.get("matched") == 0 || counts.get("extracted") == 0 || counts.get("failures") != 0) System.exit(2);
        } catch (Throwable failure) {
            StringBuilder classes = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (classes.length() > 0) classes.append(" -> ");
                classes.append(cause.getClass().getSimpleName());
            }
            System.err.println("probeFailureClasses=" + classes);
            System.err.println("probeFailureStage=" + stage);
            for (String permission : new String[]{"ACCESS_NOTIFICATIONS", "GET_INTENT_SENDER_INTENT", "INTERACT_ACROSS_USERS", "DUMP", "android does not belong", "Shell-only"}) {
                boolean mentioned = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause.getMessage() != null && cause.getMessage().contains(permission)) mentioned = true;
                }
                System.err.println("mentions" + permission + "=" + mentioned);
            }
            System.exit(1);
        }
    }
    private static Bundle bridgeCall(String method, String mode, Bundle input) throws Exception {
        String authority = "io.github.prince_dulb.ohmynotification.test.routeprobe";
        Class<?> managerApi = Class.forName("android.app.IActivityManager");
        Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        IBinder token = new android.os.Binder();
        Object holder = managerApi.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                .invoke(manager, authority, 0, token, "omn-route-probe");
        if (holder == null) throw new IllegalStateException("Missing test bridge");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            return (Bundle) Class.forName("android.content.IContentProvider").getMethod("call",
                    android.content.AttributionSource.class, String.class, String.class, String.class, Bundle.class)
                    .invoke(provider, new android.content.AttributionSource.Builder(Process.myUid())
                            .setPackageName("com.android.shell").build(), authority, method, mode, input);
        } finally {
            managerApi.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(manager, authority, token, 0);
        }
    }
    private static void printReplayStatus(Bundle result) {
        if (result == null) throw new IllegalStateException("No status");
        for (String key : new String[]{"saved", "rebooted", "sourceUnchanged", "ordinaryUid", "originalAvailable", "lastAttemptRejected"})
            System.out.println(key + "=" + result.getBoolean(key));
        for (String key : new String[]{"originalDispatchedAt", "beforeDispatchedAt", "afterDispatchedAt"})
            System.out.println(key + "=" + result.getLong(key));
    }
    private static void add(Map<String, Integer> counts, String key) { counts.put(key, counts.get(key) + 1); }
}
