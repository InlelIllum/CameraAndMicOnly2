package ru.liner.sensorprivacy.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.Settings;

import androidx.annotation.NonNull;

import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;
import ru.liner.sensorprivacy.Application;
import ru.liner.sensorprivacy.R;
import ru.liner.sensorprivacy.preference.Preferences;
import ru.liner.sensorprivacy.receiver.RestartReceiver;
import ru.liner.sensorprivacy.utils.Broadcast;
import ru.liner.sensorprivacy.utils.Consumer;

/**
 * Per-app blocking service.
 * Same fix: direct IBinder.transact() for setToggleSensorPrivacy,
 * blocks ONLY camera + microphone (NOT motion sensors).
 */
public class BlockingService extends ImmortalService {
    public static final String WINDOW_CHANGE_ACTION = "ru.liner.sensorprivacy.WINDOW_CHANGE_ACTION";
    public static final String EXTRA_PACKAGE_NAME   = "package_name";
    public static final String EXTRA_CLASS_NAME     = "class_name";

    private static final int SENSOR_CAMERA      = 1;
    private static final int SENSOR_MICROPHONE  = 2;
    private static final int SOURCE_OTHER       = 0;
    private static final int USER_ID            = 0;
    private static final int TX_SET_TOGGLE      = 10; // setToggleSensorPrivacy
    private static final String DESCRIPTOR      = "android.hardware.ISensorPrivacyManager";

    private Preferences preferences;
    private Broadcast windowChangeListener;
    private android.os.IBinder rawBinder;

    private boolean setToggleDirect(int sensor, boolean enable) {
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(USER_ID);
            data.writeInt(SOURCE_OTHER);
            data.writeInt(sensor);
            data.writeInt(enable ? 1 : 0);
            rawBinder.transact(TX_SET_TOGGLE, data, reply, 0);
            reply.readException();
            return true;
        } catch (RemoteException e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = new Preferences(this);
        rawBinder = new ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService("sensor_privacy"));

        windowChangeListener = new Broadcast(this, WINDOW_CHANGE_ACTION) {
            @Override
            public void handle(@NonNull Context context, @NonNull Intent intent) {
                Consumer.of(intent.getAction()).ifPresent(input -> {
                    if (input.equals(WINDOW_CHANGE_ACTION)) {
                        boolean shouldBlock = preferences
                                .getList("blocked_application_list", String.class)
                                .contains(intent.getStringExtra(EXTRA_PACKAGE_NAME));
                        setToggleDirect(SENSOR_CAMERA,    shouldBlock);
                        setToggleDirect(SENSOR_MICROPHONE, shouldBlock);
                    }
                });
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        windowChangeListener.register();
        if (!Application.isAccessibilityServiceEnabled(this))
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        windowChangeListener.unregister();
    }

    @NonNull @Override public String getChannelID() {
        return BlockingService.class.getSimpleName();
    }
    @NonNull @Override public String getChannelDescription() {
        return "Blocks camera & mic only (not motion sensors)";
    }
    @Override public int getNotificationIcon() {
        return R.drawable.tile_icon_sensorsoff_active;
    }
    @NonNull @Override public Class<? extends BroadcastReceiver> getRestartReceiverClass() {
        return RestartReceiver.class;
    }
}
