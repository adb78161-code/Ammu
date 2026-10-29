package com.ammu.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Persistent device-only personal memory store. It is never used as neural training data. */
final class LocalMemoryDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "ammu_personal_memory.db";
    private static final int DATABASE_VERSION = 1;

    LocalMemoryDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, memory_key TEXT NOT NULL, fact TEXT NOT NULL, memory_type TEXT NOT NULL, source TEXT NOT NULL, verified INTEGER NOT NULL, expires_at_ms INTEGER, created_at TEXT NOT NULL, UNIQUE(memory_key))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS memories");
        onCreate(db);
    }

    synchronized void upsert(String key, String fact, String type, String source, boolean verified, long expiresAt, String createdAt) {
        ContentValues values = new ContentValues();
        values.put("memory_key", key);
        values.put("fact", fact);
        values.put("memory_type", type);
        values.put("source", source);
        values.put("verified", verified ? 1 : 0);
        values.put("expires_at_ms", expiresAt);
        values.put("created_at", createdAt);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertWithOnConflict("memories", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized void replaceLegacyFact(String fact, String key, String type, String source, boolean verified, long expiresAt, String createdAt) {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT id FROM memories WHERE fact = ? LIMIT 1", new String[]{fact});
        try {
            if (!cursor.moveToFirst()) upsert(key, fact, type, source, verified, expiresAt, createdAt);
        } finally {
            cursor.close();
        }
    }

    synchronized List<String> facts() {
        ArrayList<String> facts = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("memories", new String[]{"fact"}, null, null, null, null, "id ASC");
        try { while (cursor.moveToNext()) facts.add(cursor.getString(0)); }
        finally { cursor.close(); }
        return facts;
    }

    synchronized void deleteAll() { getWritableDatabase().delete("memories", null, null); }
    synchronized void delete(long id) { getWritableDatabase().delete("memories", "id = ?", new String[]{Long.toString(id)}); }
}
