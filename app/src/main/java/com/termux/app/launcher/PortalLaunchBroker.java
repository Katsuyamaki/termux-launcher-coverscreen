package com.termux.app.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

final class PortalLaunchBroker {
    private static final String TAG = "PortalLaunchBroker";
    private static final ComponentName BROKER_SERVICE = new ComponentName(
        "com.katsuyamaki.portallauncher",
        "com.katsuyamaki.portallauncher.PortalLaunchBrokerService"
    );
    private static final String DESCRIPTOR = "com.katsuyamaki.portallauncher.PortalLaunchBroker";
    private static final int TRANSACTION_LAUNCH = IBinder.FIRST_CALL_TRANSACTION;

    private PortalLaunchBroker() {
    }

    static boolean launch(@NonNull Context context, @NonNull ComponentName target, int displayId) {
        Intent brokerIntent = new Intent().setComponent(BROKER_SERVICE);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                int result = transactLaunch(service, target, displayId);
                Log.i(TAG, "Broker result=" + result + " target=" + target.flattenToShortString()
                    + " display=" + displayId);
                safeUnbind(context, this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "Portal broker disconnected before explicit unbind");
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Log.w(TAG, "Portal broker returned a null binding");
                safeUnbind(context, this);
            }
        };

        int bindFlags = Context.BIND_AUTO_CREATE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            bindFlags |= Context.BIND_ALLOW_ACTIVITY_STARTS;
        }

        try {
            boolean bound = context.bindService(brokerIntent, connection, bindFlags);
            if (!bound) {
                Log.w(TAG, "Portal broker bind was rejected");
            }
            return bound;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Portal broker bind failed", e);
            return false;
        }
    }

    private static int transactLaunch(@NonNull IBinder service,
                                      @NonNull ComponentName target,
                                      int displayId) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(target.getPackageName());
            data.writeString(target.getClassName());
            data.writeInt(displayId);

            if (!service.transact(TRANSACTION_LAUNCH, data, reply, 0)) {
                return -1;
            }
            reply.readException();
            return reply.readInt();
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Portal broker transaction failed", e);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void safeUnbind(@NonNull Context context, @NonNull ServiceConnection connection) {
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
