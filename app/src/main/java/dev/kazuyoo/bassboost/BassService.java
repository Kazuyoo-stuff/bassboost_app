package dev.kazuyoo.bassboost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * v1.2. The signal path is v1.1.1 (Bass Boost, Loudness Enhancer, Dynamics Processing
 * with a pre-EQ and a limiter on the global output mix). With the new options at their
 * defaults it sounds identical. Opt-in extras:
 *
 *  - adaptive:      follows the music volume. Louder at low volume, and from 60% volume
 *                   upwards less bass drive, less loudness and a softer limiter, which
 *                   removes clipping and stuttering at high volume.
 *  - vocal_smooth:  a few dB of static EQ cut at 2-6 kHz against harsh, tinny vocals.
 *  - bass_low:      equal-loudness compensation. The ear loses bass at low volume, so with
 *                   adaptive on the bass gain is raised by up to bass_low dB at minimum
 *                   volume, fading out by 60% volume.
 */
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
    public static final String ACTION_SET_DP_ENABLED       = "dev.kazuyoo.bassboost.SET_DP_ENABLED";
    public static final String ACTION_SET_ADAPTIVE         = "dev.kazuyoo.bassboost.SET_ADAPTIVE";
    public static final String ACTION_SET_HIGH_VOL_GAIN    = "dev.kazuyoo.bassboost.SET_HIGH_VOL_GAIN";
    public static final String ACTION_SET_LOUDNESS_LOW     = "dev.kazuyoo.bassboost.SET_LOUDNESS_LOW";
    public static final String ACTION_SET_VOCAL_SMOOTH     = "dev.kazuyoo.bassboost.SET_VOCAL_SMOOTH";
    public static final String ACTION_SET_BASS_DEPTH       = "dev.kazuyoo.bassboost.SET_BASS_DEPTH";
    public static final String ACTION_SET_LIMITER_RELEASE  = "dev.kazuyoo.bassboost.SET_LIMITER_RELEASE";
    public static final String ACTION_SET_BASS_LOW         = "dev.kazuyoo.bassboost.SET_BASS_LOW";
    public static final String EXTRA_VALUE                 = "value";

    private static final int NO_VALUE = Integer.MIN_VALUE;

    /*
     * Adaptive curve, r = music volume as a fraction of its range.
     *  - r below KNEE: bass gain and limiter exactly as configured (same as v1.1.1);
     *    loudness gets an extra boost (loudness_low), largest at minimum volume and
     *    fading to nothing at KNEE.
     *  - r above KNEE: factor t rises 0 -> 1. Bass gain slides to dp_gain_max, loudness
     *    to 0 and the limiter becomes softer.
     */
    private static final float KNEE    = 0.6f;
    private static final long  POLL_MS = 500;

    /* Pre-EQ band upper edges in Hz.
     * 4 bands: 0-60 | 60-150 | 150-300 | 300+
     * 6 bands: 0-60 | 60-150 | 150-300 | 300-2000 | 2000-6000 | 6000+  (vocal_smooth on) */
    private static final float[] CUT4 = { 60f, 150f, 300f, 20000f };
    private static final float[] CUT6 = { 60f, 150f, 300f, 2000f, 6000f, 20000f };

    private BassBoost          mBass;
    private LoudnessEnhancer   mLoudness;
    private DynamicsProcessing mDp;
    private int                mDpBands = 0;
    private Config             mConfig;
    private AudioManager       mAudio;

    private boolean mPlaying    = false;
    private boolean mPolling    = false;
    private float   mAppliedR   = Float.NaN;
    private int     mVolMin     = -1;
    private int     mVolMax     = -1;

    private final Handler  mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mVerify  = this::verifyEffects;

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (!mPolling) return;
            try {
                applyAdaptive(false);
            } catch (Exception e) {
                Log.e(TAG, "adaptive update failed: " + e);
                scheduleVerify();
            }
            mHandler.postDelayed(this, POLL_MS);
        }
    };

    /* Playback start: make sure every effect is alive, and run the adaptive poll only
     * while something is actually playing. */
    private final AudioManager.AudioPlaybackCallback mPlaybackCb =
        new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                boolean playing = configs != null && !configs.isEmpty();
                boolean started = playing && !mPlaying;
                mPlaying = playing;
                updatePolling();
                if (started) verifyEffects();
            }
        };

    /* Listeners from the AudioEffect API. The engine can be shared between apps; if control
     * is regained or someone switches one of our effects off, restore the intended state. */
    private final AudioEffect.OnControlStatusChangeListener mControlCb =
        (effect, controlGranted) -> {
            Log.w(TAG, "control " + (controlGranted ? "regained" : "lost"));
            if (controlGranted) scheduleVerify();
        };

    private final AudioEffect.OnEnableStatusChangeListener mEnableCb =
        (effect, enabled) -> {
            if (!enabled && shouldBeEnabled(effect)) scheduleVerify();
        };

    @Override
    public void onCreate() {
        super.onCreate();
        mConfig = new Config(this);
        mAudio  = getSystemService(AudioManager.class);
        startFg();
        syncAll();

        try {
            mAudio.registerAudioPlaybackCallback(mPlaybackCb, mHandler);
            mPlaying = !mAudio.getActivePlaybackConfigurations().isEmpty();
            updatePolling();
        } catch (Exception e) {
            Log.e(TAG, "playback callback registration failed: " + e);
        }

        /* The audio server may not be ready right after boot: retry twice. */
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
            case ACTION_SET_DP_ENABLED:
                mConfig.set("dp_enabled", value == 1 ? 1 : 0);
                syncDp();
                break;
            case ACTION_SET_ADAPTIVE:
                mConfig.set("adaptive", value == 1 ? 1 : 0);
                syncDp();
                syncLoudness();
                updatePolling();
                break;
            case ACTION_SET_HIGH_VOL_GAIN:
                mConfig.set("dp_gain_max", clamp(value, 0, 15));
                syncDp();
                break;
            case ACTION_SET_LOUDNESS_LOW:
                mConfig.set("loudness_low", clamp(value, 0, 1000));
                syncLoudness();
                break;
            case ACTION_SET_VOCAL_SMOOTH:
                mConfig.set("vocal_smooth", clamp(value, 0, 6));
                syncDp();
                break;
            case ACTION_SET_BASS_DEPTH:
                mConfig.set("bass_depth", clamp(value, 0, 100));
                syncDp();
                break;
            case ACTION_SET_LIMITER_RELEASE:
                mConfig.set("limiter_release", clamp(value, 40, 400));
                syncDp();
                break;
            case ACTION_SET_BASS_LOW:
                mConfig.set("bass_low", clamp(value, 0, 10));
                syncDp();
                break;
            default:
                return; /* unknown or newer action: ignore */
        }
        Log.i(TAG, action + "=" + value);
    }

    /* ------------------------------------------------------------------ */
    /* Effects                                                            */
    /*                                                                    */
    /* Each sync*() creates its effect if needed, then applies the stored  */
    /* settings. If anything throws (a dead effect object after an audio   */
    /* server restart, lost control, ...), the effect is released and      */
    /* rebuilt once from scratch. One effect failing never affects others. */
    /* ------------------------------------------------------------------ */

    private void syncAll() {
        syncBass();
        syncLoudness();
        syncDp();
    }

    private void syncBass() {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (mBass == null) {
                    mBass = new BassBoost(Integer.MAX_VALUE, 0); /* session 0 = global mix */
                    watch(mBass);
                }
                mBass.setStrength((short) clamp(mConfig.get("bass_strength", Config.DEFAULT_BASS_STRENGTH), 0, 1000));
                mBass.setEnabled(mConfig.get("bass_enabled", Config.DEFAULT_BASS_ENABLED) == 1);
                return;
            } catch (Exception e) {
                Log.e(TAG, "BassBoost failed (attempt " + (attempt + 1) + "): " + e);
                releaseBass();
            }
        }
    }

    private void syncLoudness() {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (mLoudness == null) {
                    mLoudness = new LoudnessEnhancer(0);
                    watch(mLoudness);
                }
                mLoudness.setTargetGain(loudnessFor(currentR()));
                mLoudness.setEnabled(mConfig.get("loudness_enabled", Config.DEFAULT_LOUDNESS_ENABLED) == 1);
                return;
            } catch (Exception e) {
                Log.e(TAG, "LoudnessEnhancer failed (attempt " + (attempt + 1) + "): " + e);
                releaseLoudness();
            }
        }
    }

    private void syncDp() {
        int want = clamp(mConfig.get("vocal_smooth", Config.DEFAULT_VOCAL_SMOOTH), 0, 6) > 0 ? 6 : 4;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (mDp != null && mDpBands != want) releaseDp(); /* band count is fixed at creation */
                if (mDp == null) {
                    mDp = buildDp(want);
                    mDpBands = want;
                    watch(mDp);
                }
                applyDp(mDp, currentR());
                mDp.setEnabled(mConfig.get("dp_enabled", Config.DEFAULT_DP_ENABLED) == 1);
                mAppliedR = currentR();
                return;
            } catch (Exception e) {
                Log.e(TAG, "DynamicsProcessing failed (attempt " + (attempt + 1) + "): " + e);
                releaseDp();
            }
        }
    }

    /** Dynamics Processing: flat pre-EQ with the given band count plus a limiter. */
    private DynamicsProcessing buildDp(int bands) {
        DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,              /* channels (stereo) */
            true, bands,    /* pre-EQ */
            false, 0,       /* MBC unused */
            false, 0,       /* post-EQ unused */
            true);          /* limiter */

        DynamicsProcessing dp = new DynamicsProcessing(Integer.MAX_VALUE, 0, b.build());
        try {
            dp.setLimiterAllChannelsTo(buildLimiter(0f));

            float[] cut = bands == 6 ? CUT6 : CUT4;
            DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, bands);
            for (int i = 0; i < bands; i++) {
                eq.setBand(i, new DynamicsProcessing.EqBand(true, cut[i], 0f));
            }
            dp.setPreEqAllChannelsTo(eq);
        } catch (RuntimeException e) {
            try { dp.release(); } catch (Exception ignored) {}
            throw e;
        }
        return dp;
    }

    /**
     * Limiter shared by both channels (link group 0), so it reacts to the louder one and
     * the stereo image stays put. t = 0 is the v1.1.1 limiter; t = 1 is the softer
     * high-volume limiter.
     */
    private DynamicsProcessing.Limiter buildLimiter(float t) {
        float release = clamp(mConfig.get("limiter_release", Config.DEFAULT_LIMITER_RELEASE), 40, 400);
        return new DynamicsProcessing.Limiter(
            true, true, 0,
            1f + 2f * t,        /* attack ms:     1 -> 3                    */
            release + 70f * t,  /* release ms:    configured -> +70 ms      */
            20f - 12f * t,      /* ratio:        20 -> 8     */
            -3f - 1.5f * t,     /* threshold dB: -3 -> -4.5  */
            0f);                /* post gain dB (applied after limiting) */
    }

    /**
     * Stage parameters can be changed while the effect runs, so only the bands that move
     * are touched instead of rebuilding the effect.
     *   0-60 Hz: bass_depth % of the gain | 60-150 Hz: full gain | 150-300 Hz: 60%
     *   2-6 kHz: -vocal_smooth (6-band mode only)
     */
    private void applyDp(DynamicsProcessing dp, float r) {
        float t = tailFactor(r);
        float g = gainFor(r);
        float[] cut = mDpBands == 6 ? CUT6 : CUT4;
        float depth = clamp(mConfig.get("bass_depth", Config.DEFAULT_BASS_DEPTH), 0, 100) / 100f;
        dp.setPreEqBandAllChannelsTo(0, new DynamicsProcessing.EqBand(true, cut[0], g * depth));
        dp.setPreEqBandAllChannelsTo(1, new DynamicsProcessing.EqBand(true, cut[1], g));
        dp.setPreEqBandAllChannelsTo(2, new DynamicsProcessing.EqBand(true, cut[2], g * 0.6f));
        if (mDpBands == 6) {
            float v = clamp(mConfig.get("vocal_smooth", Config.DEFAULT_VOCAL_SMOOTH), 0, 6);
            dp.setPreEqBandAllChannelsTo(4, new DynamicsProcessing.EqBand(true, cut[4], -v));
        }
        dp.setLimiterAllChannelsTo(buildLimiter(t));
    }

    /* ------------------------------------------------------------------ */
    /* Volume-adaptive behaviour (inactive unless adaptive = 1)           */
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

    /** Music volume as 0..1 in 5% steps, or -1 when adaptive mode is off or unreadable. */
    private float currentR() {
        if (mConfig.get("adaptive", Config.DEFAULT_ADAPTIVE) != 1) return -1f;
        try {
            if (mVolMax < 0) {
                mVolMin = mAudio.getStreamMinVolume(AudioManager.STREAM_MUSIC);
                mVolMax = mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            }
            if (mVolMax <= mVolMin) return -1f;
            float r = (mAudio.getStreamVolume(AudioManager.STREAM_MUSIC) - mVolMin) / (float) (mVolMax - mVolMin);
            r = Math.max(0f, Math.min(1f, r));
            return Math.round(r * 20f) / 20f;
        } catch (Exception e) {
            return -1f;
        }
    }

    /** 0 up to KNEE, rising linearly to 1 at maximum volume. 0 when adaptive is off (r < 0). */
    private static float tailFactor(float r) {
        return r <= KNEE ? 0f : (r - KNEE) / (1f - KNEE);
    }

    /** 1 at minimum volume, fading to 0 at KNEE. 0 when adaptive is off (r < 0). */
    private static float lowFactor(float r) {
        return (r < 0f || r >= KNEE) ? 0f : 1f - r / KNEE;
    }

    /**
     * Bass gain in dB for music volume r (-1 = adaptive off):
     *   below KNEE:  dp_gain plus up to bass_low dB, fading out towards KNEE
     *   above KNEE:  slides from dp_gain down to dp_gain_max at maximum volume
     */
    private float gainFor(float r) {
        float base  = clamp(mConfig.get("dp_gain",     Config.DEFAULT_DP_GAIN),     0, 15);
        float high  = Math.min(clamp(mConfig.get("dp_gain_max", Config.DEFAULT_DP_GAIN_MAX), 0, 15), base);
        float extra = clamp(mConfig.get("bass_low",    Config.DEFAULT_BASS_LOW),    0, 10);
        float g = base + extra * lowFactor(r) + (high - base) * tailFactor(r);
        g = Math.max(0f, Math.min(15f, g));
        return Math.round(g * 2f) / 2f; /* 0.5 dB steps */
    }

    private int loudnessFor(float r) {
        int base  = clamp(mConfig.get("loudness_target", Config.DEFAULT_LOUDNESS_TARGET), 0, 2000);
        int extra = clamp(mConfig.get("loudness_low",    Config.DEFAULT_LOUDNESS_LOW),    0, 1000);
        float mb = (base + extra * lowFactor(r)) * (1f - tailFactor(r));
        return Math.round(mb / 10f) * 10; /* 10 mB steps */
    }

    /** Pushes the volume-dependent values to the effects, skipping unchanged ones. */
    private void applyAdaptive(boolean force) {
        float r = currentR();
        if (!force && r == mAppliedR) return;
        if (mDp != null)       applyDp(mDp, r);
        if (mLoudness != null) mLoudness.setTargetGain(loudnessFor(r));
        mAppliedR = r;
    }

    /* ------------------------------------------------------------------ */
    /* Keeping the effects alive                                          */
    /* ------------------------------------------------------------------ */

    private void watch(AudioEffect e) {
        try {
            e.setControlStatusListener(mControlCb);
            e.setEnableStatusListener(mEnableCb);
        } catch (Exception ignored) {}
    }

    private boolean shouldBeEnabled(AudioEffect e) {
        if (e == mBass)     return mConfig.get("bass_enabled", Config.DEFAULT_BASS_ENABLED) == 1;
        if (e == mLoudness) return mConfig.get("loudness_enabled", Config.DEFAULT_LOUDNESS_ENABLED) == 1;
        if (e == mDp)       return mConfig.get("dp_enabled", Config.DEFAULT_DP_ENABLED) == 1;
        return false;
    }

    private void scheduleVerify() {
        mHandler.removeCallbacks(mVerify);
        mHandler.postDelayed(mVerify, 300);
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
    }

    private void releaseDp() {
        if (mDp != null) {
            try { mDp.release(); } catch (Exception ignored) {}
            mDp = null;
        }
        mDpBands = 0;
        mAppliedR = Float.NaN;
    }

    /** Creates whichever effect is missing (e.g. it failed while booting). */
    private void ensureEffects() {
        if (mBass == null)     syncBass();
        if (mLoudness == null) syncLoudness();
        if (mDp == null)       syncDp();
    }

    /**
     * Cheap liveness probe: re-asserting the enabled state throws if the effect object is
     * dead, in which case the effect is rebuilt.
     */
    private void verifyEffects() {
        try {
            if (mBass == null) syncBass();
            else mBass.setEnabled(mConfig.get("bass_enabled", Config.DEFAULT_BASS_ENABLED) == 1);
        } catch (Exception e) {
            releaseBass();
            syncBass();
        }
        try {
            if (mLoudness == null) syncLoudness();
            else mLoudness.setEnabled(mConfig.get("loudness_enabled", Config.DEFAULT_LOUDNESS_ENABLED) == 1);
        } catch (Exception e) {
            releaseLoudness();
            syncLoudness();
        }
        try {
            if (mDp == null) syncDp();
            else mDp.setEnabled(mConfig.get("dp_enabled", Config.DEFAULT_DP_ENABLED) == 1);
        } catch (Exception e) {
            releaseDp();
            syncDp();
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
        mPolling = false;
        mHandler.removeCallbacksAndMessages(null);
        try { mAudio.unregisterAudioPlaybackCallback(mPlaybackCb); } catch (Exception ignored) {}
        releaseBass();
        releaseLoudness();
        releaseDp();
        mConfig.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
