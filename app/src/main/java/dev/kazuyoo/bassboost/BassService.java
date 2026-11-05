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
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

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
    public static final String ACTION_SET_CLARITY          = "dev.kazuyoo.bassboost.SET_CLARITY";
    public static final String ACTION_SET_DP_ENABLED       = "dev.kazuyoo.bassboost.SET_DP_ENABLED";
    public static final String EXTRA_VALUE                 = "value";

    private static final int NO_VALUE = Integer.MIN_VALUE;

    /* Platform broadcasts (hidden constants in AudioManager) */
    private static final String ACTION_VOLUME_CHANGED  = "android.media.VOLUME_CHANGED_ACTION";
    private static final String ACTION_DEVICES_CHANGED = "android.media.STREAM_DEVICES_CHANGED_ACTION";
    private static final String EXTRA_STREAM_TYPE      = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    /*
     * Adaptive curve. Up to KNEE of the max music volume everything stays at the
     * configured "quiet" values (identical to v1.1: full bass gain, loudness target,
     * fast/hard limiter). Above it, factor t rises 0 -> 1 and bass gain, loudness and
     * the limiter all move towards their gentler "max volume" values.
     */
    private static final float KNEE = 0.5f;

    /* While audio is playing the volume is also polled, because the platform volume
     * broadcast is not delivered reliably on every ROM. */
    private static final long POLL_MS   = 500;
    private static final long ENSURE_MS = 5000;

    /* Pre-EQ band upper edges in Hz: 0-60 | 60-150 | 150-300 | 300+ */
    private static final float[] BAND_CUTOFF = { 60f, 150f, 300f, 20000f };

    private BassBoost          mBass;
    private LoudnessEnhancer   mLoudness;
    private DynamicsProcessing mDp;
    private Config             mConfig;
    private AudioManager       mAudio;

    private float   mAppliedT = Float.NaN;
    private boolean mReceiverRegistered = false;
    private boolean mPlaying    = false;
    private boolean mPolling    = false;
    private long    mLastEnsure = 0;
    private int     mVolMin     = -1;
    private int     mVolMax     = -1;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (!mPolling) return;
            onVolumeChanged();
            mHandler.postDelayed(this, POLL_MS);
        }
    };

    private final AudioManager.AudioPlaybackCallback mPlaybackCb =
        new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                boolean playing = configs != null && !configs.isEmpty();
                boolean started = playing && !mPlaying;
                mPlaying = playing;
                updatePolling();
                if (started) ensureEffects();
            }
        };

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

        try {
            mAudio.registerAudioPlaybackCallback(mPlaybackCb, mHandler);
            mPlaying = !mAudio.getActivePlaybackConfigurations().isEmpty();
            updatePolling();
        } catch (Exception e) {
            Log.e(TAG, "playback callback registration failed: " + e);
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
                    updatePolling();
                    break;
                case ACTION_SET_CLARITY:
                    mConfig.set("clarity", value == 1 ? 1 : 0);
                    releaseDp();   /* the effect is rebuilt with or without the multiband stage */
                    syncDp();
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
            mLoudness.setEnabled(true);
            applyAdaptive(true);
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
            mDp.setEnabled(true);
            applyAdaptive(true);
        } catch (Exception e) {
            Log.e(TAG, "DynamicsProcessing failed: " + e);
            releaseDp();
        }
    }

    /** Builds the Dynamics Processing effect, with the multiband stage if "clarity" is on. */
    private void createDp() {
        if (mConfig.get("clarity", Config.DEFAULT_CLARITY) == 1) {
            try {
                mDp = buildDp(true);
                return;
            } catch (Exception e) {
                Log.e(TAG, "multiband stage unavailable, falling back: " + e);
            }
        }
        mDp = buildDp(false);
    }

    private DynamicsProcessing buildDp(boolean withMbc) {
        DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,                          /* channels (stereo) */
            true, 4,                    /* pre-EQ: 4 bands */
            withMbc, withMbc ? 3 : 0,   /* multiband compressor: 3 bands, optional */
            false, 0,                   /* post-EQ unused */
            true);                      /* limiter */

        DynamicsProcessing dp = new DynamicsProcessing(Integer.MAX_VALUE, 0, b.build());
        try {
            /* flat pre-EQ and quiet-volume limiter; applyAdaptive() sets the real values */
            DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, 4);
            for (int i = 0; i < 4; i++) {
                eq.setBand(i, new DynamicsProcessing.EqBand(true, BAND_CUTOFF[i], 0f));
            }
            dp.setPreEqAllChannelsTo(eq);
            dp.setLimiterAllChannelsTo(buildLimiter(0f));

            if (withMbc) {
                /*
                 * "Clarity": per-band dynamics, so bass stays big but controlled while
                 * vocals stay present and harsh highs are tamed.
                 * band: upper edge, attack ms, release ms, ratio, threshold dB, knee dB,
                 *       noise gate dB (off), expander ratio (off), pre gain, post gain
                 */
                DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, true, 3);
                /* bass  (<300 Hz): compress dents so bass is big but never huge */
                mbc.setBand(0, new DynamicsProcessing.MbcBand(
                    true,  300f, 15f, 150f, 2.5f, -12f, 6f, -90f, 1f, 0f, 0f));
                /* vocal (300-2500 Hz): gentle levelling plus a small lift */
                mbc.setBand(1, new DynamicsProcessing.MbcBand(
                    true, 2500f, 10f, 120f, 2.0f, -28f, 6f, -90f, 1f, 0f, 2f));
                /* presence (2.5 kHz+): only squash peaks that get harsh */
                mbc.setBand(2, new DynamicsProcessing.MbcBand(
                    true, 20000f, 5f, 80f, 3.0f, -22f, 6f, -90f, 1f, 0f, 0f));
                dp.setMbcAllChannelsTo(mbc);
            }
        } catch (RuntimeException e) {
            try { dp.release(); } catch (Exception ignored) {}
            throw e;
        }
        return dp;
    }

    /** t = 0: the v1.1 limiter (hard, loud). t = 1: softer limiter for high volume. */
    private static DynamicsProcessing.Limiter buildLimiter(float t) {
        return new DynamicsProcessing.Limiter(
            true, true, 0,
            1f + 2f * t,        /* attack ms:    1 -> 3    */
            80f + 40f * t,      /* release ms:  80 -> 120  */
            20f - 10f * t,      /* ratio:       20 -> 10   */
            -3f - t,            /* threshold dB: -3 -> -4  */
            0f);                /* post gain dB            */
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
        mAppliedT = Float.NaN;
    }

    private void releaseDp() {
        if (mDp != null) {
            try { mDp.release(); } catch (Exception ignored) {}
            mDp = null;
        }
        mAppliedT = Float.NaN;
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
    /* Volume-adaptive behaviour                                          */
    /* ------------------------------------------------------------------ */

    private void updatePolling() {
        setPolling(mPlaying && mConfig.get("adaptive", Config.DEFAULT_ADAPTIVE) == 1);
    }

    private void setPolling(boolean on) {
        if (on == mPolling) return;
        mPolling = on;
        mHandler.removeCallbacks(mPoll);
        if (on) mHandler.post(mPoll);
    }

    private void onVolumeChanged() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastEnsure >= ENSURE_MS) {
            mLastEnsure = now;
            ensureEffects();
        }
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
            if (mVolMax < 0) {
                mVolMin = mAudio.getStreamMinVolume(AudioManager.STREAM_MUSIC);
                mVolMax = mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            }
            int min = mVolMin;
            int max = mVolMax;
            if (max <= min) return 0f;
            float r = (mAudio.getStreamVolume(AudioManager.STREAM_MUSIC) - min) / (float) (max - min);
            r = Math.max(0f, Math.min(1f, r));
            return r <= KNEE ? 0f : (r - KNEE) / (1f - KNEE);
        } catch (Exception e) {
            return 0f;
        }
    }

    private float gainFor(float t) {
        float low  = clamp(mConfig.get("dp_gain",     Config.DEFAULT_DP_GAIN),     0, 15);
        float high = clamp(mConfig.get("dp_gain_max", Config.DEFAULT_DP_GAIN_MAX), 0, 15);
        float g = low + (high - low) * t;
        return Math.round(g * 2f) / 2f; /* 0.5 dB steps */
    }

    private int loudnessFor(float t) {
        int base = clamp(mConfig.get("loudness_target", Config.DEFAULT_LOUDNESS_TARGET), 0, 2000);
        return Math.round(base * (1f - t) / 10f) * 10; /* 10 mB steps */
    }

    /**
     * Pushes every volume-dependent value (bass gain, limiter, loudness) to the effects.
     * The factor is quantised to 0.1 so nothing is touched unless it really changed.
     */
    private void applyAdaptive(boolean force) {
        float t = Math.round(volumeFactor() * 10f) / 10f;
        if (!force && t == mAppliedT) return;

        if (mDp != null) {
            float g = gainFor(t);
            mDp.setPreEqBandAllChannelsTo(1, new DynamicsProcessing.EqBand(true, BAND_CUTOFF[1], g));
            mDp.setPreEqBandAllChannelsTo(2, new DynamicsProcessing.EqBand(true, BAND_CUTOFF[2], g * 0.6f));
            mDp.setLimiterAllChannelsTo(buildLimiter(t));
        }
        if (mLoudness != null) {
            mLoudness.setTargetGain(loudnessFor(t));
        }
        mAppliedT = t;
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
        mPolling = false;
        mHandler.removeCallbacksAndMessages(null);
        try { mAudio.unregisterAudioPlaybackCallback(mPlaybackCb); } catch (Exception ignored) {}
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
