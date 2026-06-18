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
 * Fix: removed isCombinedToggleSensorPrivacyEnabled() — not present in MIUI 13.
 * State is tracked via preferences (same as original app).
 */
public class SensorsOffTileService extends TileService implements
        Shizuku.OnRequestPermissionResultListener,
        Shizuku.OnBinderReceivedListener,
        Shizuku.OnBinderDeadListener {

    private static final int REQUEST_CODE_SHIZUKU = 9988;

    // Android 12 SensorPrivacyManager.Sensors constants
    private static final int SENSOR_CAMERA     = 1;
    private static final int SENSOR_MICROPHONE = 2;
    // SensorPrivacyManager.Sources.OTHER
    private static final int SOURCE_OTHER      = 0;
    // Primary user ID
    private static final int USER_ID           = 0;

    private ISensorPrivacyManager sensorPrivacyManager;
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
        sensorPrivacyManager = ISensorPrivacyManager.Stub.asInterface(
                new ShizukuBinderWrapper(
                        SystemServiceHelper.getSystemService("sensor_privacy")));
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

    @Override
    public void onStartListening() {
        shizukuState = checkShizukuState();
        // Read state from preferences only — avoids calling MIUI-incompatible methods
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
     * Blocks/unblocks ONLY camera + microphone using setToggleSensorPrivacy().
     * Falls back to setSensorPrivacy() if MIUI doesn't support per-sensor toggle.
     */
    private void setPrivacyEnabled(boolean enabled) {
        shizukuState = checkShizukuState();
        if (shizukuState == ShizukuState.NORMAL) {
            if (!keyguardManager.isKeyguardLocked()) {
                try {
                    privacyEnabled = enabled;
                    // Per-sensor toggle: camera only + microphone only
                    sensorPrivacyManager.setToggleSensorPrivacy(
                            USER_ID, SOURCE_OTHER, SENSOR_CAMERA,     privacyEnabled);
                    sensorPrivacyManager.setToggleSensorPrivacy(
                            USER_ID, SOURCE_OTHER, SENSOR_MICROPHONE, privacyEnabled);
                } catch (RemoteException e) {
                    privacyEnabled = false;
                    shizukuState = checkShizukuState();
                } catch (Throwable e) {
                    // Fallback: if MIUI doesn't have setToggleSensorPrivacy,
                    // fall back to global setSensorPrivacy (blocks all sensors)
                    try {
                        sensorPrivacyManager.setSensorPrivacy(privacyEnabled);
                    } catch (RemoteException ignored) {
                        privacyEnabled = false;
                        shizukuState = checkShizukuState();
                    }
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
        // Re-initialize binder on Shizuku reconnect
        sensorPrivacyManager = ISensorPrivacyManager.Stub.asInterface(
                new ShizukuBinderWrapper(
                        SystemServiceHelper.getSystemService("sensor_privacy")));
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
