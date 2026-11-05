package dev.kazuyoo.bassboost;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent settings. All values are loaded into memory once; reads never touch
 * the database. Writes go through a single background thread so the main thread
 * is never blocked by disk I/O.
 */
public class Config extends SQLiteOpenHelper {

    private static final String TAG        = "BassConfig";
    private static final String DB_NAME    = "config.db";
    private static final int    DB_VERSION = 1;

    /* default values */
    public static final int DEFAULT_BASS_ENABLED      = 1;
    public static final int DEFAULT_BASS_STRENGTH     = 1000; /* 0-1000 */
    public static final int DEFAULT_LOUDNESS_ENABLED  = 1;
    public static final int DEFAULT_LOUDNESS_TARGET   = 0;    /* millibel (mB), 0 = no gain, 600 = +6 dB */
    public static final int DEFAULT_LOUDNESS_LOW      = 250;  /* mB, extra loudness at minimum volume, fades out by 50% */
    public static final int DEFAULT_DP_ENABLED        = 1;    /* Dynamics Processing low-band boost */
    public static final int DEFAULT_DP_GAIN           = 6;    /* dB, 0-15, bass gain up to mid volume */
    public static final int DEFAULT_DP_GAIN_MAX       = 4;    /* dB, 0-15, bass gain at max volume */
    public static final int DEFAULT_ADAPTIVE          = 1;    /* 1 = taper gain as volume rises */
    public static final int DEFAULT_CLARITY           = 0;    /* 1 = multiband vocal/presence control */

    private final Map<String, Integer> mCache = new ConcurrentHashMap<>();
    private final ExecutorService      mIo    = Executors.newSingleThreadExecutor();

    public Config(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
        load();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS settings (" +
            "  key   TEXT PRIMARY KEY," +
            "  value TEXT NOT NULL" +
            ")"
        );
        insert(db, "bass_enabled",     DEFAULT_BASS_ENABLED);
        insert(db, "bass_strength",    DEFAULT_BASS_STRENGTH);
        insert(db, "loudness_enabled", DEFAULT_LOUDNESS_ENABLED);
        insert(db, "loudness_target",  DEFAULT_LOUDNESS_TARGET);
        insert(db, "loudness_low",     DEFAULT_LOUDNESS_LOW);
        insert(db, "dp_enabled",       DEFAULT_DP_ENABLED);
        insert(db, "dp_gain",          DEFAULT_DP_GAIN);
        insert(db, "dp_gain_max",      DEFAULT_DP_GAIN_MAX);
        insert(db, "adaptive",         DEFAULT_ADAPTIVE);
        insert(db, "clarity",          DEFAULT_CLARITY);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    private void insert(SQLiteDatabase db, String key, int value) {
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", String.valueOf(value));
        db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private void load() {
        try {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor c = db.rawQuery("SELECT key, value FROM settings", null)) {
                while (c.moveToNext()) {
                    try {
                        mCache.put(c.getString(0), Integer.parseInt(c.getString(1)));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "load failed, using defaults: " + e);
        }
    }

    /** Returns the stored value, or {@code def} if the key is missing. Never blocks on disk. */
    public int get(String key, int def) {
        Integer v = mCache.get(key);
        return v != null ? v : def;
    }

    /** Updates memory immediately and persists in the background. */
    public void set(String key, int value) {
        mCache.put(key, value);
        mIo.execute(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues cv = new ContentValues();
                cv.put("key", key);
                cv.put("value", String.valueOf(value));
                /* REPLACE so new keys are stored in databases created by older versions */
                db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception e) {
                Log.e(TAG, "persist failed (" + key + "): " + e);
            }
        });
    }

    /** Lets queued writes finish, then stops the writer thread. */
    public void shutdown() {
        mIo.shutdown();
    }
}
