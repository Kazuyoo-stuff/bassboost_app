package dev.kazuyoo.bassboost;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
import android.database.Cursor;

public class Config extends SQLiteOpenHelper {

    private static final String DB_NAME    = "config.db";
    private static final int    DB_VERSION = 1;

    /* default values */
    public static final int DEFAULT_BASS_STRENGTH     = 1000; /* 0-1000 */
    public static final int DEFAULT_LOUDNESS_TARGET   = 0;    /* milibel (mB), 0 = tanpa gain, 600 = +6 dB */
    public static final int DEFAULT_BASS_ENABLED      = 1;
    public static final int DEFAULT_LOUDNESS_ENABLED  = 1;
    public static final int DEFAULT_DP_ENABLED        = 1;    /* DynamicsProcessing low-band boost */
    public static final int DEFAULT_DP_GAIN           = 6;    /* dB, 0-15 */

    public Config(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
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
        insert(db, "dp_enabled",       DEFAULT_DP_ENABLED);
        insert(db, "dp_gain",          DEFAULT_DP_GAIN);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    private void insert(SQLiteDatabase db, String key, int value) {
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", String.valueOf(value));
        db.insertWithOnConflict("settings", null, cv,
                                SQLiteDatabase.CONFLICT_IGNORE);
    }

    public int get(String key, int def) {
        try (SQLiteDatabase db = getReadableDatabase();
             Cursor c = db.rawQuery(
                 "SELECT value FROM settings WHERE key=?",
                 new String[]{key})) {
            if (c.moveToFirst()) return Integer.parseInt(c.getString(0));
        } catch (Exception ignored) {}
        return def;
    }

    /* REPLACE (bukan UPDATE) supaya key baru di DB lama ikut tersimpan */
    public void set(String key, int value) {
        try (SQLiteDatabase db = getWritableDatabase()) {
            ContentValues cv = new ContentValues();
            cv.put("key", key);
            cv.put("value", String.valueOf(value));
            db.insertWithOnConflict("settings", null, cv,
                                    SQLiteDatabase.CONFLICT_REPLACE);
        }
    }
}
