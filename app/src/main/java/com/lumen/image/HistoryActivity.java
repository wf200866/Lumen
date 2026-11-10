package com.lumen.image;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 历史记录：网格浏览 → 点开看详情 → 可复现/导出/删除。
 */
public class HistoryActivity extends AppCompatActivity {

    /** 选中后回传给主界面的结果 */
    public static final String EXTRA_PICK_ID = "pick_id";

    private HistoryStore store;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<HistoryStore.Item> items = new ArrayList<>();
    private GridAdapter adapter;
    private TextView tvEmpty;
    private GridView gv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_history);

        store = new HistoryStore(this);
        tvEmpty = findViewById(R.id.tv_empty);
        gv = findViewById(R.id.gv_history);

        adapter = new GridAdapter();
        gv.setAdapter(adapter);

        findViewById(R.id.btn_close_history).setOnClickListener(v -> finish());

        gv.setOnItemClickListener((p, v, pos, id) -> showDetail(items.get(pos)));
        gv.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDelete(items.get(pos));
            return true;
        });

        reload();
    }

    private void reload() {
        pool.execute(() -> {
            List<HistoryStore.Item> list = store.recent(300);
            ui.post(() -> {
                items.clear();
                items.addAll(list);
                adapter.notifyDataSetChanged();
                boolean empty = items.isEmpty();
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                gv.setVisibility(empty ? View.GONE : View.VISIBLE);
                ((TextView) findViewById(R.id.tv_title))
                        .setText(empty ? "历史记录" : "历史记录 · " + items.size());
            });
        });
    }

    /** 详情弹窗 */
    private void showDetail(HistoryStore.Item it) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 12);
        box.setPadding(pad, pad, pad, pad);

        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (getResources().getDisplayMetrics().density * 240)));

        TextView meta = new TextView(this);
        meta.setTextSize(11);
        meta.setTextColor(0xFF8A8AA0);
        meta.setPadding(0, pad, 0, 0);
        meta.setText(
                "时间　" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .format(new java.util.Date(it.createdAt)) + "\n" +
                "参数　" + it.size + "×" + it.size + " · " + it.steps + " 步 · CFG " + it.cfg + "\n" +
                "耗时　" + (it.elapsedMs / 1000) + " 秒\n" +
                "后端　" + (it.backend == null ? "-" : it.backend) + "\n" +
                "seed　" + (it.seed == null ? "-" : it.seed) + "\n\n" +
                "提示词\n" + (it.prompt == null ? "" : it.prompt) +
                (it.negative == null || it.negative.isEmpty() ? "" : "\n\n负向\n" + it.negative));
        box.addView(meta);

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(box);

        pool.execute(() -> {
            Bitmap bmp = store.loadFull(it);
            ui.post(() -> {
                if (bmp != null) iv.setImageBitmap(bmp);
                new AlertDialog.Builder(this)
                        .setTitle("详情")
                        .setView(sc)
                        .setPositiveButton("用这组参数", (d, w) -> {
                            Intent r = new Intent();
                            r.putExtra(EXTRA_PICK_ID, it.id);
                            setResult(RESULT_OK, r);
                            finish();
                        })
                        .setNeutralButton("导出相册", (d, w) -> exportTo(it))
                        .setNegativeButton("删除", (d, w) -> confirmDelete(it))
                        .show();
            });
        });
    }

    private void exportTo(HistoryStore.Item it) {
        pool.execute(() -> {
            Bitmap bmp = store.loadFull(it);
            if (bmp == null) { toast("图已丢失"); return; }
            try {
                java.io.File dir = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_PICTURES), "Lumen");
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir,
                        "lumen_" + it.createdAt + ".png");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
                }
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        android.net.Uri.fromFile(f)));
                ui.post(() -> toast("已导出 " + f.getName()));
            } catch (Exception e) {
                ui.post(() -> toast("导出失败：" + e.getMessage()));
            }
        });
    }

    private void confirmDelete(HistoryStore.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("删除这张图？")
                .setMessage("图片文件和记录都会删掉，不能恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    pool.execute(() -> {
                        store.delete(it.id);
                        ui.post(this::reload);
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ── 网格适配器 ──
    private class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            ImageView iv;
            if (convert == null) {
                iv = new ImageView(HistoryActivity.this);
                int h = (int) (getResources().getDisplayMetrics().density * 110);
                iv.setLayoutParams(new GridView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, h));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0xFF16161E);
            } else {
                iv = (ImageView) convert;
            }
            HistoryStore.Item it = items.get(pos);
            iv.setTag(it.id);
            iv.setImageDrawable(null);

            Bitmap bmp = store.loadThumb(it);
            if (bmp != null && Long.valueOf(it.id).equals(iv.getTag())) {
                iv.setImageBitmap(bmp);
            }
            return iv;
        }
    }
}