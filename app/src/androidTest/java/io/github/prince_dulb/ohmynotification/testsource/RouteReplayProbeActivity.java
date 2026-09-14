package io.github.prince_dulb.ohmynotification.testsource;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Single authorized local sample; never exports its URI or interprets dispatch as exact landing. */
public final class RouteReplayProbeActivity extends Activity {
    private static final String FILE = "omn-route-reboot-probe.json";
    private static final String EVENTS = "omn-route-reboot-probe-events";
    private static PendingIntent originalAction;
    private TextView status;
    private boolean automaticAttempted;

    private static boolean emulator() {
        return "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE);
    }
    private static int boot(Context context) {
        int count = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (count < 0) throw new IllegalStateException("Boot count unavailable");
        return count;
    }
    private static String source(String mode) {
        if ("bilibili".equals(mode)) return "tv.danmaku.bili";
        if ("synthetic".equals(mode) && emulator()) return SyntheticCarrierService.SOURCE;
        throw new SecurityException("Unsupported diagnostic scope");
    }
    private static Intent checkedTarget(Context context, String mode, String encoded) throws Exception {
        if (encoded == null || encoded.length() > 65536) throw new IllegalArgumentException("Invalid candidate");
        String pkg = source(mode);
        Intent target;
        if ("bilibili".equals(mode)) {
            Uri uri = Uri.parse(encoded);
            if (!"bilibili".equals(uri.getScheme()) || !"video".equals(uri.getHost())
                    || uri.getPath() == null || uri.getPath().length() <= 1)
                throw new IllegalArgumentException("Only one video candidate is allowed");
            // Fresh intent, no imported flags, component, grants, selector or custom extras.
            target = new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg);
        } else {
            target = Intent.parseUri(encoded, Intent.URI_INTENT_SCHEME);
            if (target.getComponent() == null || !pkg.equals(target.getComponent().getPackageName())
                    || !target.getComponent().getClassName().endsWith(".ControlledRebootTarget")
                    || target.getStringExtra("omn.synthetic.target_token") == null)
                throw new IllegalArgumentException("Unexpected synthetic target");
            target.setFlags(0);
        }
        boolean publicTarget = false;
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(target, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (info.activityInfo != null && info.activityInfo.exported && info.activityInfo.enabled
                    && pkg.equals(info.activityInfo.packageName) && info.activityInfo.permission == null) publicTarget = true;
        }
        if (!publicTarget) throw new SecurityException("No public target");
        return target;
    }
    private static JSONObject read(Context context) throws Exception {
        File file = new File(context.getNoBackupFilesDir(), FILE);
        if (file.length() <= 0 || file.length() > 524288) throw new IllegalStateException("No valid saved sample");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = input.readNBytes(524289);
            if (bytes.length > 524288) throw new IllegalStateException("Sample exceeds bound");
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        }
    }
    static synchronized Bundle capture(Context context, String mode, Bundle input) throws Exception {
        if (input == null) throw new IllegalArgumentException("Missing candidate");
        String encoded = input.getString("route");
        PendingIntent original = input.getParcelable("original", PendingIntent.class);
        if (original == null || !original.isActivity() || !source(mode).equals(original.getCreatorPackage()))
            throw new SecurityException("Wrong original source");
        checkedTarget(context, mode, encoded);
        File file = new File(context.getNoBackupFilesDir(), FILE);
        // Single immutable sample. Re-running capture cannot silently change the target under test.
        if (file.exists()) throw new IllegalStateException("Saved sample already exists");
        JSONObject data = new JSONObject();
        data.put("mode", mode);
        data.put("route", encoded);
        data.put("boot", boot(context));
        data.put("sourceUpdate", context.getPackageManager().getPackageInfo(source(mode), 0).lastUpdateTime);
        if (!file.createNewFile()) throw new IllegalStateException("Sample already exists");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        originalAction = original;
        return snapshot(context);
    }
    static synchronized Bundle snapshot(Context context) throws Exception {
        Bundle result = new Bundle();
        result.putBoolean("saved", new File(context.getNoBackupFilesDir(), FILE).exists());
        if (result.getBoolean("saved")) {
            JSONObject data = read(context);
            result.putBoolean("rebooted", boot(context) > data.getInt("boot"));
            result.putBoolean("sourceUnchanged", context.getPackageManager()
                    .getPackageInfo(source(data.getString("mode")), 0).lastUpdateTime == data.getLong("sourceUpdate"));
        }
        result.putBoolean("ordinaryUid", context.checkSelfPermission("android.permission.GET_INTENT_SENDER_INTENT")
                != PackageManager.PERMISSION_GRANTED);
        result.putBoolean("originalAvailable", originalAction != null);
        android.content.SharedPreferences events = context.getSharedPreferences(EVENTS, Context.MODE_PRIVATE);
        for (String operation : new String[]{"original", "before", "after"}) {
            result.putLong(operation + "DispatchedAt", events.getLong(operation + "DispatchedAt", 0));
        }
        result.putBoolean("lastAttemptRejected", events.getBoolean("lastAttemptRejected", false));
        return result;
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        automaticAttempted = state != null;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding * 2, padding, padding);
        status = new TextView(this);
        status.setTextSize(18);
        status.setText("OMN 单条重启实验\n链接只存本机，不显示或上传。\n打开成功不等于内容正确，请对照实际视频。\n返回此页可继续下一步。");
        layout.addView(status);
        for (String operation : new String[]{"original", "before", "after"}) {
            Button button = new Button(this);
            button.setText("original".equals(operation) ? "打开原通知（对照）"
                    : "before".equals(operation) ? "重启前打开已保存链接" : "重启后打开已保存链接");
            button.setOnClickListener(view -> attempt(operation));
            layout.addView(button);
        }
        setContentView(layout);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        // Explicit shell launch can drive the same button path, only after the ordinary Activity is visible.
        String operation = getIntent().getStringExtra("operation");
        if (focused && !automaticAttempted && operation != null) {
            automaticAttempted = true;
            attempt(operation);
        }
    }
    private void attempt(String operation) {
        try {
            if (!"original".equals(operation) && !"before".equals(operation) && !"after".equals(operation))
                throw new IllegalArgumentException("Unsupported phase");
            if (checkSelfPermission("android.permission.GET_INTENT_SENDER_INTENT") == PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Replay must use ordinary application permissions");
            JSONObject data = read(this);
            int capturedBoot = data.getInt("boot");
            if ("after".equals(operation) ? boot(this) <= capturedBoot : boot(this) != capturedBoot)
                throw new IllegalStateException("Wrong reboot phase");
            if (getPackageManager().getPackageInfo(source(data.getString("mode")), 0).lastUpdateTime != data.getLong("sourceUpdate"))
                throw new IllegalStateException("Source was updated");
            if ("original".equals(operation)) {
                PendingIntent original = originalAction;
                if (original == null) throw new IllegalStateException("Original no longer in memory");
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE);
                original.send(this, 0, null, null, null, null, options.toBundle());
            } else {
                startActivity(checkedTarget(this, data.getString("mode"), data.getString("route")));
            }
            getSharedPreferences(EVENTS, MODE_PRIVATE).edit().putLong(operation + "DispatchedAt", System.currentTimeMillis())
                    .putBoolean("lastAttemptRejected", false).commit();
            status.setText("已请求打开。请核对实际内容；这条状态不代表精确落页已验证。");
        } catch (Exception failure) {
            getSharedPreferences(EVENTS, MODE_PRIVATE).edit().putBoolean("lastAttemptRejected", true).commit();
            status.setText("本次未通过阶段/权限/目标检查，或启动被拒绝。没有改写已保存链接。");
        }
    }
}
