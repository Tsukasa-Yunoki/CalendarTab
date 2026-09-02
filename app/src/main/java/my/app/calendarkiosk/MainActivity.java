package my.app.calendarkiosk;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.CalendarContract;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private LinearLayout mainScrollLeft, mainScrollRight, sideScrollLeft, sideScrollRight, footerArea, mainContainer, sideContainer;
    private TextView footerTimeTable, footerImportant, statusText;
    private Handler handler = new Handler();
    private Runnable updateRunnable;
    private int tapCount = 0;
    private long lastTapTime = 0;

    private static final String PREF_NAME = "KioskPrefs_Final";
    private static final String K_MAX = "max", K_MIN = "min", K_OFF = "off", K_SIDE = "side", K_FOOT = "foot", K_RATIO = "ratio", K_W = "w", K_H = "h", K_AUTO = "auto", K_FIX = "fix", K_FOLD = "fold", K_SIDE_FOLD = "side_fold";
    private static final String K_WORDS = "words", K_C_NOR = "c_nor", K_C_IMP = "c_imp";
    // フッター個別に制御するためのキー
    private static final String K_SHOW_FOOT_1 = "show_foot_1", K_SHOW_FOOT_2 = "show_foot_2";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        initViews();
        statusText.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastTapTime < 500) tapCount++; else tapCount = 1;
            lastTapTime = now;
            if (tapCount == 3) { tapCount = 0; showTopMenu(); }
        });

        updateRunnable = new Runnable() {
            @Override
            public void run() {
                fetchCalendarData();
                handler.postDelayed(this, 10 * 60 * 1000);
            }
        };
        checkPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(updateRunnable);
        handler.post(updateRunnable);
    }

    private void initViews() {
        mainScrollLeft = findViewById(R.id.mainScrollLeft); mainScrollRight = findViewById(R.id.mainScrollRight);
        sideScrollLeft = findViewById(R.id.sideScrollLeft); sideScrollRight = findViewById(R.id.sideScrollRight);
        footerArea = findViewById(R.id.footerArea); mainContainer = findViewById(R.id.mainContainer); sideContainer = findViewById(R.id.sideContainer);
        footerTimeTable = findViewById(R.id.footerTimeTable); footerImportant = findViewById(R.id.footerImportant);
        statusText = findViewById(R.id.statusText);
        if(footerTimeTable != null) footerTimeTable.setSelected(true);
        if(footerImportant != null) footerImportant.setSelected(true);
    }

    private void showTopMenu() {
        String[] items = {"1. カレンダー選択", "2. 文字サイズ設定", "3. レイアウト設定", "4. 重要ワード・色設定"};
        new AlertDialog.Builder(this).setTitle("総合設定").setItems(items, (d, i) -> {
            if (i == 0) showCalendarMenu();
            else if (i == 1) showFontMenu();
            else if (i == 2) showLayoutMenu();
            else showCustomMenu();
        }).show();
    }

    private void showCalendarMenu() {
        String[] items = {"メインカレンダー選択", "概要欄カレンダー選択"};
        new AlertDialog.Builder(this).setItems(items, (d, i) -> showCalendarSelector(i == 0)).show();
    }

    private void showFontMenu() {
        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String[] items = {
                "自動サイズ計算: " + (p.getBoolean(K_AUTO, true) ? "ON" : "OFF"),
                "メイン：上限", "メイン：下限", "メイン：補正",
                "メイン：固定サイズ", "サイド：サイズ", "概要：サイズ", "日付縮小率(%)"
        };
        new AlertDialog.Builder(this).setItems(items, (d, i) -> {
            if(i==0) { p.edit().putBoolean(K_AUTO, !p.getBoolean(K_AUTO, true)).apply(); fetchCalendarData(); }
            // 文字サイズの上限・下限の範囲を拡張 (例: 4sp〜120sp)
            else if(i==1) showSB(items[i], K_MAX, 4, 120, 36);
            else if(i==2) showSB(items[i], K_MIN, 4, 80, 16);
            else if(i==3) showSB(items[i], K_OFF, -40, 40, 0);
            else if(i==4) showSB(items[i], K_FIX, 4, 120, 25);
            else if(i==5) showSB(items[i], K_SIDE, 4, 80, 14);
            else if(i==6) showSB(items[i], K_FOOT, 4, 80, 18);
            else showSB(items[i], K_RATIO, 10, 100, 80);
        }).show();
    }

    private void showLayoutMenu() {
        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean showFoot1 = p.getBoolean(K_SHOW_FOOT_1, true);
        boolean showFoot2 = p.getBoolean(K_SHOW_FOOT_2, true);
        String[] items = {
                "フッター行1(概要): " + (showFoot1 ? "ON" : "OFF"),
                "フッター行2(重要): " + (showFoot2 ? "ON" : "OFF"),
                "メイン幅(%)", "概要高さ(px)", "メイン左列上限", "サイド左列上限"
        };
        new AlertDialog.Builder(this).setItems(items, (d, i) -> {
            if(i==0) { p.edit().putBoolean(K_SHOW_FOOT_1, !showFoot1).apply(); fetchCalendarData(); }
            else if(i==1) { p.edit().putBoolean(K_SHOW_FOOT_2, !showFoot2).apply(); fetchCalendarData(); }
            else if(i==2) showSB(items[i], K_W, 20, 95, 67);
            else if(i==3) showSB(items[i], K_H, 40, 500, 100);
            else if(i==4) showSB(items[i], K_FOLD, 1, 30, 6);
            else showSB(items[i], K_SIDE_FOLD, 1, 40, 10);
        }).show();
    }

    private void showCustomMenu() {
        String[] items = {"重要ワード設定 (カンマ区切り)", "通常文字色 (#RRGGBB)", "重要文字色 (#RRGGBB)"};
        new AlertDialog.Builder(this).setItems(items, (d, i) -> {
            if (i == 0) showEditTextDialog("重要ワード", K_WORDS, "!");
            else if (i == 1) showEditTextDialog("通常文字色", K_C_NOR, "#FFFFFF");
            else showEditTextDialog("重要文字色", K_C_IMP, "#FF0000");
        }).show();
    }

    private void showEditTextDialog(String title, String key, String def) {
        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        EditText et = new EditText(this); et.setText(p.getString(key, def));
        new AlertDialog.Builder(this).setTitle(title).setView(et).setPositiveButton("保存", (d, w) -> {
            p.edit().putString(key, et.getText().toString()).apply(); fetchCalendarData();
        }).show();
    }

    private void showSB(String title, String key, int min, int max, int def) {
        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(60,40,60,40);
        int val = p.getInt(key, def);
        final TextView tv = new TextView(this); tv.setText("値: " + val); tv.setGravity(Gravity.CENTER);
        SeekBar sb = new SeekBar(this); sb.setMax(max - min); sb.setProgress(val - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean f) { tv.setText("値: " + (pr + min)); }
            public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
        });
        l.addView(tv); l.addView(sb);
        new AlertDialog.Builder(this).setTitle(title).setView(l).setPositiveButton("保存", (d, w) -> {
            p.edit().putInt(key, sb.getProgress() + min).apply(); fetchCalendarData();
        }).show();
    }

    private void showCalendarSelector(boolean isMain) {
        final List<String> ids = new ArrayList<>(), names = new ArrayList<>();
        Cursor c = null;
        try {
            c = getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                    new String[]{CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                do {
                    ids.add(c.getString(0));
                    names.add(c.getString(1) + "\n(" + c.getString(2) + ")");
                } while (c.moveToNext());
            }
        } catch (Exception e) { e.printStackTrace(); } finally { if(c != null) c.close(); }
        if (ids.isEmpty()) return;
        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String k = isMain ? "m_ids" : "f_ids";
        Set<String> sel = p.getStringSet(k, new HashSet<>());
        boolean[] chks = new boolean[ids.size()];
        for(int i=0; i<ids.size(); i++) if(sel.contains(ids.get(i))) chks[i]=true;
        new AlertDialog.Builder(this).setTitle(isMain?"メイン":"概要欄")
                .setMultiChoiceItems(names.toArray(new String[0]), chks, (d, w, ck) -> chks[w]=ck)
                .setPositiveButton("保存", (d, w) -> {
                    Set<String> ns = new HashSet<>();
                    for(int i=0; i<chks.length; i++) if(chks[i]) ns.add(ids.get(i));
                    p.edit().putStringSet(k, ns).apply(); fetchCalendarData();
                }).show();
    }

    private int parseColorSafe(String hex, String def) {
        try { return Color.parseColor(hex); } catch (Exception e) { return Color.parseColor(def); }
    }

    private void fetchCalendarData() {
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        ContentResolver.requestSync(null, CalendarContract.AUTHORITY, extras);

        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        applyLayout(p);
        mainScrollLeft.removeAllViews(); mainScrollRight.removeAllViews();
        sideScrollLeft.removeAllViews(); sideScrollRight.removeAllViews();

        int cNor = parseColorSafe(p.getString(K_C_NOR, "#FFFFFF"), "#FFFFFF");
        int cI = parseColorSafe(p.getString(K_C_IMP, "#FF0000"), "#FF0000");
        String[] iWords = p.getString(K_WORDS, "!").split("\\s*,\\s*");

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        long today0 = cal.getTimeInMillis();
        long limit = today0 + (8 * 24 * 60 * 60 * 1000L);
        long tomE = today0 + (2 * 24 * 60 * 60 * 1000L) - 1;

        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, today0); ContentUris.appendId(builder, limit);
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(builder.build(), new String[]{"title", "begin", "end", "calendar_color", "calendar_id", "allDay"}, null, null, "begin ASC");
            if (cursor == null) return;
            ArrayList<EventData> rawMainList = new ArrayList<>(), sList = new ArrayList<>();
            StringBuilder tt = new StringBuilder(), imS = new StringBuilder();
            Set<String> mIds = p.getStringSet("m_ids", new HashSet<>()), fIds = p.getStringSet("f_ids", new HashSet<>());
            HashSet<String> seen = new HashSet<>(), seenTT = new HashSet<>();

            while (cursor.moveToNext()) {
                String title = cursor.getString(0);
                long start = cursor.getLong(1), end = cursor.getLong(2);
                boolean isAllDay = cursor.getInt(5) == 1;
                if (isAllDay) {
                    Calendar ct = Calendar.getInstance(TimeZone.getTimeZone("UTC")); ct.setTimeInMillis(start);
                    Calendar cl = Calendar.getInstance(); cl.set(ct.get(Calendar.YEAR), ct.get(Calendar.MONTH), ct.get(Calendar.DAY_OF_MONTH), 0, 0, 0); cl.set(Calendar.MILLISECOND, 0);
                    start = cl.getTimeInMillis();
                    ct.setTimeInMillis(end); cl.set(ct.get(Calendar.YEAR), ct.get(Calendar.MONTH), ct.get(Calendar.DAY_OF_MONTH), 0, 0, 0); cl.set(Calendar.MILLISECOND, 0);
                    end = cl.getTimeInMillis();
                }
                if (end - 1 < today0) continue;
                String key = title + "_" + start;
                if (fIds.contains(cursor.getString(4)) && !seenTT.contains(key)) { tt.append(title).append("   "); seenTT.add(key); }
                if (mIds.contains(cursor.getString(4)) && !seen.contains(key)) {
                    seen.add(key);
                    boolean isMainCandidate = start <= tomE;
                    boolean isImportant = false;
                    for (String w : iWords) { if (!w.isEmpty() && title.contains(w)) { isImportant = true; break; } }
                    if (title.startsWith("★")) isImportant = true;
                    Calendar cS = Calendar.getInstance(); cS.setTimeInMillis(start);
                    Calendar cE = Calendar.getInstance(); cE.setTimeInMillis(end - 1);
                    boolean isMulti = cS.get(Calendar.DAY_OF_YEAR) != cE.get(Calendar.DAY_OF_YEAR);
                    EventData ed = new EventData(title, start, end, cursor.getInt(3), isAllDay, isImportant, isMulti);

                    if (isMainCandidate) rawMainList.add(ed); else sList.add(ed);

                    if (isImportant) {
                        SimpleDateFormat sdf = new SimpleDateFormat(isMulti ? "dd(E)-dd(E)" : "dd(E)", Locale.JAPAN);
                        imS.append("★").append(sdf.format(new Date(start))).append(" ").append(title).append("   ");
                    }
                }
            }

            // メイン側の溢れ（オーバーフロー）処理
            // メインの最大表示件数 = 左列上限(K_FOLD) × 2 (左右2列分)
            int mainFold = p.getInt(K_FOLD, 6);
            int mainMaxCapacity = mainFold * 2;

            ArrayList<EventData> mList = new ArrayList<>();
            if (rawMainList.size() > mainMaxCapacity) {
                // 容量内に収まる分をメインへ
                mList.addAll(rawMainList.subList(0, mainMaxCapacity));
                // 溢れた分をサイド側の先頭に追加
                List<EventData> overflow = rawMainList.subList(mainMaxCapacity, rawMainList.size());
                sList.addAll(0, overflow);
            } else {
                mList.addAll(rawMainList);
            }

            renderArea(mList, true, p, cNor, cI);
            renderArea(sList, false, p, cNor, cI);

            float fs = p.getInt(K_FOOT, 18);
            footerTimeTable.setText(tt); footerTimeTable.setTextSize(fs); footerTimeTable.setTextColor(cNor);
            footerImportant.setText(imS); footerImportant.setTextSize(fs); footerImportant.setTextColor(cI);
            statusText.setText("Update: " + new SimpleDateFormat("HH:mm").format(new Date()));
        } catch (Exception e) { e.printStackTrace(); } finally { if(cursor != null) cursor.close(); }
        applyFullImmersiveMode();
    }

    private void renderArea(ArrayList<EventData> list, boolean isM, SharedPreferences p, int cN, int cI) {
        int count = list.size();
        int fold = p.getInt(isM ? K_FOLD : K_SIDE_FOLD, isM ? 6 : 10);
        LinearLayout left = isM ? mainScrollLeft : sideScrollLeft, right = isM ? mainScrollRight : sideScrollRight;
        right.setVisibility(count > fold ? View.VISIBLE : View.GONE);
        float fs;
        if (isM) {
            if (p.getBoolean(K_AUTO, true)) {
                float auto = (count > 0) ? (450f / Math.max(1, (count > fold ? (count/2+1) : count))) : p.getInt(K_MAX, 36);
                fs = Math.max(4, Math.max(p.getInt(K_MIN, 16), Math.min(p.getInt(K_MAX, 36), auto)) + p.getInt(K_OFF, 0));
            } else fs = p.getInt(K_FIX, 25);
        } else fs = p.getInt(K_SIDE, 14);
        for (int i = 0; i < count; i++) {
            View v = createEventView(list.get(i), isM, fs, p, cN, cI);
            if (i < fold) left.addView(v); else right.addView(v);
        }
    }

    private View createEventView(EventData d, boolean isM, float size, SharedPreferences p, int cN, int cI) {
        LinearLayout container = new LinearLayout(this); container.setOrientation(LinearLayout.VERTICAL); container.setPadding(0, 8, 0, 8);
        TextView tvDate = new TextView(this); String timeStr;
        if (d.multi) {
            if (isM) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd(E) HH:mm", Locale.JAPAN);
                timeStr = sdf.format(new Date(d.b)) + " - " + (d.al ? new SimpleDateFormat("dd(E)", Locale.JAPAN).format(new Date(d.e - 1)) : sdf.format(new Date(d.e)));
            } else timeStr = new SimpleDateFormat("dd(E)", Locale.JAPAN).format(new Date(d.b)) + "〜" + new SimpleDateFormat("dd(E)", Locale.JAPAN).format(new Date(d.e - 1));
        } else if (d.al) timeStr = new SimpleDateFormat("dd(E) ", Locale.JAPAN).format(new Date(d.b)) + "終日";
        else timeStr = new SimpleDateFormat("dd(E) ", Locale.JAPAN).format(new Date(d.b)) + new SimpleDateFormat("HH:mm").format(new Date(d.b)) + " - " + new SimpleDateFormat("HH:mm").format(new Date(d.e));

        SpannableString ss = new SpannableString((d.imp ? "★ " : "● ") + timeStr);
        ss.setSpan(new ForegroundColorSpan(d.imp ? cI : d.col), 0, 1, 0);
        ss.setSpan(new ForegroundColorSpan(d.imp ? cI : cN), 1, ss.length(), 0);
        if (d.multi) ss.setSpan(new RelativeSizeSpan(p.getInt(K_RATIO, 80)/100f), 0, ss.length(), 0);
        tvDate.setText(ss);

        TextView tvTitle = new TextView(this); tvTitle.setText("   " + d.title); tvTitle.setTextColor(d.imp ? cI : cN);
        tvTitle.setSingleLine(true); tvTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE); tvTitle.setMarqueeRepeatLimit(-1); tvTitle.setSelected(true);
        tvDate.setTextSize(size * 0.75f); tvTitle.setTextSize(size);
        container.addView(tvDate); container.addView(tvTitle);
        return container;
    }

    private void applyLayout(SharedPreferences p) {
        float w = Math.max(20, p.getInt(K_W, 67)) / 10f;
        mainContainer.setLayoutParams(new LinearLayout.LayoutParams(0, -1, w));
        sideContainer.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 10 - w));

        // 2行の個別表示設定を取得
        boolean showFoot1 = p.getBoolean(K_SHOW_FOOT_1, true);
        boolean showFoot2 = p.getBoolean(K_SHOW_FOOT_2, true);

        footerTimeTable.setVisibility(showFoot1 ? View.VISIBLE : View.GONE);
        footerImportant.setVisibility(showFoot2 ? View.VISIBLE : View.GONE);

        // どちらか一方でもONならフッターエリアを表示、両方OFFなら非表示
        if (showFoot1 || showFoot2) {
            footerArea.setVisibility(View.VISIBLE);
            footerArea.getLayoutParams().height = (int) (p.getInt(K_H, 100) * getResources().getDisplayMetrics().density);
        } else {
            footerArea.setVisibility(View.GONE);
        }
    }

    private void checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) fetchCalendarData();
        else ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_CALENDAR}, 100);
    }

    private void applyFullImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if(f) applyFullImmersiveMode(); }

    private static class EventData {
        String title; long b, e; int col; boolean al, imp, multi;
        EventData(String t, long b, long e, int c, boolean al, boolean i, boolean m) {
            this.title=t; this.b=b; this.e=e; this.col=c; this.al=al; this.imp=i; this.multi=m;
        }
    }
}