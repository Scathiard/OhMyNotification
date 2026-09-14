package io.github.prince_dulb.ohmynotification.testsource;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

/** Three-UID synthetic experiment, never a production component or disk persistence format. */
public final class SyntheticCarrierService extends Service {
    public static final String TARGET = "io.github.prince_dulb.ohmynotification";
    public static final String SOURCE = TARGET + ".sourcefixture";
    public static final String DESCRIPTOR = "omn.synthetic.independent.carrier.v1";
    public static final int PUT = IBinder.FIRST_CALL_TRANSACTION;
    public static final int GET = PUT + 1;
    public static final int CLEAR = PUT + 2;
    private static final String CHANNEL = "omn_synthetic_independent_carrier";
    private static final String TAG = "omn.synthetic.independent.carrier";
    private static final int ID = 0x0D50;

    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            if (code < PUT || code > CLEAR) return super.onTransact(code, data, reply, flags);
            authenticate();
            data.enforceInterface(DESCRIPTOR);
            Bundle request = data.readTypedObject(Bundle.CREATOR);
            data.enforceNoDataAvail();
            Bundle result;
            if (code == PUT) result = put(request);
            else if (code == GET) result = current();
            else {
                Bundle stored = current();
                if (request == null || !stored.getString("nonce").equals(request.getString("nonce"))) {
                    throw new SecurityException("Synthetic carrier nonce mismatch");
                }
                manager().cancel(TAG, ID);
                result = new Bundle();
            }
            reply.writeNoException();
            reply.writeTypedObject(result, 0);
            return true;
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }

    private void authenticate() {
        if (!("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))
                || !getPackageName().equals(TARGET + ".test")) {
            throw new SecurityException("Synthetic carrier is restricted to its emulator test holder");
        }
        int caller = Binder.getCallingUid();
        try {
            int allowed = getPackageManager().getApplicationInfo(TARGET, 0).uid;
            if (caller != allowed || caller == Process.myUid()
                    || getPackageManager().checkSignatures(caller, Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
                throw new SecurityException("Synthetic carrier caller rejected");
            }
        } catch (PackageManager.NameNotFoundException absent) { throw new SecurityException(absent); }
    }

    private NotificationManager manager() { return getSystemService(NotificationManager.class); }

    private Bundle put(Bundle request) {
        if (request == null || request.getString("nonce") == null
                || !request.getString("nonce").matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Missing nonce");
        if (!manager().areNotificationsEnabled()) throw new IllegalStateException("Holder notifications unavailable");
        if (find() != null) throw new IllegalStateException("Do not overwrite a prior synthetic carrier");
        PendingIntent action = request.getParcelable("handle", PendingIntent.class);
        if (action == null || !action.isActivity() || !SOURCE.equals(action.getCreatorPackage())
                || action.getCreatorUid() == Process.myUid() || action.getCreatorUid() == Binder.getCallingUid()) {
            throw new SecurityException("Expected a third-UID synthetic Activity capability: present=" + (action != null)
                    + ",activity=" + (action != null && action.isActivity())
                    + ",sourceMatches=" + (action != null && SOURCE.equals(action.getCreatorPackage()))
                    + ",creatorUid=" + (action == null ? -1 : action.getCreatorUid())
                    + ",holderUid=" + Process.myUid() + ",callerUid=" + Binder.getCallingUid());
        }
        Bundle stored = new Bundle(request);
        stored.putInt("publishedHolderPid", Process.myPid());
        stored.putInt("holderUid", Process.myUid());
        stored.putInt("callerUid", Binder.getCallingUid());
        stored.putInt("creatorUid", action.getCreatorUid());
        manager().createNotificationChannel(new NotificationChannel(CHANNEL, "OMN 三 UID 合成实验", NotificationManager.IMPORTANCE_MIN));
        manager().notify(TAG, ID, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("OMN 三 UID 合成实验")
                .setContentText("仅合成动作，等待主测试包覆盖安装")
                .setVisibility(Notification.VISIBILITY_SECRET).setLocalOnly(true)
                .setOngoing(true).setOnlyAlertOnce(true).addExtras(stored).build());
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (find() != null) {
                Bundle returned = current();
                if (!action.equals(returned.getParcelable("handle", PendingIntent.class))) {
                    throw new IllegalStateException("System readback capability mismatch");
                }
                return returned;
            }
            SystemClock.sleep(50);
        }
        throw new IllegalStateException("System did not retain the synthetic carrier");
    }

    private Notification find() {
        for (StatusBarNotification entry : manager().getActiveNotifications()) {
            if (entry.getId() == ID && TAG.equals(entry.getTag())) return entry.getNotification();
        }
        return null;
    }

    private Bundle current() {
        Notification carrier = find();
        if (carrier == null) throw new IllegalStateException("Independent synthetic carrier absent");
        Bundle result = new Bundle(carrier.extras);
        result.putInt("holderPid", Process.myPid());
        return result;
    }
}
