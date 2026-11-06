package dev.kazuyoo.bassboost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * v1.1 sound, hardened.
 *
 * The signal path is exactly v1.1: Bass Boost, Loudness Enhancer and a Dynamics
 * Processing stage (4-band pre-EQ plus limiter) on the global output mix. Nothing is
 * volume-adaptive and nothing polls. What changed is how the effects are kept alive.
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
    public static final String EXTRA_VALUE                 = "value";

    private static final int NO_VALUE = Integer.MIN_VALUE;

    /* Pre-EQ band upper edges in Hz: 0-60 | 60-150 | 150-300 | 300+ */
    private static final float[] BAND_CUTOFF = { 60f, 150f, 300f, 20000f };

    private BassBoost          mBass;
    private LoudnessEnhancer   mLoudness;
    private DynamicsProcessing mDp;
    private Config             mConfig;
    private AudioManager       mAudio;

    private boolean mPlaying = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /* Event-driven watchdog: whenever playback starts, make sure every effect is still
     * alive and in the expected state. No timers, no polling. */
    private final AudioManager.AudioPlaybackCallback mPlaybackCb =
        new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                boolean playing = configs != null && !configs.isEmpty();
                boolean started = playing && !mPlaying;
                mPlaying = playing;
                if (started) verifyEffects();
            }
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
    /* rebuilt once from scratch. A failure of one effect never affects    */
    /* the others.                                                        */
    /* ------------------------------------------------------------------ */

    private void syncAll() {
        syncBass();
        syncLoudness();
        syncDp();
    }

    private void syncBass() {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (mBass == null) mBass = new BassBoost(Integer.MAX_VALUE, 0); /* session 0 = global mix */
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
                if (mLoudness == null) mLoudness = new LoudnessEnhancer(0);
                mLoudness.setTargetGain(clamp(mConfig.get("loudness_target", Config.DEFAULT_LOUDNESS_TARGET), 0, 2000));
                mLoudness.setEnabled(mConfig.get("loudness_enabled", Config.DEFAULT_LOUDNESS_ENABLED) == 1);
                return;
            } catch (Exception e) {
                Log.e(TAG, "LoudnessEnhancer failed (attempt " + (attempt + 1) + "): " + e);
                releaseLoudness();
            }
        }
    }

    private void syncDp() {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (mDp == null) mDp = buildDp();
                applyLowGain(mDp, clamp(mConfig.get("dp_gain", Config.DEFAULT_DP_GAIN), 0, 15));
                mDp.setEnabled(mConfig.get("dp_enabled", Config.DEFAULT_DP_ENABLED) == 1);
                return;
            } catch (Exception e) {
                Log.e(TAG, "DynamicsProcessing failed (attempt " + (attempt + 1) + "): " + e);
                releaseDp();
            }
        }
    }

    /** Dynamics Processing with a 4-band pre-EQ and a limiter, identical to v1.1. */
    private DynamicsProcessing buildDp() {
        DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,          /* channels (stereo) */
            true, 4,    /* pre-EQ: 4 bands */
            false, 0,   /* MBC unused */
            false, 0,   /* post-EQ unused */
            true);      /* limiter */

        DynamicsProcessing dp = new DynamicsProcessing(Integer.MAX_VALUE, 0, b.build());
        try {
            /* Both channels share link group 0, so the limiter reacts to the louder one
             * and the stereo image stays put. */
            dp.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(
                true, true, 0,
                1f,     /* attack ms     */
                80f,    /* release ms    */
                20f,    /* ratio         */
                -3f,    /* threshold dB  */
                0f));   /* post gain dB (applied after limiting) */

            DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, 4);
            for (int i = 0; i < 4; i++) {
                eq.setBand(i, new DynamicsProcessing.EqBand(true, BAND_CUTOFF[i], 0f));
            }
            dp.setPreEqAllChannelsTo(eq);
        } catch (RuntimeException e) {
            try { dp.release(); } catch (Exception ignored) {}
            throw e;
        }
        return dp;
    }

    /**
     * Stage parameters can be changed while the effect runs, so only the two boosted
     * bands are touched instead of rebuilding the effect.
     *   0-60 Hz: untouched | 60-150 Hz: full gain | 150-300 Hz: 60% | 300 Hz+: untouched
     */
    private void applyLowGain(DynamicsProcessing dp, int gainDb) {
        dp.setPreEqBandAllChannelsTo(1, new DynamicsProcessing.EqBand(true, BAND_CUTOFF[1], gainDb));
        dp.setPreEqBandAllChannelsTo(2, new DynamicsProcessing.EqBand(true, BAND_CUTOFF[2], gainDb * 0.6f));
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
    }

    /** Creates whichever effect is missing (e.g. it failed while booting). */
    private void ensureEffects() {
        if (mBass == null)     syncBass();
        if (mLoudness == null) syncLoudness();
        if (mDp == null)       syncDp();
    }

    /**
     * Cheap liveness probe, run when playback starts: re-asserting the enabled state
     * throws if the effect object is dead, in which case the effect is rebuilt.
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
