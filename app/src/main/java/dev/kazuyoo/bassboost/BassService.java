package dev.kazuyoo.bassboost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class BassService extends Service {

    private static final String TAG   = "BassService";
    private static final String CH_ID = "bassboost";
    private static final int    NOTIF = 1;

    /* Intent actions for configuring the service from a shell */
    public static final String ACTION_SET_BASS_STRENGTH    = "dev.kazuyoo.bassboost.SET_BASS_STRENGTH";
    public static final String ACTION_SET_LOUDNESS_TARGET  = "dev.kazuyoo.bassboost.SET_LOUDNESS_TARGET";
    public static final String ACTION_SET_BASS_ENABLED     = "dev.kazuyoo.bassboost.SET_BASS_ENABLED";
    public static final String ACTION_SET_LOUDNESS_ENABLED = "dev.kazuyoo.bassboost.SET_LOUDNESS_ENABLED";
    public static final String ACTION_SET_LOW_GAIN         = "dev.kazuyoo.bassboost.SET_LOW_GAIN";
    public static final String ACTION_SET_HIGH_VOL_GAIN    = "dev.kazuyoo.bassboost.SET_HIGH_VOL_GAIN";
    public static final String ACTION_SET_ADAPTIVE         = "dev.kazuyoo.bassboost.SET_ADAPTIVE";
    public static final String ACTION_SET_DP_ENABLED       = "dev.kazuyoo.bassboost.SET_DP_ENABLED";
    public static final String EXTRA_VALUE                 = "value";

    private static final int NO_VALUE = Integer.MIN_VALUE;

    /* Platform broadcasts (hidden constants in AudioManager) */
    private static final String ACTION_VOLUME_CHANGED  = "android.media.VOLUME_CHANGED_ACTION";
    private static final String ACTION_DEVICES_CHANGED = "android.media.STREAM_DEVICES_CHANGED_ACTION";
    private static final String EXTRA_STREAM_TYPE      = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    /*
     * Adaptive curve: up to this fraction of the max music volume the bass gain and
     * loudness stay at their configured values; above it they taper linearly to the
     * "max volume" values (dp_gain_max, loudness 0 mB).
     */
    private static final float KNEE = 0.5f;

    /* Pre-EQ band upper edges in Hz: 0-60 | 60-150 | 150-300 | 300+ */
    private static final float[] BAND_CUTOFF = { 60f, 150f, 300f, 20000f };

    private BassBoost          mBass;
    private LoudnessEnhancer   mLoudness;
    private DynamicsProcessing mDp;
    private Config             mConfig;
    private AudioManager       mAudio;

    private float mAppliedGain = Float.NaN;
    private int   mAppliedLoud = NO_VALUE;
    private boolean mReceiverRegistered = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver mVolumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            int type = intent.getIntExtra(EXTRA_STREAM_TYPE, AudioManager.STREAM_MUSIC);
            if (type != AudioManager.STREAM_MUSIC) return;
            onVolumeChanged();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mConfig = new Config(this);
        mAudio  = getSystemService(AudioManager.class);
        startFg();
        syncAll();

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_VOLUME_CHANGED);
        f.addAction(ACTION_DEVICES_CHANGED);
        try {
            registerReceiver(mVolumeReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            mReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "volume receiver registration failed: " + e);
        }

        /* The audio server may not be ready right after boot; retry creating any
         * effect that failed, a couple of times. */
        mHandler.postDelayed(this::ensureEffects, 5000);
        mHandler.postDelayed(this::ensureEffects, 30000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            int val = intent.getIntExtra(EXTRA_VALUE, NO_VALUE);
            handleAction(intent.getAction(), val);
        }
        return START_STICKY;
    }

    /* ------------------------------------------------------------------ */
    /* Commands                                                           */
    /* ------------------------------------------------------------------ */

    private void handleAction(String action, int value) {
        if (value == NO_VALUE) return;
        try {
            switch (action) {
                case ACTION_SET_BASS_STRENGTH:
                    mConfig.set("bass_strength", clamp(value, 0, 1000));
                    syncBass();
                    break;
                case ACTION_SET_BASS_ENABLED:
                    mConfig.set("bass_enabled", value == 1 ? 1 : 0);
                    syncBass();
                    break;
                case ACTION_SET_LOUDNESS_TARGET:
                    /* millibels: 0 = no gain, 600 = +6 dB */
                    mConfig.set("loudness_target", clamp(value, 0, 2000));
                    syncLoudness();
                    break;
                case ACTION_SET_LOUDNESS_ENABLED:
                    mConfig.set("loudness_enabled", value == 1 ? 1 : 0);
                    syncLoudness();
                    break;
                case ACTION_SET_LOW_GAIN:
                    mConfig.set("dp_gain", clamp(value, 0, 15));
                    syncDp();
                    break;
                case ACTION_SET_HIGH_VOL_GAIN:
                    mConfig.set("dp_gain_max", clamp(value, 0, 15));
                    syncDp();
                    break;
                case ACTION_SET_ADAPTIVE:
                    mConfig.set("adaptive", value == 1 ? 1 : 0);
                    syncDp();
                    syncLoudness();
                    break;
                case ACTION_SET_DP_ENABLED:
                    mConfig.set("dp_enabled", value == 1 ? 1 : 0);
                    syncDp();
                    break;
                default:
                    return;
            }
            Log.i(TAG, action + "=" + value);
        } catch (IllegalStateException | UnsupportedOperationException e) {
            Log.e(TAG, "effect invalid (" + action + "), rebuilding: " + e);
            recoverEffects();
        } catch (Exception e) {
            Log.e(TAG, "handleAction failed (" + action + "): " + e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Effect lifecycle: an effect only exists while it is enabled, so a   */
    /* disabled effect costs no CPU and does not sit in the output chain.  */
    /* ------------------------------------------------------------------ */

    private void syncAll() {
        syncBass();
        syncLoudness();
        syncDp();
    }

    private void syncBass() {
        try {
            if (mConfig.get("bass_enabled", Config.DEFAULT_BASS_ENABLED) != 1) {
                releaseBass();
                return;
            }
            if (mBass == null) mBass = new BassBoost(Integer.MAX_VALUE, 0); /* session 0 = global mix */
            int strength = clamp(mConfig.get("bass_strength", Config.DEFAULT_BASS_STRENGTH), 0, 1000);
            mBass.setStrength((short) strength);
            mBass.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "BassBoost failed: " + e);
            releaseBass();
        }
    }

    private void syncLoudness() {
        try {
            if (mConfig.get("loudness_enabled", Config.DEFAULT_LOUDNESS_ENABLED) != 1) {
                releaseLoudness();
                return;
            }
            if (mLoudness == null) mLoudness = new LoudnessEnhancer(0);
            mAppliedLoud = targetLoudnessMb();
            mLoudness.setTargetGain(mAppliedLoud);
            mLoudness.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "LoudnessEnhancer failed: " + e);
            releaseLoudness();
        }
    }

    private void syncDp() {
        try {
            if (mConfig.get("dp_enabled", Config.DEFAULT_DP_ENABLED) != 1) {
                releaseDp();
                return;
            }
            if (mDp == null) createDp();
            applyAdaptive(true);
            mDp.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "DynamicsProcessing failed: " + e);
            releaseDp();
        }
    }

    private void createDp() {
        DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,          /* channels (stereo) */
            true, 4,    /* pre-EQ: 4 bands */
            false, 0,   /* MBC unused */
            false, 0,   /* post-EQ unused */
            true);      /* limiter */

        DynamicsProcessing dp = new DynamicsProcessing(Integer.MAX_VALUE, 0, b.build());

        /* Softer limiter than v1.1: slower attack, longer release, lower ratio and a
         * little more headroom. Less harsh and less "pumping" at high volume. */
        DynamicsProcessing.Limiter lim = new DynamicsProcessing.Limiter(
            true, true, 0,
            3f,     /* attack ms     */
            120f,   /* release ms    */
            10f,    /* ratio         */
            -4f,    /* threshold dB  */
            0f);    /* post gain dB  */
        dp.setLimiterAllChannelsTo(lim);

        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, 4);
        float g = targetGainDb();
        float[] gain = { 0f, g, g * 0.6f, 0f };
        for (int i = 0; i < 4; i++) {
            eq.setBand(i, new DynamicsProcessing.EqBand(true, BAND_CUTOFF[i], gain[i]));
        }
        dp.setPreEqAllChannelsTo(eq);

        mDp = dp;
        mAppliedGain = g;
    }

    private void releaseBass() {
        if (mBass != null) {
            try { mBass.release(); } catch (Exception ignored) {}
            mBass = null;
        }
    }

    private void releaseLoudness() {
        if (mLoudness != null) {
            try { mLoudness.release(); } catch (Exception ignored) {}
            mLoudness = null;
        }
        mAppliedLoud = NO_VALUE;
    }

    private void releaseDp() {
        if (mDp != null) {
            try { mDp.release(); } catch (Exception ignored) {}
            mDp = null;
        }
        mAppliedGain = Float.NaN;
    }

    /** Re-creates any effect that should exist but does not (e.g. it failed during boot). */
    private void ensureEffects() {
        if (mConfig.get("dp_enabled", Config.DEFAULT_DP_ENABLED) == 1 && mDp == null) syncDp();
        if (mConfig.get("loudness_enabled", Config.DEFAULT_LOUDNESS_ENABLED) == 1 && mLoudness == null) syncLoudness();
        if (mConfig.get("bass_enabled", Config.DEFAULT_BASS_ENABLED) == 1 && mBass == null) syncBass();
    }

    /** Called when an effect object turns out to be dead (e.g. audio server restarted). */
    private void recoverEffects() {
        releaseBass();
        releaseLoudness();
        releaseDp();
        syncAll();
    }

    /* ------------------------------------------------------------------ */
    /* Volume-adaptive gain                                               */
    /* ------------------------------------------------------------------ */

    private void onVolumeChanged() {
        ensureEffects();
        if (mConfig.get("adaptive", Config.DEFAULT_ADAPTIVE) != 1) return;
        try {
            applyAdaptive(false);
        } catch (IllegalStateException | UnsupportedOperationException e) {
            Log.e(TAG, "effect invalid on volume change, rebuilding: " + e);
            recoverEffects();
        } catch (Exception e) {
            Log.e(TAG, "adaptive update failed: " + e);
        }
    }

    /** 0 up to KNEE of the max music volume, rising linearly to 1 at max volume. */
    private float volumeFactor() {
        if (mConfig.get("adaptive", Config.DEFAULT_ADAPTIVE) != 1) return 0f;
        try {
            int min = mAudio.getStreamMinVolume(AudioManager.STREAM_MUSIC);
            int max = mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= min) return 0f;
            float r = (mAudio.getStreamVolume(AudioManager.STREAM_MUSIC) - min) / (float) (max - min);
            r = Math.max(0f, Math.min(1f, r));
            return r <= KNEE ? 0f : (r - KNEE) / (1f - KNEE);
        } catch (Exception e) {
            return 0f;
        }
    }

    private float targetGainDb() {
        float low  = clamp(mConfig.get("dp_gain",     Config.DEFAULT_DP_GAIN),     0, 15);
        float high = clamp(mConfig.get("dp_gain_max", Config.DEFAULT_DP_GAIN_MAX), 0, 15);
        float g = low + (high - low) * volumeFactor();
        return Math.round(g * 2f) / 2f; /* 0.5 dB steps, avoids pointless updates */
    }

    private int targetLoudnessMb() {
        int base = clamp(mConfig.get("loudness_target", Config.DEFAULT_LOUDNESS_TARGET), 0, 2000);
        return Math.round(base * (1f - volumeFactor()) / 10f) * 10; /* 10 mB steps */
    }

    /** Pushes the volume-dependent values to the effects, skipping unchanged ones. */
    private void applyAdaptive(boolean force) {
        if (mDp != null) {
            float g = targetGainDb();
            if (force || g != mAppliedGain) {
                mDp.setPreEqBandAllChannelsTo(1, new DynamicsProcessing.EqBand(true, BAND_CUTOFF[1], g));
                mDp.setPreEqBandAllChannelsTo(2, new DynamicsProcessing.EqBand(true, BAND_CUTOFF[2], g * 0.6f));
                mAppliedGain = g;
            }
        }
        if (mLoudness != null) {
            int l = targetLoudnessMb();
            if (force || l != mAppliedLoud) {
                mLoudness.setTargetGain(l);
                mAppliedLoud = l;
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /* ------------------------------------------------------------------ */

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
        mHandler.removeCallbacksAndMessages(null);
        if (mReceiverRegistered) {
            try { unregisterReceiver(mVolumeReceiver); } catch (Exception ignored) {}
            mReceiverRegistered = false;
        }
        releaseBass();
        releaseLoudness();
        releaseDp();
        mConfig.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
