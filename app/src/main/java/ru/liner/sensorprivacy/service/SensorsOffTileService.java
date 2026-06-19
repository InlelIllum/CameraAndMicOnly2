package ru.liner.sensorprivacy.service;

import static ru.liner.sensorprivacy.Application.preferences;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.hardware.ISensorPrivacyManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.NonNull;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;
import ru.liner.sensorprivacy.R;
import ru.liner.sensorprivacy.preference.PreferenceListener;
import ru.liner.sensorprivacy.shizuku.ShizukuState;

/**
 * Blocks ONLY Camera (sensor=1) and Microphone (sensor=2).
 * Motion sensors, accelerometer, gyroscope etc. are NOT affected.
 *
 * Key fix for MIUI 13: uses direct IBinder.transact() for setToggleSensorPrivacy
 * (transaction code 10 / 0xa) instead of the Java interface.
 * MIUI 13's framework.jar may not expose setToggleSensorPrivacy in the Java
 * interface, but the underlying Binder service still supports this transaction
 * (evidenced by camera/mic privacy indicators working on MIUI 13).
 *
 * Transaction code map (from compiled AIDL smali):
 *   6  = isSensorPrivacyEnabled
 *   9  = setSensorPrivacy        (global, blocks ALL sensors — NOT used anymore)
 *   10 = setToggleSensorPrivacy  (per-sensor — camera or mic only) ← used via direct transact
 */
public class SensorsOffTileService extends TileService implements
        Shizuku.OnRequestPermissionResultListener,
        Shizuku.OnBinderReceivedListener,
        Shizuku.OnBinderDeadListener {

    private static final int REQUEST_CODE_SHIZUKU = 9988;

    private static final int SENSOR_CAMERA      = 1;
    private static final int SENSOR_MICROPHONE  = 2;
    private static final int SOURCE_OTHER       = 0;
    private static final int USER_ID            = 0;

    // Transaction code 10 (0xa) = setToggleSensorPrivacy(userId, source, sensor, enable)
    private static final int TX_SET_TOGGLE_SENSOR_PRIVACY = 10;

    private static final String AIDL_DESCRIPTOR =
            "android.hardware.ISensorPrivacyManager";

    private ISensorPrivacyManager sensorPrivacyManager;
    private IBinder rawBinder;
    private KeyguardManager keyguardManager;
    private boolean privacyEnabled;
    private Icon activeIcon;
    private Icon inactiveIcon;
    private Icon warningIcon;
    private Icon stopIcon;
    @ShizukuState
    private int shizukuState;

    @Override
    public void onCreate() {
        super.onCreate();
        Context context = getApplicationContext();
        initBinders();
        keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);

        preferences.register(new PreferenceListener<Boolean>() {
            @NonNull @Override public String key() { return "privacy_state"; }
            @Override public void onChanged(@NonNull Boolean newValue) { setPrivacyEnabled(newValue); }
            @NonNull @Override public Boolean defaultValue() { return false; }
        });

        activeIcon   = Icon.createWithResource(context, R.drawable.tile_icon_sensorsoff_active);
        inactiveIcon = Icon.createWithResource(context, R.drawable.tile_icon_sensorsoff_inactive);
        warningIcon  = Icon.createWithResource(context, R.drawable.tile_icon_warning);
        stopIcon     = Icon.createWithResource(context, R.drawable.tile_icon_stop);
    }

    private void initBinders() {
        rawBinder = new ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService("sensor_privacy"));
        sensorPrivacyManager = ISensorPrivacyManager.Stub.asInterface(rawBinder);
    }

    @Override
    public void onStartListening() {
        shizukuState = checkShizukuState();
        // Read state from preferences only — no system calls here
        // (avoids MIUI 13 Java interface incompatibilities on tile open)
        privacyEnabled = preferences.get("privacy_enabled", false);
        updateUI();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
    }

    @Override
    public void onClick() {
        setPrivacyEnabled(!privacyEnabled);
    }

    /**
     * Sends setToggleSensorPrivacy directly via IBinder.transact() — bypasses
     * the Java interface class in framework.jar, which may be MIUI-trimmed.
     *
     * @param sensor  1 = camera, 2 = microphone
     * @param enable  true = block, false = unblock
     * @return true if the Binder call succeeded without exception
     */
    private boolean setToggleSensorPrivacyDirect(int sensor, boolean enable) {
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(AIDL_DESCRIPTOR);
            data.writeInt(USER_ID);
            data.writeInt(SOURCE_OTHER);
            data.writeInt(sensor);
            data.writeInt(enable ? 1 : 0);
            rawBinder.transact(TX_SET_TOGGLE_SENSOR_PRIVACY, data, reply, 0);
            reply.readException();
            return true;
        } catch (RemoteException e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Blocks or unblocks ONLY camera + microphone.
     * Does NOT call setSensorPrivacy() — that would block motion sensors too.
     */
    private void setPrivacyEnabled(boolean enabled) {
        shizukuState = checkShizukuState();
        if (shizukuState == ShizukuState.NORMAL) {
            if (!keyguardManager.isKeyguardLocked()) {
                boolean cameraOk = setToggleSensorPrivacyDirect(SENSOR_CAMERA,     enabled);
                boolean micOk    = setToggleSensorPrivacyDirect(SENSOR_MICROPHONE, enabled);

                if (cameraOk || micOk) {
                    privacyEnabled = enabled;
                } else {
                    // Direct Binder transact failed for both — mark as unsupported
                    privacyEnabled = false;
                    shizukuState = ShizukuState.UNKNOWN;
                }
                preferences.put("privacy_enabled", privacyEnabled);
            }
        }
        updateUI();
    }

    private void updateUI() {
        Tile tile = getQsTile();
        if (tile == null) return;
        switch (shizukuState) {
            case ShizukuState.NORMAL:
                tile.setState(privacyEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
                tile.setIcon(privacyEnabled ? activeIcon : inactiveIcon);
                tile.setLabel(getString(privacyEnabled
                        ? R.string.tile_sensors_disabled
                        : R.string.tile_sensors_enabled));
                break;
            case ShizukuState.PERMISSION_WAIT:
            case ShizukuState.PERMISSION_DENIED:
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setIcon(warningIcon);
                tile.setLabel(getString(R.string.tile_permission_required));
                break;
            case ShizukuState.BINDER_DEAD:
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setIcon(warningIcon);
                tile.setLabel(getString(R.string.tile_shizuku_not_working));
                break;
            case ShizukuState.UNKNOWN:
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setIcon(stopIcon);
                tile.setLabel(getString(R.string.tile_shizuku_error));
                break;
        }
        tile.updateTile();
    }

    @Override
    public IBinder onBind(Intent intent) {
        TileService.requestListeningState(
                this, new ComponentName(this, SensorsOffTileService.class));
        return super.onBind(intent);
    }

    private boolean checkShizukuPermission() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
        if (Shizuku.isPreV11()) return false;
        if (Shizuku.shouldShowRequestPermissionRationale()) return false;
        Shizuku.requestPermission(REQUEST_CODE_SHIZUKU);
        return false;
    }

    @ShizukuState
    private int checkShizukuState() {
        if (Shizuku.pingBinder()) {
            Shizuku.addRequestPermissionResultListener(this);
            return checkShizukuPermission() ? ShizukuState.NORMAL : ShizukuState.PERMISSION_WAIT;
        } else {
            Shizuku.addBinderReceivedListener(this);
            return ShizukuState.BINDER_DEAD;
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, int grantResult) {
        shizukuState = (requestCode == REQUEST_CODE_SHIZUKU
                && grantResult == PackageManager.PERMISSION_GRANTED)
                ? ShizukuState.NORMAL : ShizukuState.PERMISSION_DENIED;
        Shizuku.removeRequestPermissionResultListener(this);
        updateUI();
    }

    @Override
    public void onBinderReceived() {
        initBinders(); // Re-initialize both binders on Shizuku reconnect
        shizukuState = checkShizukuState();
        Shizuku.removeBinderReceivedListener(this);
        updateUI();
    }

    @Override
    public void onBinderDead() {
        shizukuState = ShizukuState.BINDER_DEAD;
        Shizuku.addBinderReceivedListener(this);
        updateUI();
    }
}
