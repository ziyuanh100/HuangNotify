package dev.huang.notify;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native, dependency-light Android UI. Database and encryption never run on the UI thread. */
public final class MainActivity extends Activity {
  private Ui u;
  private LinearLayout root, content, nav, statusBox;
  private TextView statusTitle,
      statusBody,
      todayCount,
      totalCount,
      starCount,
      sectionTitle,
      empty,
      loadMore;
  private ListView list;
  private RecordsAdapter adapter;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final List<Record> rows = new ArrayList<>();
  private List<String[]> apps = new ArrayList<>();
  private String page = "records",
      query = "",
      filterPkg = null,
      filterName = "全部 App",
      paused = "0",
      retention = "0",
      lastSaved = "0";
  private long[] counts = {0, 0, 0};
  private boolean onlyToday = false, refreshing = false, resumed = false, busy = false;
  private int limit = 80, generation;
  private byte[] pendingExport;
  private boolean pendingEncrypted;
  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (resumed) {
            refresh();
            handler.postDelayed(this, 2500);
          }
        }
      };
  private final Runnable searchRefresh =
      () -> {
        limit = 80;
        generation++;
        refresh();
      };

  @Override
  public void onCreate(Bundle state) {
    if ((getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        == android.content.res.Configuration.UI_MODE_NIGHT_YES)
      setTheme(android.R.style.Theme_Material_NoActionBar);
    super.onCreate(state);
    u = new Ui(this);
    if (state != null) {
      page = state.getString("page", "records");
      query = state.getString("query", "");
      filterPkg = state.getString("pkg");
      filterName = state.getString("filter", "全部 App");
      onlyToday = state.getBoolean("today");
    }
    getWindow().setStatusBarColor(u.bg);
    getWindow().setNavigationBarColor(u.surface);
    if (!u.dark)
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    root = u.column();
    root.setBackgroundColor(u.bg);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets s =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            return WindowInsets.CONSUMED;
          }
          v.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          return insets.consumeSystemWindowInsets();
        });
    if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    setContentView(root);
    build();
    Store.IO.execute(
        () -> {
          try {
            Store s = Store.get(this);
            s.cleanup(Integer.parseInt(s.setting("retention", "0")));
          } catch (Exception e) {
            Store.error = "清理未完成，原有紀錄仍保留";
          }
        });
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    handler.removeCallbacks(poll);
    handler.post(poll);
  }

  @Override
  protected void onPause() {
    resumed = false;
    handler.removeCallbacks(poll);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (pendingExport != null) Arrays.fill(pendingExport, (byte) 0);
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("page", page);
    b.putString("query", query);
    b.putString("pkg", filterPkg);
    b.putString("filter", filterName);
    b.putBoolean("today", onlyToday);
  }

  private void build() {
    root.removeAllViews();
    content = u.column();
    root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    if (page.equals("settings")) buildSettings();
    else buildRecords();
    nav = u.row();
    nav.setBackgroundColor(u.surface);
    nav.setPadding(u.d(12), u.d(8), u.d(12), u.d(6));
    for (String[] tab :
        new String[][] {
          {"records", "bell", "紀錄"}, {"favorites", "star", "收藏"}, {"settings", "settings", "設定"}
        }) {
      boolean selected = page.equals(tab[0]);
      LinearLayout button = u.column();
      button.setGravity(Gravity.CENTER);
      button.setPadding(0, u.d(8), 0, u.d(8));
      u.ripple(button, selected ? u.pale : u.surface, 20);
      button.addView(
          u.icon(tab[1], selected ? u.accent : u.muted),
          new LinearLayout.LayoutParams(u.d(24), u.d(24)));
      TextView label = u.text(tab[2], 12, selected ? u.accent : u.muted, selected);
      label.setGravity(Gravity.CENTER);
      label.setPadding(0, u.d(4), 0, 0);
      button.addView(label);
      LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
      bp.setMargins(u.d(5), 0, u.d(5), 0);
      nav.addView(button, bp);
      button.setContentDescription(tab[2] + (selected ? "，已選取" : ""));
      button.setFocusable(true);
      button.setOnClickListener(
          v -> {
            if (!page.equals(tab[0])) {
              hideKeyboard();
              page = tab[0];
              generation++;
              build();
              refresh();
            }
          });
    }
    root.addView(nav);
    updateHeader();
  }

  private void title(LinearLayout parent, String eyebrow, String title, String subtitle) {
    LinearLayout box = u.column();
    box.setPadding(u.d(24), u.d(22), u.d(24), u.d(14));
    TextView brand = u.text(eyebrow, 12, u.accent, true);
    brand.setLetterSpacing(.13f);
    box.addView(brand);
    u.gap(box, 7);
    TextView heading = u.text(title, 30, u.ink, true);
    if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
    box.addView(heading);
    u.gap(box, 5);
    box.addView(u.text(subtitle, 13, u.muted, false));
    parent.addView(box);
  }

  private void buildRecords() {
    title(
        content,
        "HUANGNOTIFY",
        page.equals("favorites") ? "值得留下的通知" : "讓通知，好好留下。",
        page.equals("favorites") ? "收藏的紀錄不會被定期清理。" : "即使通知消失，也能回來找找。 ");
    list = new ListView(this);
    list.setDivider(null);
    list.setDividerHeight(0);
    list.setCacheColorHint(Color.TRANSPARENT);
    list.setClipToPadding(false);
    list.setPadding(u.d(16), 0, u.d(16), u.d(12));
    LinearLayout header = u.column();
    header.setPadding(u.d(4), 0, u.d(4), u.d(6));
    statusBox = u.column();
    statusBox.setPadding(u.d(16), u.d(14), u.d(16), u.d(14));
    u.ripple(statusBox, u.pale, 18);
    statusTitle = u.text("確認記錄狀態…", 15, u.accent, true);
    statusBody = u.text("", 12, u.muted, false);
    statusBody.setPadding(0, u.d(5), 0, 0);
    statusBox.addView(statusTitle);
    statusBox.addView(statusBody);
    header.addView(statusBox);
    statusBox.setOnClickListener(v -> accessDialog());
    u.gap(header, 12);
    LinearLayout stats = u.row();
    todayCount = stat(stats, "今日新增", counts[1], true);
    totalCount = stat(stats, "目前保存", counts[0], false);
    starCount = stat(stats, "收藏", counts[2], false);
    header.addView(stats);
    u.gap(header, 16);
    LinearLayout search = u.row();
    search.setPadding(u.d(14), 0, u.d(10), 0);
    search.setBackground(u.shape(u.surface, 18));
    search.addView(u.icon("search", u.muted), new LinearLayout.LayoutParams(u.d(22), u.d(22)));
    EditText input = new EditText(this);
    input.setSingleLine();
    input.setTextSize(15);
    input.setTextColor(u.ink);
    input.setHintTextColor(u.muted);
    input.setHint("搜尋通知、App 或備註");
    input.setContentDescription("搜尋通知");
    input.setBackgroundColor(Color.TRANSPARENT);
    input.setPadding(u.d(12), u.d(14), 0, u.d(14));
    input.setText(query);
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    search.addView(input, new LinearLayout.LayoutParams(0, u.d(54), 1));
    TextView clear = u.text("清除", 13, u.accent, true);
    clear.setGravity(Gravity.CENTER);
    clear.setMinWidth(u.d(48));
    clear.setMinHeight(u.d(48));
    search.addView(clear);
    clear.setOnClickListener(v -> input.setText(""));
    input.setOnEditorActionListener(
        (v, id, event) -> {
          hideKeyboard();
          refresh();
          return true;
        });
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            query = s.toString();
            handler.removeCallbacks(searchRefresh);
            handler.postDelayed(searchRefresh, 250);
          }

          public void afterTextChanged(Editable e) {}
        });
    header.addView(search);
    u.gap(header, 12);
    LinearLayout filters = u.row();
    TextView appFilter = chip(filterName + " ▾", filterPkg != null);
    appFilter.setOnClickListener(v -> chooseApp());
    filters.addView(appFilter);
    TextView timeFilter = chip(onlyToday ? "今天 ▾" : "所有日期 ▾", onlyToday);
    LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2);
    fp.setMargins(u.d(8), 0, 0, 0);
    filters.addView(timeFilter, fp);
    timeFilter.setOnClickListener(
        v ->
            new AlertDialog.Builder(this)
                .setTitle("日期範圍")
                .setSingleChoiceItems(
                    new String[] {"所有日期", "今天"},
                    onlyToday ? 1 : 0,
                    (d, which) -> {
                      onlyToday = which == 1;
                      limit = 80;
                      generation++;
                      d.dismiss();
                      build();
                      refresh();
                    })
                .show());
    header.addView(filters);
    u.gap(header, 18);
    sectionTitle = u.text(page.equals("favorites") ? "已收藏" : "最近紀錄", 14, u.muted, true);
    header.addView(sectionTitle);
    u.gap(header, 8);
    empty = u.text("讀取紀錄中…", 15, u.muted, false);
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(u.d(24), u.d(30), u.d(24), u.d(30));
    header.addView(empty);
    list.addHeaderView(header, null, false);
    LinearLayout footer = u.column();
    footer.setPadding(u.d(4), u.d(8), u.d(4), u.d(20));
    loadMore = u.button("載入更多", false);
    loadMore.setOnClickListener(
        v -> {
          limit += 80;
          generation++;
          refresh();
        });
    footer.addView(loadMore);
    list.addFooterView(footer, null, false);
    adapter = new RecordsAdapter();
    list.setAdapter(adapter);
    list.setOnItemClickListener(
        (parent, v, position, id) -> {
          int index = position - list.getHeaderViewsCount();
          if (index >= 0 && index < rows.size()) detail(rows.get(index));
        });
    content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private TextView stat(LinearLayout row, String label, long value, boolean primary) {
    LinearLayout box = u.column();
    box.setPadding(u.d(12), u.d(13), u.d(10), u.d(13));
    box.setBackground(u.shape(primary ? u.accent : u.surface, 18));
    TextView number =
        u.text(format(value), 25, primary ? (u.dark ? 0xff123c2a : Color.WHITE) : u.ink, true);
    box.addView(number);
    TextView caption =
        u.text(label, 12, primary ? (u.dark ? 0xff244d3c : 0xffd7eee2) : u.muted, false);
    u.gap(box, 4);
    box.addView(caption);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    if (row.getChildCount() > 0) p.setMargins(u.d(8), 0, 0, 0);
    row.addView(box, p);
    return number;
  }

  private TextView chip(String title, boolean selected) {
    TextView t = u.text(title, 13, selected ? u.accent : u.muted, true);
    t.setPadding(u.d(14), u.d(12), u.d(14), u.d(12));
    u.ripple(t, selected ? u.pale : u.surface, 24);
    t.setMinHeight(u.d(48));
    t.setGravity(Gravity.CENTER);
    return t;
  }

  private void updateHeader() {
    if (page.equals("settings") || statusTitle == null) return;
    boolean access = hasAccess();
    String title, body;
    if (!access) {
      title = "開始保存你的通知";
      body = "點這裡開啟通知存取權；只存於這支手機。";
    } else if (paused.equals("1")) {
      title = "記錄已暫停";
      body = "已保存的紀錄仍在。到設定重新啟用。";
    } else if (!Store.error.isEmpty()) {
      title = "記錄需要處理";
      body = Store.error;
    } else if (CaptureService.connected) {
      title = "● 正在記錄";
      body =
          lastSaved.equals("0")
              ? "準備好了，等待下一則通知。"
              : "最近保存於 " + date(parseLong(lastSaved), "MM/dd HH:mm:ss");
    } else {
      title = "等待通知服務連線";
      body = "點這裡檢查權限與重新連線。";
    }
    statusTitle.setText(title);
    statusBody.setText(body);
    todayCount.setText(format(counts[1]));
    totalCount.setText(format(counts[0]));
    starCount.setText(format(counts[2]));
  }

  private void refresh() {
    updateHeader();
    if (refreshing || isFinishing() || isDestroyed()) return;
    refreshing = true;
    final int gen = generation;
    final String q = query, pkg = filterPkg;
    final boolean fav = page.equals("favorites"), today = onlyToday;
    final int amount = limit;
    Store.IO.execute(
        () -> {
          try {
            Store s = Store.get(this);
            long[] numbers = s.counts();
            List<String[]> sources = s.apps();
            List<Record> records = s.list(q, pkg, fav, today, amount + 1);
            String p = s.setting("paused", "0"),
                r = s.setting("retention", "0"),
                last = s.setting("last_saved", "0");
            runOnUiThread(
                () -> {
                  refreshing = false;
                  if (isDestroyed()) return;
                  boolean settingsChanged =
                      !paused.equals(p) || !retention.equals(r) || counts[0] != numbers[0];
                  counts = numbers;
                  apps = sources;
                  paused = p;
                  retention = r;
                  lastSaved = last;
                  if (settingsChanged && page.equals("settings")) build();
                  updateHeader();
                  if (gen != generation) {
                    refresh();
                    return;
                  }
                  if (!page.equals("settings")) {
                    boolean more = records.size() > amount;
                    if (more) records.remove(records.size() - 1);
                    rows.clear();
                    rows.addAll(records);
                    adapter.notifyDataSetChanged();
                    loadMore.setVisibility(more ? View.VISIBLE : View.GONE);
                    empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                    empty.setText(
                        !q.isEmpty() || pkg != null || today
                            ? "找不到符合條件的通知\n試試其他關鍵字或篩選條件。"
                            : fav
                                ? "還沒有收藏\n打開一則通知，點選「加入收藏」。"
                                : hasAccess()
                                    ? "你的通知會出現在這裡\n成功記錄後，原通知消失也能找回。"
                                    : "先開啟通知存取權\n不需要帳號，也不會上傳通知。 ");
                    sectionTitle.setText(
                        (fav ? "已收藏" : "最近紀錄") + " · " + rows.size() + (more ? "+" : "") + " 則");
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  refreshing = false;
                  Store.error = "無法讀取紀錄，請檢查儲存空間";
                  updateHeader();
                });
          }
        });
  }

  private void chooseApp() {
    String[] names = new String[apps.size() + 1];
    names[0] = "全部 App";
    int checked = 0;
    for (int i = 0; i < apps.size(); i++) {
      String[] a = apps.get(i);
      names[i + 1] = a[1] + " · " + a[2] + " 則";
      if (a[0].equals(filterPkg)) checked = i + 1;
    }
    new AlertDialog.Builder(this)
        .setTitle("依 App 篩選")
        .setSingleChoiceItems(
            names,
            checked,
            (d, which) -> {
              filterPkg = which == 0 ? null : apps.get(which - 1)[0];
              filterName = which == 0 ? "全部 App" : apps.get(which - 1)[1];
              limit = 80;
              generation++;
              d.dismiss();
              build();
              refresh();
            })
        .show();
  }

  private final class RecordsAdapter extends BaseAdapter {
    public int getCount() {
      return rows.size();
    }

    public Object getItem(int i) {
      return rows.get(i);
    }

    public long getItemId(int i) {
      return i;
    }

    public View getView(int position, View convert, ViewGroup parent) {
      RowHolder h;
      if (convert == null) {
        LinearLayout outer = u.column();
        outer.setPadding(u.d(4), u.d(5), u.d(4), u.d(5));
        LinearLayout card = u.column();
        card.setPadding(u.d(16), u.d(16), u.d(16), u.d(16));
        u.ripple(card, u.surface, 20);
        outer.addView(card);
        LinearLayout top = u.row();
        ImageView icon = new ImageView(MainActivity.this);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top.addView(icon, new LinearLayout.LayoutParams(u.d(28), u.d(28)));
        TextView app = u.text("", 12, u.muted, true);
        app.setSingleLine();
        app.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, -2, 1);
        ap.setMargins(u.d(10), 0, u.d(8), 0);
        top.addView(app, ap);
        TextView time = u.text("", 11, u.muted, false);
        top.addView(time);
        card.addView(top);
        u.gap(card, 11);
        TextView title = u.text("", 16, u.ink, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);
        u.gap(card, 5);
        TextView body = u.text("", 14, u.muted, false);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setLineSpacing(u.d(3), 1);
        card.addView(body);
        u.gap(card, 10);
        TextView meta = u.text("", 11, u.accent, false);
        card.addView(meta);
        h = new RowHolder(icon, app, time, title, body, meta);
        outer.setTag(h);
        convert = outer;
      } else h = (RowHolder) convert.getTag();
      Record r = rows.get(position);
      h.app.setText(r.app);
      h.time.setText(
          date(r.firstSeen, r.firstSeen >= Store.todayStart() ? "HH:mm" : "MM/dd HH:mm"));
      h.title.setText(r.title.isEmpty() ? "沒有標題的通知" : r.title);
      h.body.setText(r.text.isEmpty() ? "來源 App 未提供可讀取的文字內容。" : r.text);
      h.meta.setText(
          (r.favorite ? "已收藏  ·  " : "")
              + (r.active && CaptureService.connected
                  ? "仍在通知列"
                  : r.removed > 0 ? "已移除，紀錄已保留" : "已保存")
              + (r.versions > 1 ? "  ·  " + r.versions + " 個版本" : ""));
      try {
        h.icon.setImageDrawable(getPackageManager().getApplicationIcon(r.pkg));
      } catch (Exception e) {
        h.icon.setImageResource(android.R.drawable.ic_dialog_info);
      }
      return convert;
    }
  }

  private static final class RowHolder {
    final ImageView icon;
    final TextView app, time, title, body, meta;

    RowHolder(ImageView i, TextView a, TextView t, TextView h, TextView b, TextView m) {
      icon = i;
      app = a;
      time = t;
      title = h;
      body = b;
      meta = m;
    }
  }

  private void detail(Record r) {
    Store.IO.execute(
        () -> {
          try {
            JSONArray versions = Store.get(this).versions(r.id);
            runOnUiThread(() -> showDetail(r, versions));
          } catch (Exception e) {
            showError("無法讀取更新歷程");
          }
        });
  }

  private void showDetail(Record r, JSONArray versions) {
    if (isDestroyed()) return;
    LinearLayout body = dialogBody();
    TextView app =
        u.text(r.app + " · " + date(r.firstSeen, "yyyy/MM/dd HH:mm:ss"), 12, u.muted, false);
    body.addView(app);
    u.gap(body, 12);
    TextView title = u.text(r.title.isEmpty() ? "通知詳情" : r.title, 23, u.ink, true);
    title.setTextIsSelectable(true);
    body.addView(title);
    u.gap(body, 12);
    TextView text = u.text(r.text.isEmpty() ? "來源 App 沒有提供文字內容。" : r.text, 16, u.ink, false);
    text.setTextIsSelectable(true);
    text.setLineSpacing(u.d(5), 1);
    body.addView(text);
    u.gap(body, 16);
    body.addView(
        u.text(
            r.active && CaptureService.connected
                ? "仍在通知列"
                : r.removed > 0
                    ? EventPolicy.removalLabel(r.reason) + " · " + date(r.removed, "MM/dd HH:mm:ss")
                    : "已保存 · 目前通知狀態未知",
            12,
            u.muted,
            false));
    if (!r.note.isEmpty()) {
      u.gap(body, 16);
      body.addView(u.text("備註", 13, u.accent, true));
      body.addView(u.text(r.note, 15, u.ink, false));
    }
    u.gap(body, 18);
    TextView favorite = u.button(r.favorite ? "移除收藏" : "加入收藏", true);
    body.addView(favorite);
    favorite.setOnClickListener(
        v -> {
          r.favorite = !r.favorite;
          favorite.setText(r.favorite ? "移除收藏" : "加入收藏");
          boolean selected = r.favorite;
          mutate(() -> Store.get(this).favorite(r.id, selected), null);
        });
    u.gap(body, 10);
    LinearLayout actions = u.row();
    TextView copy = u.button("複製內容", false), note = u.button("編輯備註", false);
    actions.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
    np.setMargins(u.d(8), 0, 0, 0);
    actions.addView(note, np);
    body.addView(actions);
    copy.setOnClickListener(
        v -> {
          ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
              .setPrimaryClip(ClipData.newPlainText("通知", r.title + "\n" + r.text));
          toast("已複製通知內容");
        });
    note.setOnClickListener(v -> editNote(r));
    Intent launch = getPackageManager().getLaunchIntentForPackage(r.pkg);
    if (launch != null) {
      u.gap(body, 10);
      TextView open = u.button("開啟來源 App", false);
      body.addView(open);
      open.setOnClickListener(
          v -> {
            try {
              startActivity(launch);
            } catch (Exception e) {
              toast("無法開啟來源 App");
            }
          });
    }
    if (versions.length() > 1) {
      u.gap(body, 24);
      body.addView(u.text("更新歷程 · " + versions.length() + " 個版本", 16, u.ink, true));
      for (int i = 0; i < versions.length(); i++) {
        JSONObject x = versions.optJSONObject(i);
        u.gap(body, 16);
        body.addView(u.text(date(x.optLong("time"), "MM/dd HH:mm:ss"), 12, u.accent, true));
        TextView version =
            u.text(x.optString("title") + "\n" + x.optString("body"), 14, u.muted, false);
        version.setTextIsSelectable(true);
        body.addView(version);
      }
    }
    AlertDialog dialog = sheet("通知詳情", body);
    u.gap(body, 20);
    TextView delete = u.button("刪除此筆紀錄", false);
    body.addView(delete);
    delete.setOnClickListener(
        v ->
            new AlertDialog.Builder(this)
                .setTitle("刪除這筆紀錄？")
                .setMessage("會一併刪除更新歷程；不影響來源 App。此操作無法復原。")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                    "刪除",
                    (d, w) -> {
                      mutate(() -> Store.get(this).delete(r.id), "已刪除紀錄");
                      dialog.dismiss();
                    })
                .show());
  }

  private void editNote(Record r) {
    EditText input = edit("輸入備註");
    input.setText(r.note);
    new AlertDialog.Builder(this)
        .setTitle("通知備註")
        .setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "儲存",
            (d, w) -> {
              String text = input.getText().toString();
              if (text.length() > 100000) {
                toast("備註過長");
                return;
              }
              r.note = text;
              mutate(() -> Store.get(this).note(r.id, text), "備註已儲存");
            })
        .show();
  }

  private void buildSettings() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout body = u.column();
    body.setPadding(0, 0, 0, u.d(24));
    scroll.addView(body);
    content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    title(body, "YOUR NOTIFICATIONS, YOURS.", "照你的方式保存", "免費、無廣告，通知留在自己的手機。 ");
    LinearLayout inner = u.column();
    inner.setPadding(u.d(20), 0, u.d(20), 0);
    body.addView(inner);
    section(inner, "記錄管理");
    settingRow(
        inner,
        "通知存取權",
        hasAccess() ? "已授權 · 點選檢查記錄狀態" : "尚未開啟 · 開始記錄前需要授權",
        "bell",
        this::accessDialog);
    settingRow(
        inner,
        paused.equals("1") ? "繼續記錄通知" : "暫停記錄通知",
        paused.equals("1") ? "暫停期間的通知可能無法補回" : "保留既有紀錄，停止保存新的通知",
        "check",
        () -> {
          String value = paused.equals("1") ? "0" : "1";
          mutate(
              () -> Store.get(this).putSetting("paused", value),
              value.equals("1") ? "已暫停記錄" : "已恢復記錄");
          paused = value;
          build();
        });
    settingRow(inner, "選擇記錄來源", "排除不想保存的 App", "settings", this::manageApps);
    settingRow(
        inner,
        "保存期限",
        retention.equals("0") ? "持續保留 · 不自動刪除" : retention + " 天 · 收藏不受影響",
        "bell",
        this::chooseRetention);
    section(inner, "備份與資料");
    settingRow(inner, "建立備份", "匯出紀錄與歷程，可使用密碼加密", "backup", this::exportDialog);
    settingRow(inner, "還原備份", "預覽後合併匯入，略過重複紀錄", "backup", this::chooseImport);
    settingRow(
        inner,
        "儲存概況",
        format(counts[0]) + " 則通知 · " + databaseSize(),
        "settings",
        this::storageDialog);
    settingRow(
        inner,
        "清除未收藏紀錄",
        "保留收藏，刪除其餘通知與歷程",
        "settings",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("清除所有未收藏紀錄？")
                .setMessage("此操作無法復原。建議先建立備份。")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                    "清除", (d, w) -> mutate(() -> Store.get(this).clearUnstarred(), "已清除未收藏紀錄"))
                .show());
    section(inner, "關於 HuangNotify");
    settingRow(inner, "隱私與記錄範圍", "沒有帳號、追蹤器或網路權限", "check", this::privacyDialog);
    TextView about = u.text("HuangNotify 0.1.0\nMIT 開源授權 · 為每一則通知留一份紀錄", 12, u.muted, false);
    about.setGravity(Gravity.CENTER);
    about.setPadding(0, u.d(24), 0, 0);
    inner.addView(about);
  }

  private void section(LinearLayout parent, String title) {
    u.gap(parent, 18);
    TextView t = u.text(title, 13, u.accent, true);
    t.setPadding(u.d(4), 0, 0, u.d(10));
    parent.addView(t);
  }

  private void settingRow(
      LinearLayout parent, String title, String subtitle, String icon, Runnable action) {
    LinearLayout row = u.row();
    row.setPadding(u.d(16), u.d(16), u.d(12), u.d(16));
    u.ripple(row, u.surface, 18);
    row.addView(u.icon(icon, u.accent), new LinearLayout.LayoutParams(u.d(24), u.d(24)));
    LinearLayout text = u.column();
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
    tp.setMargins(u.d(14), 0, u.d(8), 0);
    row.addView(text, tp);
    text.addView(u.text(title, 16, u.ink, true));
    u.gap(text, 4);
    text.addView(u.text(subtitle, 12, u.muted, false));
    row.addView(u.text("›", 24, u.muted, false));
    parent.addView(row);
    u.gap(parent, 8);
    row.setFocusable(true);
    row.setOnClickListener(v -> action.run());
  }

  private void accessDialog() {
    String state =
        hasAccess() ? (CaptureService.connected ? "通知服務已連線。" : "已有權限，但服務尚未連線。 ") : "尚未授予通知存取權。";
    new AlertDialog.Builder(this)
        .setTitle("讓通知留下紀錄")
        .setMessage(
            state
                + "\n\n"
                + "HuangNotify 會在收到通知時保存文字。請在系統設定中啟用 HuangNotify。\n\n"
                + "安裝前已消失的通知無法找回；系統隱藏的敏感內容也無法讀取。\n\n"
                + "若開關被限制，請至系統的 App 資訊頁查看「允許受限制的設定」。")
        .setNegativeButton("關閉", null)
        .setNeutralButton(
            "重新連線",
            (d, w) -> {
              if (hasAccess()) {
                NotificationListenerService.requestRebind(
                    new ComponentName(this, CaptureService.class));
                toast("已請求重新連線");
              } else toast("請先開啟通知存取權");
            })
        .setPositiveButton(
            "開啟系統設定",
            (d, w) -> {
              try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
              } catch (Exception e) {
                toast("請手動開啟系統的通知存取權設定");
              }
            })
        .show();
  }

  private boolean hasAccess() {
    String enabled =
        Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
    if (enabled == null) return false;
    ComponentName target = new ComponentName(this, CaptureService.class);
    for (String item : enabled.split(":"))
      if (target.equals(ComponentName.unflattenFromString(item))) return true;
    return false;
  }

  private void chooseRetention() {
    String[] days = {"0", "7", "30", "90", "365"};
    String[] names = {"持續保留", "7 天", "30 天", "90 天", "365 天"};
    int checked = Arrays.asList(days).indexOf(retention);
    new AlertDialog.Builder(this)
        .setTitle("保存多久的通知？")
        .setSingleChoiceItems(
            names,
            checked,
            (d, which) -> {
              d.dismiss();
              Runnable apply =
                  () -> {
                    String selected = days[which];
                    retention = selected;
                    mutate(
                        () -> {
                          Store s = Store.get(this);
                          s.putSetting("retention", selected);
                          s.cleanup(Integer.parseInt(selected));
                        },
                        "保存期限已更新");
                    build();
                  };
              if (which == 0) apply.run();
              else
                new AlertDialog.Builder(this)
                    .setTitle("啟用定期清理？")
                    .setMessage("會立即刪除超過 " + days[which] + " 天且未收藏的紀錄。之後在 App 開啟或收到通知時清理。此操作無法復原。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("啟用", (dd, w) -> apply.run())
                    .show();
            })
        .show();
  }

  private void manageApps() {
    Store.IO.execute(
        () -> {
          try {
            List<String[]> sources = Store.get(this).apps();
            boolean[] checked = new boolean[sources.size()];
            String[] names = new String[sources.size()];
            for (int i = 0; i < sources.size(); i++) {
              String[] a = sources.get(i);
              names[i] = a[1] + "（" + a[2] + " 則）";
              checked[i] = !Store.get(this).excluded(a[0]);
            }
            runOnUiThread(
                () -> {
                  if (names.length == 0) {
                    toast("收到通知後，來源 App 會出現在這裡");
                    return;
                  }
                  new AlertDialog.Builder(this)
                      .setTitle("要記錄哪些 App？")
                      .setMultiChoiceItems(names, checked, (d, i, value) -> checked[i] = value)
                      .setNegativeButton("取消", null)
                      .setPositiveButton(
                          "儲存",
                          (d, w) ->
                              mutate(
                                  () -> {
                                    for (int i = 0; i < sources.size(); i++)
                                      Store.get(this)
                                          .putSetting(
                                              "exclude:" + sources.get(i)[0],
                                              checked[i] ? "0" : "1");
                                  },
                                  "記錄來源已更新"))
                      .show();
                });
          } catch (Exception e) {
            showError("無法讀取 App 清單");
          }
        });
  }

  private String databaseSize() {
    java.io.File db = getDatabasePath("notifications.db");
    long size =
        db.length()
            + new java.io.File(db.getPath() + "-wal").length()
            + new java.io.File(db.getPath() + "-shm").length();
    return android.text.format.Formatter.formatFileSize(this, size);
  }

  private void storageDialog() {
    StringBuilder b =
        new StringBuilder(
            "目前保存 "
                + format(counts[0])
                + " 則\n今日新增 "
                + format(counts[1])
                + " 則\n收藏 "
                + format(counts[2])
                + " 則\n資料庫大小 "
                + databaseSize()
                + "\n\n各 App 保存數量\n");
    for (String[] a : apps) b.append(a[1]).append("：").append(a[2]).append(" 則\n");
    b.append("\n統計只計入成功保存且尚未刪除的通知。同一筆通知的更新另存為版本。");
    new AlertDialog.Builder(this)
        .setTitle("儲存概況")
        .setMessage(b.toString())
        .setPositiveButton("完成", null)
        .show();
  }

  private void privacyDialog() {
    new AlertDialog.Builder(this)
        .setTitle("你的通知，由你保管")
        .setMessage(
            "• 所有紀錄保存在本機。\n"
                + "• App 沒有網路權限、廣告或分析追蹤。\n"
                + "• 不會代你清除通知或回覆訊息。\n"
                + "• 系統自動備份已停用，只有你主動匯出的檔案會離開 App。\n\n"
                + "第一版保存通知文字，不下載圖片或附件。來源 App 未提供的內容，以及系統遮蔽的敏感內容，無法還原。\n\n"
                + "首次連線會補存目前仍在通知列的通知。服務中斷期間已消失的通知可能漏記；重連時會核對仍存在的通知。\n\n"
                + "解除安裝或清除 App 資料會刪除紀錄，請先匯出備份。")
        .setPositiveButton("了解", null)
        .show();
  }

  private void exportDialog() {
    if (busy) {
      toast("正在處理備份，請稍候");
      return;
    }
    LinearLayout body = dialogBody();
    body.addView(u.text("備份包含通知文字、更新歷程、收藏、備註及保存設定。加密密碼遺失後無法還原。", 14, u.muted, false));
    u.gap(body, 12);
    EditText password = edit("設定備份密碼（至少 8 個字元）");
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    body.addView(password);
    u.gap(body, 8);
    EditText confirm = edit("再次輸入密碼");
    confirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    body.addView(confirm);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("建立備份")
            .setView(body)
            .setNegativeButton("取消", null)
            .setNeutralButton(
                "不加密",
                (d, w) ->
                    new AlertDialog.Builder(this)
                        .setTitle("匯出未加密備份？")
                        .setMessage("能取得檔案的人都可以閱讀通知內容。請妥善保管。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("匯出", (dd, ww) -> prepareExport(null))
                        .show())
            .setPositiveButton("加密匯出", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String a = password.getText().toString(), b = confirm.getText().toString();
                      if (a.length() < 8) {
                        password.setError("至少 8 個字元");
                        return;
                      }
                      if (!a.equals(b)) {
                        confirm.setError("兩次密碼不相同");
                        return;
                      }
                      char[] secret = a.toCharArray();
                      password.setText("");
                      confirm.setText("");
                      dialog.dismiss();
                      prepareExport(secret);
                    }));
    dialog.show();
  }

  private void prepareExport(char[] password) {
    busy = true;
    toast("正在建立備份…");
    Store.IO.execute(
        () -> {
          try {
            byte[] raw = Store.get(this).exportBackup().toString().getBytes(StandardCharsets.UTF_8);
            if (raw.length > BackupCrypto.MAX_BYTES)
              throw new IllegalArgumentException("備份超過 20 MB，請先整理紀錄");
            byte[] output = password == null ? raw : BackupCrypto.encrypt(raw, password);
            if (password != null) Arrays.fill(raw, (byte) 0);
            runOnUiThread(
                () -> {
                  if (isDestroyed()) {
                    Arrays.fill(output, (byte) 0);
                    return;
                  }
                  pendingExport = output;
                  pendingEncrypted = password != null;
                  Intent intent =
                      new Intent(Intent.ACTION_CREATE_DOCUMENT)
                          .addCategory(Intent.CATEGORY_OPENABLE)
                          .setType("application/octet-stream")
                          .putExtra(
                              Intent.EXTRA_TITLE,
                              "HuangNotify_"
                                  + date(System.currentTimeMillis(), "yyyyMMdd_HHmm")
                                  + (pendingEncrypted ? ".hnbk" : ".hnjson"));
                  try {
                    startActivityForResult(intent, 101);
                  } catch (Exception e) {
                    finishBackup();
                    toast("無法開啟檔案選擇器");
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  finishBackup();
                  toast("備份失敗：" + safeError(e));
                });
          } finally {
            if (password != null) Arrays.fill(password, '\0');
          }
        });
  }

  private void chooseImport() {
    if (busy) {
      toast("正在處理備份，請稍候");
      return;
    }
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*");
    try {
      startActivityForResult(intent, 102);
    } catch (Exception e) {
      toast("無法開啟檔案選擇器");
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) {
      if (request == 101) finishBackup();
      return;
    }
    Uri uri = data.getData();
    if (request == 101) {
      if (pendingExport == null) {
        finishBackup();
        toast("備份作業已中斷，請重新匯出");
        return;
      }
      byte[] bytes = pendingExport;
      pendingExport = null;
      Store.IO.execute(
          () -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new java.io.IOException("無法開啟目標檔案");
              out.write(bytes);
              out.flush();
              runOnUiThread(
                  () -> {
                    finishBackup();
                    toast("備份已儲存，共 " + bytes.length + " bytes");
                  });
            } catch (Exception e) {
              runOnUiThread(
                  () -> {
                    finishBackup();
                    toast("寫入失敗，請重新備份；目標檔可能不完整");
                  });
            } finally {
              Arrays.fill(bytes, (byte) 0);
            }
          });
    } else if (request == 102) {
      busy = true;
      Store.IO.execute(
          () -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              if (in == null) throw new java.io.IOException();
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              byte[] buffer = new byte[8192];
              int n;
              while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > BackupCrypto.MAX_BYTES + 53)
                  throw new IllegalArgumentException("備份檔超過 20 MB 上限");
                out.write(buffer, 0, n);
              }
              byte[] bytes = out.toByteArray();
              runOnUiThread(
                  () -> {
                    if (BackupCrypto.encrypted(bytes)) decryptDialog(bytes);
                    else previewImport(bytes);
                  });
            } catch (Exception e) {
              runOnUiThread(
                  () -> {
                    finishBackup();
                    toast("讀取失敗：" + safeError(e));
                  });
            }
          });
    }
  }

  private void decryptDialog(byte[] bytes) {
    if (isDestroyed()) return;
    EditText password = edit("輸入備份密碼");
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("解鎖備份")
            .setView(password)
            .setNegativeButton(
                "取消",
                (dd, w) -> {
                  Arrays.fill(bytes, (byte) 0);
                  finishBackup();
                })
            .setPositiveButton(
                "解鎖",
                (dd, w) -> {
                  char[] secret = password.getText().toString().toCharArray();
                  password.setText("");
                  Store.IO.execute(
                      () -> {
                        try {
                          byte[] raw = BackupCrypto.decrypt(bytes, secret);
                          runOnUiThread(() -> previewImport(raw));
                        } catch (Exception e) {
                          runOnUiThread(
                              () -> {
                                finishBackup();
                                toast("無法解鎖：密碼錯誤或備份檔已損壞");
                              });
                        } finally {
                          Arrays.fill(secret, '\0');
                          Arrays.fill(bytes, (byte) 0);
                        }
                      });
                })
            .create();
    d.setOnCancelListener(
        dd -> {
          Arrays.fill(bytes, (byte) 0);
          finishBackup();
        });
    d.show();
  }

  private void previewImport(byte[] bytes) {
    Store.IO.execute(
        () -> {
          try {
            JSONObject backup = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            BackupFormat.validate(backup);
            int count = backup.getJSONArray("records").length();
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  LinearLayout body = dialogBody();
                  body.addView(
                      u.text(
                          "備份日期："
                              + date(backup.optLong("created"), "yyyy/MM/dd HH:mm")
                              + "\n包含 "
                              + format(count)
                              + " 則通知\n\n匯入時保留手機現有內容，以識別碼略過重複紀錄，並補入缺少的更新版本。",
                          15,
                          u.ink,
                          false));
                  CheckBox settings = new CheckBox(this);
                  settings.setText("同時還原保存期限與排除 App 設定");
                  settings.setTextColor(u.ink);
                  body.addView(settings);
                  AlertDialog dialog =
                      new AlertDialog.Builder(this)
                          .setTitle("確認還原備份")
                          .setView(body)
                          .setNegativeButton("取消", (d, w) -> finishBackup())
                          .setPositiveButton(
                              "合併匯入",
                              (d, w) -> {
                                boolean restoreSettings = settings.isChecked();
                                Store.IO.execute(
                                    () -> {
                                      try {
                                        int[] result =
                                            Store.get(this).restore(backup, restoreSettings);
                                        runOnUiThread(
                                            () -> {
                                              finishBackup();
                                              refresh();
                                              new AlertDialog.Builder(this)
                                                  .setTitle("還原完成")
                                                  .setMessage(
                                                      "新增 "
                                                          + result[0]
                                                          + " 則\n略過 "
                                                          + result[1]
                                                          + " 則重複紀錄\n補入 "
                                                          + result[2]
                                                          + " 個更新版本")
                                                  .setPositiveButton("完成", null)
                                                  .show();
                                            });
                                      } catch (Exception e) {
                                        runOnUiThread(
                                            () -> {
                                              finishBackup();
                                              toast("匯入失敗，原有資料未變更");
                                            });
                                      }
                                    });
                              })
                          .create();
                  dialog.setOnCancelListener(d -> finishBackup());
                  dialog.show();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  finishBackup();
                  toast("無法還原：" + safeError(e));
                });
          } finally {
            Arrays.fill(bytes, (byte) 0);
          }
        });
  }

  private void finishBackup() {
    busy = false;
    if (pendingExport != null) {
      Arrays.fill(pendingExport, (byte) 0);
      pendingExport = null;
    }
  }

  private String safeError(Exception e) {
    return e instanceof IllegalArgumentException ? e.getMessage() : "檔案格式不正確或無法存取";
  }

  private LinearLayout dialogBody() {
    LinearLayout b = u.column();
    b.setPadding(u.d(24), u.d(16), u.d(24), u.d(20));
    b.setBackgroundColor(u.surface);
    return b;
  }

  private AlertDialog sheet(String title, LinearLayout body) {
    ScrollView scroll = new ScrollView(this);
    scroll.addView(body);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("完成", null)
            .create();
    d.show();
    if (d.getWindow() != null) {
      d.getWindow().setBackgroundDrawable(u.shape(u.surface, 24));
      d.getWindow().setLayout(-1, (int) (getResources().getDisplayMetrics().heightPixels * .85));
      d.getWindow().setGravity(Gravity.BOTTOM);
    }
    return d;
  }

  private EditText edit(String hint) {
    EditText e = new EditText(this);
    e.setTextColor(u.ink);
    e.setHintTextColor(u.muted);
    e.setHint(hint);
    e.setTextSize(15);
    e.setPadding(u.d(16), u.d(12), u.d(16), u.d(12));
    e.setBackgroundTintList(ColorStateList.valueOf(u.accent));
    return e;
  }

  private interface Work {
    void run() throws Exception;
  }

  private void mutate(Work work, String message) {
    Store.IO.execute(
        () -> {
          try {
            work.run();
            runOnUiThread(
                () -> {
                  refresh();
                  if (message != null) toast(message);
                });
          } catch (Exception e) {
            showError("儲存失敗，請檢查手機剩餘空間");
          }
        });
  }

  private void showError(String message) {
    runOnUiThread(() -> toast(message));
  }

  private void toast(String message) {
    if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  private void hideKeyboard() {
    View focus = getCurrentFocus();
    if (focus != null)
      ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(focus.getWindowToken(), 0);
  }

  private static long parseLong(String s) {
    try {
      return Long.parseLong(s);
    } catch (Exception e) {
      return 0;
    }
  }

  private static String date(long millis, String pattern) {
    return new SimpleDateFormat(pattern, Locale.TAIWAN).format(new Date(millis));
  }

  private static String format(long number) {
    return java.text.NumberFormat.getIntegerInstance(Locale.TAIWAN).format(number);
  }
}
