package dev.kazuyoo.bassboost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
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
    public static final String ACTION_SET_LOW_GAIN         = "dev.kazuyoo.bassboost.SET_LOW_GAIN";
    public static final String ACTION_SET_DP_ENABLED       = "dev.kazuyoo.bassboost.SET_DP_ENABLED";
    public static final String EXTRA_VALUE                 = "value";

    private static final int NO_VALUE = Integer.MIN_VALUE;

    private BassBoost          mBass;
    private LoudnessEnhancer   mLoudness;
    private DynamicsProcessing mDp;
    private Config             mConfig;

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
            int val = intent.getIntExtra(EXTRA_VALUE, NO_VALUE);
            handleAction(intent.getAction(), val);
        }
        return START_STICKY;
    }

    private void handleAction(String action, int value) {
        if (value == NO_VALUE) return;
        try {
            switch (action) {
                case ACTION_SET_BASS_STRENGTH:
                    mConfig.set("bass_strength", value);
                    if (mBass != null) mBass.setStrength((short) value);
                    Log.i(TAG, "bass_strength=" + value);
                    break;
                case ACTION_SET_LOUDNESS_TARGET:
                    /* satuan milibel: 0 = tanpa gain, 600 = +6 dB */
                    mConfig.set("loudness_target", value);
                    if (mLoudness != null) mLoudness.setTargetGain(value);
                    Log.i(TAG, "loudness_target(mB)=" + value);
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
                case ACTION_SET_LOW_GAIN:
                    value = Math.max(0, Math.min(15, value));
                    mConfig.set("dp_gain", value);
                    if (mDp != null) setLowBandGain(value);
                    Log.i(TAG, "dp_gain(dB)=" + value);
                    break;
                case ACTION_SET_DP_ENABLED:
                    mConfig.set("dp_enabled", value);
                    if (mDp != null) mDp.setEnabled(value == 1);
                    Log.i(TAG, "dp_enabled=" + value);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "handleAction gagal (" + action + "): " + e);
        }
    }

    private void applyEffects() {
        /* tiap efek di try sendiri supaya satu gagal tidak mematikan yang lain */
        try {
            int bassEnabled  = mConfig.get("bass_enabled",  Config.DEFAULT_BASS_ENABLED);
            int bassStrength = mConfig.get("bass_strength", Config.DEFAULT_BASS_STRENGTH);
            /* session 0 = global output mix */
            mBass = new BassBoost(Integer.MAX_VALUE, 0);
            mBass.setStrength((short) bassStrength);
            mBass.setEnabled(bassEnabled == 1);
            Log.i(TAG, "BassBoost: enabled=" + bassEnabled + " strength=" + bassStrength);
        } catch (Exception e) {
            Log.e(TAG, "BassBoost gagal: " + e);
        }

        try {
            int loudnessEnabled = mConfig.get("loudness_enabled", Config.DEFAULT_LOUDNESS_ENABLED);
            int loudnessTarget  = mConfig.get("loudness_target",  Config.DEFAULT_LOUDNESS_TARGET);
            mLoudness = new LoudnessEnhancer(0);
            mLoudness.setTargetGain(loudnessTarget);
            mLoudness.setEnabled(loudnessEnabled == 1);
            Log.i(TAG, "LoudnessEnhancer: enabled=" + loudnessEnabled + " target(mB)=" + loudnessTarget);
        } catch (Exception e) {
            Log.e(TAG, "LoudnessEnhancer gagal: " + e);
        }

        try {
            int dpEnabled = mConfig.get("dp_enabled", Config.DEFAULT_DP_ENABLED);
            int dpGain    = mConfig.get("dp_gain",    Config.DEFAULT_DP_GAIN);

            DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,          /* channel (stereo) */
                true, 4,    /* pre-EQ: 4 band */
                false, 0,   /* MBC tidak dipakai */
                false, 0,   /* post-EQ tidak dipakai */
                true);      /* limiter */

            mDp = new DynamicsProcessing(Integer.MAX_VALUE, 0, b.build());
            setLowBandGain(dpGain);

            /* limiter: cegah clipping akibat gain bass */
            DynamicsProcessing.Limiter lim = new DynamicsProcessing.Limiter(
                true, true, 0,
                1f,     /* attack ms  */
                80f,    /* release ms */
                20f,    /* ratio      */
                -3f,    /* threshold dB */
                0f);    /* post gain dB */
            mDp.setLimiterAllChannelsTo(lim);

            mDp.setEnabled(dpEnabled == 1);
            Log.i(TAG, "DynamicsProcessing: enabled=" + dpEnabled + " gain(dB)=" + dpGain);
        } catch (Exception e) {
            Log.e(TAG, "DynamicsProcessing gagal: " + e);
        }
    }

    /*
     * Pre-EQ 4 band (cutoff = batas atas band):
     *   0-60 Hz   : 0 dB       (speaker HP tidak sanggup, hemat excursion)
     *   60-150 Hz : +gain
     *   150-300 Hz: +gain*0.6
     *   300 Hz+   : 0 dB
     */
    private void setLowBandGain(int gainDb) {
        float[] cutoff = { 60f, 150f, 300f, 20000f };
        float[] gain   = { 0f, gainDb, gainDb * 0.6f, 0f };

        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, 4);
        for (int i = 0; i < 4; i++) {
            DynamicsProcessing.EqBand band = new DynamicsProcessing.EqBand(true, cutoff[i], gain[i]);
            eq.setBand(i, band);
        }
        mDp.setPreEqAllChannelsTo(eq);
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
        if (mDp       != null) { mDp.release();       }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
