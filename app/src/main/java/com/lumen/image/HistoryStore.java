package com.lumen.image;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史记录：SQLite 存元数据，图片存 App 私有目录。
 *
 * 设计：
 * - 图片不回写相册（除非用户主动导出），避免污染图库
 * - 元数据记下 prompt/参数/seed，能原样复现
 * - 缩略图单独存一份，列表滚动不卡
 */
public class HistoryStore extends SQLiteOpenHelper {

    private static final String TAG = "LumenHistory";
    private static final String DB_NAME = "lumen_history.db";
    private static final int DB_VER = 1;

    private static final String T = "images";
    private static final String C_ID       = "id";
    private static final String C_PATH     = "path";
    private static final String C_THUMB    = "thumb";
    private static final String C_PROMPT   = "prompt";
    private static final String C_NEG      = "negative";
    private static final String C_STEPS    = "steps";
    private static final String C_CFG      = "cfg";
    private static final String C_SIZE     = "size";
    private static final String C_SEED     = "seed";
    private static final String C_BACKEND  = "backend";
    private static final String C_ELAPSED  = "elapsed_ms";
    private static final String C_CREATED  = "created_at";

    private final Context ctx;

    public HistoryStore(Context c) {
        super(c.getApplicationContext(), DB_NAME, null, DB_VER);
        this.ctx = c.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + " (" +
                C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                C_PATH + " TEXT NOT NULL," +
                C_THUMB + " TEXT," +
                C_PROMPT + " TEXT," +
                C_NEG + " TEXT," +
                C_STEPS + " INTEGER," +
                C_CFG + " REAL," +
                C_SIZE + " INTEGER," +
                C_SEED + " TEXT," +
                C_BACKEND + " TEXT," +
                C_ELAPSED + " INTEGER," +
                C_CREATED + " INTEGER)");
        db.execSQL("CREATE INDEX idx_created ON " + T + "(" + C_CREATED + " DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { /* v1 无迁移 */ }

    // ─────────────────────────────────────────────────────────

    public static class Item {
        public long id;
        public String path, thumb, prompt, negative, seed, backend;
        public int steps, size;
        public long elapsedMs;
        public long createdAt;
        public float cfg;
    }

    /** 图片和缩略图所在目录 */
    public File dir() {
        File d = new File(ctx.getFilesDir(), "images");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * 保存一张图 + 元信息。返回记录 id，失败 -1。
     * 缩略图长边 256，便于列表快速显示。
     */
    public long save(Bitmap bmp, String prompt, String negative, int steps, float cfg,
                     int size, String seed, String backend, long elapsedMs) {
        if (bmp == null) return -1;
        long now = System.currentTimeMillis();
        String name = "img_" + now + ".png";
        File f = new File(dir(), name);
        try (FileOutputStream fo = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
        } catch (Exception e) {
            Log.e(TAG, "写图失败", e);
            return -1;
        }

        String thumbName = "th_" + now + ".jpg";
        File tf = new File(dir(), thumbName);
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            float s = 256f / Math.max(w, h);
            Bitmap th = (s < 1f)
                    ? Bitmap.createScaledBitmap(bmp, Math.max(1,(int)(w*s)), Math.max(1,(int)(h*s)), true)
                    : bmp;
            try (FileOutputStream fo = new FileOutputStream(tf)) {
                th.compress(Bitmap.CompressFormat.JPEG, 85, fo);
            }
            if (th != bmp) th.recycle();
        } catch (Exception e) {
            Log.w(TAG, "缩略图失败", e);
        }

        ContentValues v = new ContentValues();
        v.put(C_PATH, f.getAbsolutePath());
        v.put(C_THUMB, tf.getAbsolutePath());
        v.put(C_PROMPT, prompt);
        v.put(C_NEG, negative);
        v.put(C_STEPS, steps);
        v.put(C_CFG, cfg);
        v.put(C_SIZE, size);
        v.put(C_SEED, seed);
        v.put(C_BACKEND, backend);
        v.put(C_ELAPSED, elapsedMs);
        v.put(C_CREATED, now);
        return getWritableDatabase().insert(T, null, v);
    }

    /** 最近 N 条，倒序 */
    public List<Item> recent(int limit) {
        List<Item> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(T, null, null, null, null, null,
                    C_CREATED + " DESC", String.valueOf(limit));
            while (c.moveToNext()) out.add(read(c));
        } catch (Exception e) {
            Log.e(TAG, "查询失败", e);
        } finally { if (c != null) c.close(); }
        return out;
    }

    private Item read(Cursor c) {
        Item it = new Item();
        it.id        = c.getLong(c.getColumnIndexOrThrow(C_ID));
        it.path      = c.getString(c.getColumnIndexOrThrow(C_PATH));
        it.thumb     = c.getString(c.getColumnIndexOrThrow(C_THUMB));
        it.prompt    = c.getString(c.getColumnIndexOrThrow(C_PROMPT));
        it.negative  = c.getString(c.getColumnIndexOrThrow(C_NEG));
        it.steps     = c.getInt(c.getColumnIndexOrThrow(C_STEPS));
        it.cfg       = c.getFloat(c.getColumnIndexOrThrow(C_CFG));
        it.size      = c.getInt(c.getColumnIndexOrThrow(C_SIZE));
        it.seed      = c.getString(c.getColumnIndexOrThrow(C_SEED));
        it.backend   = c.getString(c.getColumnIndexOrThrow(C_BACKEND));
        it.elapsedMs = c.getLong(c.getColumnIndexOrThrow(C_ELAPSED));
        it.createdAt = c.getLong(c.getColumnIndexOrThrow(C_CREATED));
        return it;
    }

    /** 删一条（连文件一起删） */
    public void delete(long id) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query(T, new String[]{C_PATH, C_THUMB},
                    C_ID + "=?", new String[]{String.valueOf(id)}, null, null, null);
            if (c.moveToFirst()) {
                deleteQuiet(c.getString(0));
                deleteQuiet(c.getString(1));
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        getWritableDatabase().delete(T, C_ID + "=?", new String[]{String.valueOf(id)});
    }

    private static void deleteQuiet(String p) {
        if (p == null) return;
        try { new File(p).delete(); } catch (Exception ignored) {}
    }

    public Bitmap loadThumb(Item it) {
        try {
            return BitmapFactory.decodeFile(it.thumb);
        } catch (Exception e) { return null; }
    }

    public Bitmap loadFull(Item it) {
        try {
            return BitmapFactory.decodeFile(it.path);
        } catch (Exception e) { return null; }
    }

    /** 从记录里恢复参数到 Config（复现） */
    public void applyTo(Item it, Config cfg) {
        if (it == null) return;
        cfg.setSteps(it.steps);
        cfg.setCfg(it.cfg);
        cfg.setSize(it.size);
    }
}