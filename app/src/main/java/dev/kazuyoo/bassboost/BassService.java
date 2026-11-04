package dev.kazuyoo.bassboost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.audiofx.BassBoost;
import android.media.audiofx.LoudnessEnhancer;
import android.os.IBinder;
import android.util.Log;

public class BassService extends Service {

    private static final String TAG     = "BassService";
    private static final String CH_ID   = "bassboost";
    private static final int    NOTIF   = 1;

    /* Intent actions untuk update config dari luar */
    public static final String ACTION_SET_BASS_STRENGTH    = "dev.kazuyoo.bassboost.SET_BASS_STRENGTH";
    public static final String ACTION_SET_LOUDNESS_TARGET  = "dev.kazuyoo.bassboost.SET_LOUDNESS_TARGET";
    public static final String ACTION_SET_BASS_ENABLED     = "dev.kazuyoo.bassboost.SET_BASS_ENABLED";
    public static final String ACTION_SET_LOUDNESS_ENABLED = "dev.kazuyoo.bassboost.SET_LOUDNESS_ENABLED";
    public static final String EXTRA_VALUE                 = "value";

    private BassBoost        mBass;
    private LoudnessEnhancer mLoudness;
    private Config           mConfig;

    @Override
    public void onCreate() {
        super.onCreate();
        mConfig = new Config(this);
        startFg();
        applyEffects();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            int val = intent.getIntExtra(EXTRA_VALUE, -1);
            handleAction(intent.getAction(), val);
        }
        return START_STICKY;
    }

    private void handleAction(String action, int value) {
        if (value < 0) return;
        switch (action) {
            case ACTION_SET_BASS_STRENGTH:
                mConfig.set("bass_strength", value);
                if (mBass != null) mBass.setStrength((short) value);
                Log.i(TAG, "bass_strength=" + value);
                break;
            case ACTION_SET_LOUDNESS_TARGET:
                mConfig.set("loudness_target", value);
                if (mLoudness != null) mLoudness.setTargetGain(value);
                Log.i(TAG, "loudness_target=" + value);
                break;
            case ACTION_SET_BASS_ENABLED:
                mConfig.set("bass_enabled", value);
                if (mBass != null) mBass.setEnabled(value == 1);
                Log.i(TAG, "bass_enabled=" + value);
                break;
            case ACTION_SET_LOUDNESS_ENABLED:
                mConfig.set("loudness_enabled", value);
                if (mLoudness != null) mLoudness.setEnabled(value == 1);
                Log.i(TAG, "loudness_enabled=" + value);
                break;
        }
    }

    private void applyEffects() {
        try {
            int bassEnabled     = mConfig.get("bass_enabled",     Config.DEFAULT_BASS_ENABLED);
            int bassStrength    = mConfig.get("bass_strength",    Config.DEFAULT_BASS_STRENGTH);
            int loudnessEnabled = mConfig.get("loudness_enabled", Config.DEFAULT_LOUDNESS_ENABLED);
            int loudnessTarget  = mConfig.get("loudness_target",  Config.DEFAULT_LOUDNESS_TARGET);

            /* session 0 = global output mix */
            mBass = new BassBoost(Integer.MAX_VALUE, 0);
            mBass.setStrength((short) bassStrength);
            mBass.setEnabled(bassEnabled == 1);
            Log.i(TAG, "BassBoost: enabled=" + bassEnabled + " strength=" + bassStrength);

            mLoudness = new LoudnessEnhancer(0);
            mLoudness.setTargetGain(loudnessTarget);
            mLoudness.setEnabled(loudnessEnabled == 1);
            Log.i(TAG, "LoudnessEnhancer: enabled=" + loudnessEnabled + " target=" + loudnessTarget);

        } catch (Exception e) {
            Log.e(TAG, "applyEffects gagal: " + e.getMessage());
        }
    }

    private void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
            CH_ID, "Bass", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);

        Notification notif = new Notification.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle("BassBoost")
            .setOngoing(true)
            .build();

        startForeground(NOTIF, notif);
    }

    @Override
    public void onDestroy() {
        if (mBass     != null) { mBass.release();     }
        if (mLoudness != null) { mLoudness.release(); }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
