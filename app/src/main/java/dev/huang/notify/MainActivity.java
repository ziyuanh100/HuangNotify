package dev.huang.notify;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
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
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native, dependency-light Android UI. Database and encryption never run on the UI thread. */
public final class MainActivity extends Activity {
  private static final int PAGE_SIZE = 80;
  private static final int ANALYSIS_REQUEST = 105;
  private static final int BACKUP_FOLDER = 103, MOVE_BACKUP_FOLDER = 106;
  private static final int[] BACKUP_RETENTION = {3, 7, 14, 30};
  private static final String[] BACKUP_RETENTION_LABELS = {"3 份", "7 份", "14 份", "30 份"};
  private Ui u;
  private final List<RecordSwitch> recordSwitches = new ArrayList<>();
  private final List<TextView> recordStates = new ArrayList<>();
  private TextView backupStatus, upgradeStatus, accessSummary, retentionSummary, storageSummary;
  private TextView lastBackup, autoBackupState, backupFolder, backupRetention, backupNow;
  private View cancelBackup;
  private LinearLayout autoBackupActions;
  private ProgressBar autoBackupProgress;
  private boolean configuringBackup;
  private ScrollView settingsScroll;
  private int settingsScrollY;
  private boolean recordingLoaded, recordingSaving;
  PermissionGuide permissionGuide;
  UpdateFlow updateFlow;
  SourcePicker sourcePicker;
  private boolean loadingSources, loadingDetail;
  private FrameLayout root;
  private GlassNavigation nav;
  private LinearLayout content, overview, emptyCard, filterSummary;
  private View statusDot, clearSearch;
  private EditText searchInput;
  private TextView statusTitle, statusBody, todayCount, totalCount, starCount;
  private TextView appFilter, timeFilter, imageFilter, filterRange, sectionTitle, resultCount;
  private TextView emptyTitle, emptyBody, emptyAction, loadMore;
  private ListView list;
  private RecordTimeline adapter;
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
  private boolean onlyToday, refreshing, resumed, onlyImages, searchPending, keyboardVisible;
  private String rangeStart="",rangeEnd="";
  private Bundle analysisReturn, filtersBeforeAnalysis;
  private OnBackInvokedCallback analysisBack;
  private int limit = PAGE_SIZE, generation, displayedGeneration = -1;
  BackupScreen backupScreen;
  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (resumed) {
            refresh();
            handler.postDelayed(this, 2500);
          }
        }
      };
  private final Runnable searchRefresh = () -> {
    searchPending = false;
    reloadRecords();
  };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    u = new Ui(this);
    permissionGuide = new PermissionGuide(this);
    backupScreen=new BackupScreen(this,u,this::refresh);
    AutoBackup.IO.execute(()->BackupFiles.cleanStale(getApplicationContext()));
    updateFlow=new UpdateFlow(this);
    if (state != null) {
      page = state.getString("page", "records");
      restoreRecordFilters(state);
      analysisReturn = state.getBundle("analysis_return");
      filtersBeforeAnalysis = state.getBundle("filters_before_analysis");
      settingsScrollY = state.getInt("settings_scroll");
    }
    getWindow().setStatusBarColor(u.bg);
    getWindow().setNavigationBarColor(u.bg);
    if (!u.dark)
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    root = new FrameLayout(this) {
      @Override public void onDescendantInvalidated(View child, View target) {
        super.onDescendantInvalidated(child, target);
        if (child == content && nav != null) nav.invalidateBackdrop();
      }
    };
    root.setBackgroundColor(u.bg);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets s =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            updateKeyboardVisibility(insets.isVisible(WindowInsets.Type.ime()));
            return WindowInsets.CONSUMED;
          }
          v.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          updateKeyboardVisibility(insets.getSystemWindowInsetBottom() - insets.getStableInsetBottom() > u.d(48));
          return insets.consumeSystemWindowInsets();
        });
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    setContentView(root);
    if (getIntent().getBooleanExtra("open_updates",false)) {
      page="settings";
      endAnalysisDrill();
    }
    updateBackAction();
    UpgradeManager.reconcile(this);
    UpgradeManager.recover(this);
    AutoBackup.schedule(this);
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

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent); setIntent(intent);
    if (intent.getBooleanExtra("open_updates", false)) {
      if (page.equals("settings")) refresh();
      else openPage("settings");
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    handler.removeCallbacks(poll);
    handler.post(poll);
    handler.post(() -> { if (resumed) permissionGuide.resume(); });
    if (settingsScroll != null && settingsScroll.isLaidOut()) build();
    AutoBackup.schedule(this);
    UpgradeManager.reconcile(this);
    UpdateChecker.schedule(this);
    updateFlow.resume();
    backupScreen.resume();
    if (page.equals("backup")) refreshBackupPage();
    Diagnostics.resume(this);
  }

  @Override
  protected void onPause() {
    resumed = false;
    updateFlow.pause();
    backupScreen.pause();
    handler.removeCallbacks(poll);
    handler.removeCallbacks(backupTick);
    super.onPause();
  }

  @Override protected void onStop() { updateFlow.leave(); super.onStop(); }

  @Override
  protected void onDestroy() {
    if (Build.VERSION.SDK_INT >= 33 && analysisBack != null) {
      getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(analysisBack);
    }
    updateFlow.destroy();
    handler.removeCallbacksAndMessages(null);
    AppDialog.dismissAll(this);
    backupScreen.destroy();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("page", page);
    saveRecordFilters(b);
    b.putBundle("analysis_return", analysisReturn);
    b.putBundle("filters_before_analysis", filtersBeforeAnalysis);
    b.putInt("settings_scroll", settingsScroll == null ? settingsScrollY : settingsScroll.getScrollY());
  }

  private void saveRecordFilters(Bundle b) {
    b.putString("query", query);
    b.putString("pkg", filterPkg);
    b.putString("filter", filterName);
    b.putBoolean("today", onlyToday);
    b.putBoolean("images", onlyImages);
    b.putString("range_start", rangeStart);
    b.putString("range_end", rangeEnd);
  }

  private void restoreRecordFilters(Bundle b) {
    query = b.getString("query", "");
    filterPkg = b.getString("pkg");
    filterName = b.getString("filter", "全部 App");
    onlyToday = b.getBoolean("today");
    onlyImages = b.getBoolean("images");
    rangeStart = b.getString("range_start", "");
    rangeEnd = b.getString("range_end", "");
  }

  private void updateBackAction() {
    if (Build.VERSION.SDK_INT < 33) return;
    boolean handlesBack = analysisReturn != null || page.equals("backup");
    if (handlesBack && analysisBack == null) {
      analysisBack = this::onBackPressed;
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
          OnBackInvokedDispatcher.PRIORITY_DEFAULT, analysisBack);
    } else if (!handlesBack && analysisBack != null) {
      getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(analysisBack);
      analysisBack = null;
    }
  }

  @Override public void onBackPressed() {
    if (page.equals("backup")) openPage("settings");
    else if (analysisReturn != null) launchAnalysis();
    else super.onBackPressed();
  }

  private void endAnalysisDrill() {
    if (filtersBeforeAnalysis != null) restoreRecordFilters(filtersBeforeAnalysis);
    analysisReturn = filtersBeforeAnalysis = null;
    updateBackAction();
  }

  private void launchAnalysis() {
    hideKeyboard();
    Intent intent = new Intent(this, AnalysisActivity.class);
    if (analysisReturn != null) {
      intent.putExtra(AnalysisActivity.DASHBOARD_STATE, analysisReturn);
      endAnalysisDrill();
      rebuildPage();
    }
    startActivityForResult(intent, ANALYSIS_REQUEST);
  }

  private void openPage(String nextPage) {
    if (page.equals(nextPage)) return;
    hideKeyboard();
    endAnalysisDrill();
    page = nextPage;
    updateBackAction();
    rebuildPage();
  }

  private void rebuildPage() {
    handler.removeCallbacks(searchRefresh);
    searchPending = false;
    limit = PAGE_SIZE;
    generation++;
    build();
    refresh();
  }

  private int pageInset() {
    return u.d(Math.max(16, (getResources().getConfiguration().screenWidthDp - 680) / 2));
  }

  private void build() {
    if (settingsScroll != null) settingsScrollY = settingsScroll.getScrollY();
    settingsScroll = null;
    accessSummary = retentionSummary = storageSummary = backupStatus = upgradeStatus = null;
    lastBackup = null;
    handler.removeCallbacks(backupTick);
    statusTitle = null;
    overview = null;
    list = null;
    recordSwitches.clear();
    recordStates.clear();

    if (content != null) root.removeView(content);
    content = u.column();
    content.setFocusableInTouchMode(true);
    root.addView(content, 0, new FrameLayout.LayoutParams(-1, -1));
    if (page.equals("backup")) buildBackupPage();
    else if (page.equals("settings")) buildSettings();
    else buildRecords();
    content.requestFocus();

    if (nav == null) {
      nav = new GlassNavigation(u, page, this::openPage);
      FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2,
          Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
      int side = u.d(Math.max(18, (getResources().getConfiguration().screenWidthDp - 480) / 2));
      params.setMargins(side, 0, side, u.d(12));
      root.addView(nav, params);
      nav.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
          updateContentInsets());
    }
    nav.setPageContent(content);
    nav.selectPage(page.equals("backup") ? "settings" : page);
    updateKeyboardVisibility(keyboardVisible);
    root.requestApplyInsets();
    updateHeader();
  }

  private void updateKeyboardVisibility(boolean visible) {
    keyboardVisible = visible;
    if (nav != null) nav.setVisibility(visible || analysisReturn != null || page.equals("backup") ? View.GONE : View.VISIBLE);
    updateContentInsets();
    updateOverviewVisibility();
  }

  private void updateContentInsets() {
    View scroll = settingsScroll != null ? settingsScroll : list;
    if (scroll == null || nav == null) return;
    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) nav.getLayoutParams();
    int bottom = nav.getVisibility() == View.VISIBLE ? nav.getHeight() + params.bottomMargin + u.d(8) : 0;
    if (scroll.getPaddingBottom() != bottom) {
      scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(), scroll.getPaddingRight(), bottom);
    }
  }

  private void pageTitle(LinearLayout parent, String title, String icon) {
    LinearLayout bar = u.row();
    bar.setPadding(pageInset(), u.d(10), pageInset(), u.d(12));
    FrameLayout mark = u.iconTile(icon, u.lime, u.hero, 15);
    bar.addView(mark, new LinearLayout.LayoutParams(u.d(44), u.d(44)));
    LinearLayout text = u.column();
    TextView brand = u.text("HuangNotify", 12, u.muted, true);
    brand.setLetterSpacing(.06f);
    text.addView(brand);
    TextView heading = u.text(title, 26, u.ink, true);
    u.heading(heading);
    text.addView(heading);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    params.setMarginStart(u.d(12));
    bar.addView(text, params);
    parent.addView(bar);
  }

  private void buildRecords() {
    boolean favorites = page.equals("favorites");
    boolean analysisDetail = analysisReturn != null;
    LinearLayout chrome = u.column();
    pageTitle(chrome, analysisDetail ? "分析紀錄" : favorites ? "收藏通知" : "通知紀錄",
        favorites ? "star" : "bell");
    if (analysisDetail) buildAnalysisScope(chrome);
    buildSearch(chrome);

    list = new ListView(this);
    list.setDivider(null);
    list.setItemsCanFocus(true);
    list.setClipToPadding(false);
    list.setSelector(new ColorDrawable(Color.TRANSPARENT));
    list.setCacheColorHint(Color.TRANSPARENT);
    list.setVerticalScrollBarEnabled(false);

    // Give the list room in landscape, split screen, and other short windows.
    float fontScale = getResources().getConfiguration().fontScale;
    int height = getResources().getConfiguration().screenHeightDp;
    if (height > 0 && height < (fontScale >= 1.5f ? 560 : 420)) {
      list.addHeaderView(chrome, null, false);
    } else {
      content.addView(chrome);
    }

    LinearLayout header = u.column();
    header.setPadding(pageInset(), u.d(10), pageInset(), u.d(2));
    if (!favorites && !analysisDetail) {
      overview = u.column();
      overview.addView(recordingOverview());
      u.gap(overview, 14);
      header.addView(overview);
    }
    buildResultsHeader(header, favorites);
    list.addHeaderView(header, null, false);
    LinearLayout footer = u.column();
    footer.setPadding(pageInset(), u.d(8), pageInset(), u.d(16));
    loadMore = u.button("載入更多", false);
    loadMore.setVisibility(View.GONE);
    loadMore.setOnClickListener(v -> {
      limit += PAGE_SIZE;
      generation++;
      refresh();
    });
    footer.addView(loadMore);
    list.addFooterView(footer, null, false);

    rows.clear();
    displayedGeneration = -1;
    adapter = new RecordTimeline(u, rows, pageInset());
    list.setAdapter(adapter);
    list.setOnItemClickListener((parent, v, position, id) -> {
      int index = position - list.getHeaderViewsCount();
      if (displayedGeneration == generation && index >= 0 && index < rows.size()) {
        detail(rows.get(index));
      }
    });
    content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    updateFilterControls();
  }

  private void buildAnalysisScope(LinearLayout parent) {
    boolean stack = getResources().getConfiguration().fontScale >= 1.5f
        || getResources().getConfiguration().screenWidthDp < 330;
    LinearLayout summary = stack ? u.column() : u.row();
    summary.setPadding(pageInset(), 0, pageInset(), u.d(8));
    LinearLayout labels = u.column();
    labels.addView(u.text(filterName, 15, u.ink, true));
    String dates = rangeStart.equals(rangeEnd) ? rangeStart : rangeStart + " ～ " + rangeEnd;
    labels.addView(u.text(dates, 13, u.muted, false));
    summary.addView(labels, stack ? new LinearLayout.LayoutParams(-1, -2)
        : new LinearLayout.LayoutParams(0, -2, 1));
    if (stack) u.gap(summary, 6);
    TextView back = u.button("返回分析", false);
    back.setContentDescription("返回通知分析");
    back.setOnClickListener(v -> launchAnalysis());
    summary.addView(back);
    parent.addView(summary);
  }

  private void buildSearch(LinearLayout parent) {
    LinearLayout search = u.row();
    search.setPaddingRelative(u.d(14), 0, u.d(4), 0);
    search.setBackground(u.outline(u.surface, 18));
    search.addView(u.icon("search", u.muted), new LinearLayout.LayoutParams(u.d(21), u.d(21)));

    searchInput = new EditText(this);
    searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
    searchInput.setSingleLine();
    searchInput.setTextSize(15);
    searchInput.setTextColor(u.ink);
    searchInput.setHintTextColor(u.muted);
    searchInput.setHint(analysisReturn == null ? "搜尋通知、App 或備註" : "搜尋這些通知");
    searchInput.setBackgroundColor(Color.TRANSPARENT);
    searchInput.setPaddingRelative(u.d(10), u.d(10), 0, u.d(10));
    searchInput.setMinHeight(u.d(52));
    searchInput.setText(query);
    searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    search.addView(searchInput, new LinearLayout.LayoutParams(0, -2, 1));
    clearSearch = u.iconButton("close", "清除搜尋", u.muted, () -> searchInput.setText(""));
    search.addView(clearSearch, new LinearLayout.LayoutParams(u.d(48), u.d(48)));
    LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
    searchParams.setMargins(pageInset(), 0, pageInset(), 0);
    parent.addView(search, searchParams);
    searchInput.setOnEditorActionListener((v, id, event) -> {
      handler.removeCallbacks(searchRefresh);
      searchRefresh.run();
      hideKeyboard();
      return true;
    });
    searchInput.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        query = s.toString();
        generation++;
        searchPending = true;
        updateFilterControls();
        resultCount.setText("搜尋中…");
        handler.removeCallbacks(searchRefresh);
        handler.postDelayed(searchRefresh, 250);
      }
      public void afterTextChanged(Editable e) {}
    });

    if (analysisReturn != null) {
      appFilter = timeFilter = imageFilter = filterRange = null;
      filterSummary = null;
      return;
    }
    HorizontalScrollView filterStrip = new HorizontalScrollView(this);
    filterStrip.setHorizontalScrollBarEnabled(false);
    filterStrip.setPadding(pageInset(), u.d(7), pageInset(), u.d(2));
    filterStrip.setClipToPadding(false);
    LinearLayout chips = u.row();
    filterStrip.addView(chips);
    appFilter = filterChip(chips, this::chooseApp);
    timeFilter = filterChip(chips, this::dateFilterDialog);
    imageFilter = filterChip(chips, () -> {
      onlyImages = !onlyImages;
      filtersChanged();
    });
    parent.addView(filterStrip);

    filterSummary = u.row();
    filterSummary.setPadding(pageInset(), 0, pageInset(), 0);
    filterRange = u.text("", 12, u.muted, false);
    filterSummary.addView(filterRange, new LinearLayout.LayoutParams(0, -2, 1));
    TextView clear = u.button("清除篩選", false);
    clear.setTextSize(13);
    clear.setMinHeight(u.d(48));
    u.ripple(clear, Color.TRANSPARENT, 16);
    clear.setOnClickListener(v -> resetFilters());
    filterSummary.addView(clear);
    parent.addView(filterSummary);
  }

  private TextView filterChip(LinearLayout parent, Runnable action) {
    TextView chip = u.button("", false);
    chip.setTextSize(13);
    chip.setMinHeight(u.d(48));
    chip.setPadding(u.d(12), u.d(8), u.d(12), u.d(8));
    chip.setMaxWidth(u.d(190));
    chip.setMaxLines(2);
    chip.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
    if (parent.getChildCount() > 0) params.setMarginStart(u.d(6));
    parent.addView(chip, params);
    chip.setOnClickListener(v -> {
      hideKeyboard();
      action.run();
    });
    return chip;
  }

  private void buildResultsHeader(LinearLayout header, boolean favorites) {
    if (!favorites && analysisReturn == null) {
      TextView analysis = u.button("通知分析", false);
      analysis.setOnClickListener(v -> launchAnalysis());
      header.addView(analysis);
    }
    LinearLayout section = u.row();
    section.setPadding(u.d(2), u.d(6), u.d(2), u.d(5));
    sectionTitle = u.text("", 19, u.ink, true);
    u.heading(sectionTitle);
    section.addView(sectionTitle, new LinearLayout.LayoutParams(0, -2, 1));
    resultCount = u.text("讀取中…", 12, u.muted, true);
    resultCount.setPadding(u.d(10), u.d(4), u.d(10), u.d(4));
    resultCount.setBackground(u.shape(u.pale, 12));
    section.addView(resultCount);
    header.addView(section);

    emptyCard = u.column();
    emptyCard.setGravity(Gravity.CENTER_HORIZONTAL);
    emptyCard.setPadding(u.d(16), u.d(20), u.d(16), u.d(20));
    emptyCard.setBackground(u.outline(u.surface, 20));
    emptyCard.addView(u.iconTile(favorites ? "star" : "bell", u.accent, u.pale, 18),
        new LinearLayout.LayoutParams(u.d(44), u.d(44)));
    u.gap(emptyCard, 8);
    emptyTitle = u.text("正在讀取通知", 17, u.ink, true);
    emptyTitle.setGravity(Gravity.CENTER);
    emptyCard.addView(emptyTitle);
    emptyBody = u.text("", 14, u.muted, false);
    emptyBody.setGravity(Gravity.CENTER);
    emptyBody.setPadding(0, u.d(4), 0, u.d(8));
    emptyCard.addView(emptyBody);
    emptyAction = u.button("設定通知存取權", true);
    emptyAction.setVisibility(View.GONE);
    emptyCard.addView(emptyAction);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = u.d(8);
    header.addView(emptyCard, params);
  }

  private LinearLayout recordingOverview() {
    LinearLayout card = u.column();
    card.setPadding(u.d(14), u.d(12), u.d(14), u.d(15));
    card.setBackground(u.shape(u.hero, 24));

    LinearLayout status = u.row();
    LinearLayout statusText = u.column();
    LinearLayout caption = u.row();
    statusDot = new View(this);
    statusDot.setBackground(u.shape(u.heroMuted, 8));
    LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(u.d(8), u.d(8));
    dot.setMarginEnd(u.d(7));
    caption.addView(statusDot, dot);
    caption.addView(u.text("通知記錄", 12, u.heroMuted, true));
    statusText.addView(caption);
    u.gap(statusText, 3);
    statusTitle = u.text("讀取記錄狀態…", 20, u.heroInk, true);
    statusText.addView(statusTitle);
    statusBody = u.text("", 12, u.heroMuted, false);
    statusText.addView(statusBody);
    u.ripple(statusText, u.hero, 10);
    statusText.setFocusable(true);
    statusText.setOnClickListener(v -> accessDialog());
    status.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
    status.addView(recordSwitch(true));
    card.addView(status);
    u.gap(card, 11);
    View line = new View(this);
    line.setBackgroundColor(u.heroLine);
    card.addView(line, new LinearLayout.LayoutParams(-1, u.d(1)));
    u.gap(card, 10);
    LinearLayout stats = u.row();
    stats.setGravity(Gravity.TOP);
    todayCount = addStat(stats, "今日新增", counts[1], u.lime);
    totalCount = addStat(stats, "目前保存", counts[0], u.heroInk);
    starCount = addStat(stats, "已收藏", counts[2], u.heroInk);
    card.addView(stats);
    return card;
  }

  private TextView addStat(LinearLayout parent, String label, long value, int color) {
    LinearLayout stat = u.column();
    TextView number = u.text(format(value), 24, color, true);
    stat.addView(number);
    stat.addView(u.text(label, 12, u.heroMuted, false));
    parent.addView(stat, new LinearLayout.LayoutParams(0, -2, 1));
    return number;
  }

  private boolean hasFilters() {
    return !query.trim().isEmpty() || hasFilterOptions();
  }

  private boolean hasFilterOptions() {
    return filterPkg != null || onlyToday || onlyImages || !rangeStart.isEmpty();
  }

  private void updateOverviewVisibility() {
    if (overview != null) overview.setVisibility(hasFilters() || keyboardVisible ? View.GONE : View.VISIBLE);
  }

  private void updateFilterControls() {
    if (list == null) return;
    clearSearch.setVisibility(query.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    if (analysisReturn != null) {
      sectionTitle.setText(query.trim().isEmpty() ? "通知清單" : "搜尋結果");
      return;
    }
    updateChip(appFilter, filterName + " ▾", filterPkg != null);
    updateChip(timeFilter, onlyToday ? "今天 ▾" : rangeStart.isEmpty() ? "所有日期 ▾" : "自訂日期 ▾",
        onlyToday || !rangeStart.isEmpty());
    updateChip(imageFilter, onlyImages ? "有圖片 ✓" : "只看有圖片", onlyImages);
    String title = page.equals("favorites") ? "已收藏通知" : "所有通知";
    if (hasFilterOptions()) title = "篩選結果";
    else if (!query.trim().isEmpty()) title = "搜尋結果";
    sectionTitle.setText(title);
    filterSummary.setVisibility(hasFilterOptions() ? View.VISIBLE : View.GONE);
    filterRange.setText(rangeStart.isEmpty() ? "已套用篩選" : rangeStart + " ～ " + rangeEnd);
    updateOverviewVisibility();
  }

  private void updateChip(TextView chip, String text, boolean selected) {
    chip.setText(text);
    chip.setSelected(selected);
    chip.setTextColor(selected ? u.accent : u.muted);
    u.ripple(chip, selected ? u.pale : u.surface, 16);
  }

  private void resetFilters() {
    onlyToday = onlyImages = false;
    rangeStart = rangeEnd = "";
    filterPkg = null;
    filterName = "全部 App";
    searchInput.setText("");
    hideKeyboard();
    filtersChanged();
  }

  private void reloadRecords() {
    limit = PAGE_SIZE;
    if (list != null) {
      list.smoothScrollToPositionFromTop(0, 0, 0);
      resultCount.setText("讀取中…");
      if (rows.isEmpty()) showEmpty("正在讀取通知", "", null, null);
    }
    refresh();
  }

  private void filtersChanged() {
    handler.removeCallbacks(searchRefresh);
    searchPending = false;
    generation++;
    updateFilterControls();
    reloadRecords();
  }

  private String recordingState() {
    if (!recordingLoaded) return "讀取記錄設定…";
    if (paused.equals("1")) return "已暫停 · 原有紀錄保留";
    if (!hasAccess()) return "尚未授權 · 開啟通知存取權後才能記錄";
    if (!Store.error.isEmpty()) return "需要處理 · " + Store.error;
    return CaptureService.connected ? "正在記錄 · 開關啟用" : "服務未連線 · 點選通知存取權重新連線";
  }
  private RecordSwitch recordSwitch(boolean onHero) {
    RecordSwitch control=new RecordSwitch(this, onHero);
    control.setContentDescription("記錄通知"); control.setChecked(!paused.equals("1"));
    control.setEnabled(recordingLoaded && !recordingSaving);
    control.setOnCheckedChangeListener((button,checked) -> changeRecording(checked));
    recordSwitches.add(control); return control;
  }
  private void updateRecordingControls() {
    for (RecordSwitch control:recordSwitches) {
      control.setOnCheckedChangeListener(null); control.setChecked(!paused.equals("1"));
      control.setEnabled(recordingLoaded && !recordingSaving);
      if (android.os.Build.VERSION.SDK_INT>=30) control.setStateDescription(recordingSaving ? "正在儲存" : recordingState());
      control.setOnCheckedChangeListener((button,checked) -> changeRecording(checked));
    }
    for (TextView state:recordStates) state.setText(recordingSaving?"正在儲存設定…":recordingState());
    if (backupStatus!=null) backupStatus.setText(backupSummary(BackupReceipt.latest(this)));
    if (upgradeStatus!=null) upgradeStatus.setText(UpgradeManager.status(this));
  }
  private void changeRecording(boolean enabled) {
    if (!recordingLoaded || recordingSaving) { updateRecordingControls(); return; }
    recordingSaving=true; updateRecordingControls();
    Store.IO.execute(() -> {
      boolean success;
      try { Store.get(this).putSetting("paused",enabled?"0":"1"); success=true; } catch(Exception e) { success=false; }
      final boolean saved=success;
      runOnUiThread(() -> {
        recordingSaving=false;
        if (saved) paused=enabled?"0":"1";
        if (isDestroyed()) return;
        updateHeader(); toast(saved?(enabled?"已啟用記錄":"已暫停記錄，原有紀錄保留"):"設定未儲存，請重試");
        if (saved && enabled) {
          if (!hasAccess()) permissionGuide.restart();
          else if (!CaptureService.connected) NotificationListenerService.requestRebind(new ComponentName(this,CaptureService.class));
        }
      });
    });
  }
  private void updateHeader() {
    updateRecordingControls();
    boolean access = hasAccess();
    if (accessSummary != null) {
      accessSummary.setText(access ? "已授權 · 檢查通知服務連線" : "尚未授權 · 開啟後才能記錄");
      retentionSummary.setText(retention.equals("0") ? "持續保留" : retention + " 天 · 收藏不受影響");
    }
    if (statusTitle == null) return;

    String title, body;
    int color = u.heroMuted;
    if (recordingSaving) {
      title = "正在儲存設定…";
      body = "";
    } else if (!recordingLoaded) {
      title = "讀取記錄狀態…";
      body = "";
    } else if (paused.equals("1")) {
      title = "記錄已暫停";
      body = "已保存的通知可繼續查看";
    } else if (!access) {
      title = "等待通知存取權";
      body = "點此設定，開始保存通知";
      color = u.warning;
    } else if (!Store.error.isEmpty()) {
      title = "記錄需要處理";
      body = Store.error;
      color = u.warning;
    } else if (CaptureService.connected) {
      title = "正在記錄";
      body = lastSaved.equals("0") ? "準備好接收下一則通知"
          : "最近保存 " + date(parseLong(lastSaved), "MM/dd HH:mm");
      color = u.lime;
    } else {
      title = "等待服務連線";
      body = "點此檢查權限與重新連線";
      color = u.warning;
    }
    statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
    statusTitle.setText(title);
    statusBody.setText(body);
    statusBody.setVisibility(body.isEmpty() ? View.GONE : View.VISIBLE);
    todayCount.setText(format(counts[1]));
    totalCount.setText(format(counts[0]));
    starCount.setText(format(counts[2]));
  }

  void dateFilterDialog() {
    LinearLayout body = dialogBody();
    body.addView(u.text("依首次保存日期篩選，包含開始與結束日期。", 14, u.muted, false));
    u.gap(body, 10);

    String today = LocalDate.now().toString();
    EditText start = u.field("開始日期 YYYY-MM-DD"), end = u.field("結束日期 YYYY-MM-DD");
    start.setText(rangeStart.isEmpty() ? today : rangeStart);
    end.setText(rangeEnd.isEmpty() ? today : rangeEnd);
    start.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
    end.setInputType(start.getInputType());
    LinearLayout dates = u.column();
    dates.setPadding(0, u.d(14), 0, 0);
    dates.addView(u.text("開始日期", 13, u.muted, false));
    dates.addView(start);
    u.gap(dates, 8);
    dates.addView(u.text("結束日期", 13, u.muted, false));
    dates.addView(end);

    int[] selected = {onlyToday ? 1 : rangeStart.isEmpty() ? 0 : 2};
    String[] labels = {"所有日期", "今天", "自訂日期"};
    AppDialog.Toggle[] options = new AppDialog.Toggle[labels.length];
    for (int i = 0; i < options.length; i++) {
      int index = i;
      AppDialog.Toggle option = new AppDialog.Toggle(this, true);
      options[i] = option;
      option.setText(labels[i]);
      option.setChecked(selected[0] == i);
      if (i > 0) u.gap(body, 6);
      body.addView(option);
      option.setOnClickListener(v -> {
        selected[0] = index;
        for (int j = 0; j < options.length; j++) options[j].setChecked(j == index);
        dates.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
      });
    }
    dates.setVisibility(selected[0] == 2 ? View.VISIBLE : View.GONE);
    body.addView(dates);

    AppDialog dialog = new AppDialog.Builder(this).setTitle("日期範圍").setView(body)
        .setNegativeButton("取消", null).setPositiveButton("套用", null).create();
    dialog.setOnShowListener(d -> dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      String first = start.getText().toString().trim(), last = end.getText().toString().trim();
      boolean custom = selected[0] == 2;
      if (custom) {
        try {
          RecordFilters.bounds(first, last, ZoneId.systemDefault());
        } catch (java.time.DateTimeException e) {
          end.setError("日期不存在，請重新輸入");
          return;
        } catch (IllegalArgumentException e) {
          end.setError(e.getMessage());
          return;
        }
      }
      onlyToday = selected[0] == 1;
      rangeStart = custom ? first : "";
      rangeEnd = custom ? last : "";
      dialog.dismiss();
      filtersChanged();
    }));
    dialog.show();
  }

  private void refresh() {
    if (isFinishing() || isDestroyed()) return;
    updateHeader();
    if (refreshing || searchPending) return;

    refreshing = true;
    int gen = generation;
    String q = query, pkg = filterPkg;
    boolean settings = page.equals("settings") || page.equals("backup");
    boolean favorites = page.equals("favorites"), images = onlyImages;
    ZoneId zone = ZoneId.systemDefault();
    String today = LocalDate.now(zone).toString();
    long[] bounds = RecordFilters.bounds(onlyToday ? today : rangeStart,
        onlyToday ? today : rangeEnd, zone);
    int amount = limit;
    Store.IO.execute(() -> {
      try {
        Store store = Store.get(this);
        long[] numbers = store.counts();
        List<String[]> sources = store.apps();
        List<Record> records = settings ? Collections.emptyList()
            : store.listFiltered(q, pkg, favorites, bounds[0], bounds[1], images, amount + 1);
        long exactCount = records.size() > amount && !favorites && !images
            && q.trim().isEmpty() && bounds[1] != -1
            ? store.countInScope(pkg, bounds[0], bounds[1]) : -1;
        String savedPaused = store.setting("paused", "0");
        String savedRetention = store.setting("retention", "0");
        String savedTime = store.setting("last_saved", "0");
        String size = settings ? formatSize(store.databaseBytes()) : "";
        runOnUiThread(() -> {
          refreshing = false;
          if (isDestroyed()) return;
          counts = numbers;
          apps = sources;
          if (!recordingSaving) paused = savedPaused;
          recordingLoaded = true;
          retention = savedRetention;
          lastSaved = savedTime;
          updateHeader();
          if (gen != generation) {
            refresh();
          } else if (settings) {
            if (storageSummary != null) storageSummary.setText(format(counts[0]) + " 則通知 · " + size);
          } else {
            showResults(records, amount, gen, exactCount);
          }
        });
      } catch (Exception e) {
        runOnUiThread(() -> {
          refreshing = false;
          if (isDestroyed()) return;
          Store.error = "無法讀取紀錄，請檢查儲存空間";
          updateHeader();
          if (gen != generation) {
            refresh();
          } else if (list != null) {
            resultCount.setText("讀取失敗");
            if (rows.isEmpty()) showEmpty("無法讀取通知", "請檢查手機儲存空間。", "重新整理", this::refresh);
          }
        });
      }
    });
  }

  private void showResults(List<Record> records, int amount, int gen, long exactCount) {
    boolean more = records.size() > amount;
    if (more) records.remove(records.size() - 1);
    rows.clear();
    rows.addAll(records);
    displayedGeneration = gen;
    loadMore.setVisibility(more ? View.VISIBLE : View.GONE);
    emptyCard.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    if (rows.isEmpty()) {
      if (analysisReturn != null) {
        showEmpty("找不到符合的通知", "可調整關鍵字，或返回分析切換範圍。", null, null);
      } else if (hasFilters()) {
        showEmpty("找不到符合的通知", "試試其他關鍵字、App 或日期。", null, null);
      } else if (page.equals("favorites")) {
        showEmpty("還沒有收藏", "打開一則通知，點選「加入收藏」。", "查看通知紀錄", () -> openPage("records"));
      } else if (!hasAccess()) {
        showEmpty("開啟通知存取權", "授權後，HuangNotify 會把收到的通知保存在手機。", "設定通知存取權", this::accessDialog);
      } else {
        showEmpty("還沒有通知紀錄", "收到並保存通知後，就會顯示在這裡。", null, null);
      }
    }
    resultCount.setText(exactCount >= 0 ? format(exactCount) + " 則"
        : format(rows.size()) + (more ? "+" : "") + " 則");
    adapter.notifyDataSetChanged();
  }

  private void showEmpty(String title, String message, String action, Runnable callback) {
    emptyTitle.setText(title);
    emptyBody.setText(message);
    emptyAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
    if (action != null) {
      emptyAction.setText(action);
      emptyAction.setOnClickListener(v -> callback.run());
    }
  }

  private void chooseApp() {
    List<String[]> sources = apps;
    String[] names = new String[sources.size() + 1];
    names[0] = "全部 App";
    int checked = 0;
    for (int i = 0; i < sources.size(); i++) {
      String[] app = sources.get(i);
      names[i + 1] = app[1] + " · " + app[2] + " 則";
      if (app[0].equals(filterPkg)) checked = i + 1;
    }
    new AppDialog.Builder(this)
        .setTitle("依 App 篩選")
        .setSingleChoiceItems(names, checked, (dialog, which) -> {
          filterPkg = which == 0 ? null : sources.get(which - 1)[0];
          filterName = which == 0 ? "全部 App" : sources.get(which - 1)[1];
          dialog.dismiss();
          filtersChanged();
        })
        .show();
  }

  private void detail(Record r) {
    if (loadingDetail || AppDialog.hasOpen(this)) return;
    loadingDetail = true;
    int requestedGeneration = generation;
    Store.IO.execute(
        () -> {
          try {
            JSONArray versions = Store.get(this).versions(r.id);
            JSONArray images = Store.get(this).recordImages(r.id);
            runOnUiThread(() -> {
              loadingDetail = false;
              if (resumed && requestedGeneration == generation && !AppDialog.hasOpen(this)) {
                showDetail(r, versions, images);
              }
            });
          } catch (Exception e) {
            runOnUiThread(() -> {
              loadingDetail = false;
              toast("無法讀取通知詳情");
            });
          }
        });
  }

  private void showDetail(Record record, JSONArray versions, JSONArray images) {
    if (isDestroyed() || isFinishing()) return;
    ScrollView scroll = new ScrollView(this);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout body = dialogBody();
    scroll.addView(body);

    LinearLayout source = u.row();
    ImageView icon = new ImageView(this);
    icon.setImageDrawable(u.appIcon(record.pkg));
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    source.addView(icon, new LinearLayout.LayoutParams(u.d(34), u.d(34)));
    LinearLayout sourceText = u.column();
    sourceText.addView(u.text(record.app, 15, u.ink, true));
    sourceText.addView(u.text(date(record.firstSeen, "yyyy/MM/dd HH:mm:ss"), 12, u.muted, false));
    LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(0, -2, 1);
    sourceParams.setMarginStart(u.d(10));
    source.addView(sourceText, sourceParams);
    body.addView(source);

    u.gap(body, 13);
    TextView title = u.text(record.title.isEmpty() ? "無標題的通知" : record.title, 23, u.ink, true);
    title.setTextIsSelectable(true);
    u.heading(title);
    body.addView(title);
    String status = record.active && CaptureService.connected ? "仍在通知列"
        : record.removed > 0 ? EventPolicy.removalLabel(record.reason) + " · " + date(record.removed, "MM/dd HH:mm:ss")
        : "已保存 · 目前通知狀態未知";
    body.addView(u.text(status, 12, u.muted, false));
    u.gap(body, 14);

    LinearLayout actions = u.row();
    TextView favorite = u.button(record.favorite ? "移除收藏" : "加入收藏", true);
    TextView copy = u.button("複製內容", false);
    actions.addView(favorite, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
    copyParams.setMarginStart(u.d(8));
    actions.addView(copy, copyParams);
    body.addView(actions);
    favorite.setOnClickListener(v -> {
      boolean selected = !record.favorite;
      favorite.setEnabled(false);
      mutate(() -> Store.get(this).favorite(record.id, selected), null, saved -> {
        if (saved) record.favorite = selected;
        favorite.setText(record.favorite ? "移除收藏" : "加入收藏");
        favorite.setEnabled(true);
      });
    });
    copy.setOnClickListener(v -> {
      getSystemService(ClipboardManager.class).setPrimaryClip(
          ClipData.newPlainText("通知", record.title + "\n" + record.text));
      toast("已複製通知內容");
    });

    LinearLayout message = detailPanel(body, "通知內容");
    TextView text = u.text(record.text.isEmpty() ? "來源 App 未提供文字內容。" : record.text, 16, u.ink, false);
    text.setTextIsSelectable(true);
    text.setLineSpacing(u.d(4), 1);
    message.addView(text);
    ImageGallery.add(this, u, message, images, record.imageStatus, true);

    LinearLayout notes = detailPanel(body, "備註");
    TextView noteText = u.text(noteSummary(record), 15, u.ink, false);
    noteText.setTextIsSelectable(true);
    notes.addView(noteText);
    u.gap(notes, 8);
    TextView editNote = u.button("編輯備註", false);
    editNote.setOnClickListener(v -> editNote(record, noteText));
    notes.addView(editNote);

    if (versions.length() > 1) {
      LinearLayout history = detailPanel(body, "更新歷程 · " + versions.length() + " 個版本");
      for (int i = 0; i < versions.length(); i++) {
        JSONObject version = versions.optJSONObject(i);
        if (i > 0) u.gap(history, 12);
        history.addView(u.text(date(version.optLong("time"), "MM/dd HH:mm:ss"), 12, u.accent, true));
        TextView versionText = u.text(version.optString("title") + "\n" + version.optString("body"), 14, u.ink, false);
        versionText.setTextIsSelectable(true);
        history.addView(versionText);
        ImageGallery.add(this, u, history, version.optJSONArray("images"),
            version.optString("image_status", "none"), false);
      }
    }

    Intent launch = getPackageManager().getLaunchIntentForPackage(record.pkg);
    if (launch != null) {
      u.gap(body, 12);
      TextView open = u.button("開啟來源 App", false);
      body.addView(open);
      open.setOnClickListener(v -> {
        try {
          startActivity(launch);
        } catch (Exception e) {
          toast("無法開啟來源 App");
        }
      });
    }

    u.gap(body, 12);
    TextView delete = u.button("刪除此筆紀錄", false);
    u.ripple(delete, u.bg, 16);
    delete.setTextColor(u.danger);
    body.addView(delete);
    AppDialog detail = new AppDialog.Builder(this).setTitle("通知詳情")
        .setExpandedView(scroll).setPositiveButton("完成", null).show();
    delete.setOnClickListener(v -> new AppDialog.Builder(this)
        .setTitle("刪除這筆紀錄？")
        .setMessage("會一併刪除更新歷程與不再使用的圖片。此操作無法復原，來源 App 不受影響。")
        .setNegativeButton("取消", null)
        .setPositiveButton("刪除", (dialog, which) ->
            mutate(() -> Store.get(this).delete(record.id), "已刪除紀錄", saved -> {
              if (saved) detail.dismiss();
            }))
        .show());
  }

  private LinearLayout detailPanel(LinearLayout parent, String label) {
    u.gap(parent, 12);
    LinearLayout panel = u.column();
    panel.setPadding(u.d(14), u.d(12), u.d(14), u.d(12));
    panel.setBackground(u.outline(u.bg, 18));
    TextView heading = u.text(label, 14, u.accent, true);
    u.heading(heading);
    panel.addView(heading);
    u.gap(panel, 7);
    parent.addView(panel);
    return panel;
  }

  private String noteSummary(Record record) {
    return record.note.isEmpty() ? "尚未加入備註" : record.note;
  }

  private void editNote(Record record, TextView summary) {
    EditText input = u.field("輸入備註");
    input.setText(record.note);
    AppDialog dialog = new AppDialog.Builder(this).setTitle("通知備註").setView(input)
        .setNegativeButton("取消", null).setPositiveButton("儲存", null).create();
    dialog.setOnShowListener(d -> dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      String text = input.getText().toString();
      if (text.length() > 100000) {
        input.setError("備註最多 100,000 個字元");
        return;
      }
      v.setEnabled(false);
      mutate(() -> Store.get(this).note(record.id, text), "備註已儲存", saved -> {
        if (saved) {
          record.note = text;
          summary.setText(noteSummary(record));
          dialog.dismiss();
        } else {
          v.setEnabled(true);
        }
      });
    }));
    dialog.show();
  }

  private void buildSettings() {
    pageTitle(content, "偏好設定", "settings");
    settingsScroll = new ScrollView(this);
    settingsScroll.setTag("settings_scroll");
    settingsScroll.setFillViewport(true);
    settingsScroll.setClipToPadding(false);
    settingsScroll.setVerticalScrollBarEnabled(false);
    LinearLayout body = u.column();
    body.setPadding(pageInset(), 0, pageInset(), u.d(24));
    settingsScroll.addView(body);
    content.addView(settingsScroll, new LinearLayout.LayoutParams(-1, 0, 1));

    LinearLayout recording = settingGroup(body, "記錄與來源");
    TextView state = addSetting(recording, "記錄通知", recordingState(), "bell", recordSwitch(false), null);
    recordStates.add(state);
    accessSummary = settingRow(recording, "通知存取權", "", "lock", this::accessDialog);
    settingRow(recording, "選擇記錄來源", "搜尋已安裝 App，調整要記錄的來源", "apps", this::manageApps);
    imageSetting(recording);
    retentionSummary = settingRow(recording, "保存期限", "", "clock", this::chooseRetention);

    LinearLayout data = settingGroup(body, "資料");
    backupStatus = settingRow(data, "備份與儲存", backupSummary(BackupReceipt.latest(this)), "backup",
        () -> openPage("backup"));

    LinearLayout diagnostics = settingGroup(body, "權限與診斷");
    settingRow(diagnostics, "通知診斷", "連線狀態、來源排除與最近處理結果", "check", () -> Diagnostics.show(this));
    settingRow(diagnostics, "權限設定精靈", "依序完成通知權限設定", "lock", () -> permissionGuide.restart());
    settingRow(diagnostics, "背景運作說明", "Samsung 與省電模式設定建議", "bell", () -> permissionGuide.backgroundHelp());

    LinearLayout updates = settingGroup(body, "版本與更新");
    settingRow(updates, "檢查新版本", UpdateChecker.cached(this).message, "check", this::checkForUpdates);
    RecordSwitch update = new RecordSwitch(this);
    update.setContentDescription("自動檢查更新");
    update.setTag("update_check");
    update.setChecked(UpdateChecker.enabled(this));
    update.setOnCheckedChangeListener((button, checked) -> {
      UpdateChecker.enable(this, checked);
      updateFlow.automaticChanged();
    });
    addSetting(updates, "自動檢查更新", "進入 App 時向 GitHub 檢查新版本", "clock", update, null);
    settingRow(updates, "包含測試版",
        UpdateChecker.previews(this) ? "已開啟 · 可收到 Alpha / Beta 版本" : "只接收正式版本",
        "settings", () -> {
          UpdateChecker.setPreviews(this, !UpdateChecker.previews(this));
          updateFlow.automaticChanged();
          build();
        });
    settingRow(updates, "更新下載與安裝", "自動下載、非計量網路與安裝偏好", "backup", this::upgradeOptions);
    upgradeStatus = settingRow(updates, "安裝已下載更新", UpgradeManager.status(this), "check", this::installDownloaded);
    settingRow(updates, "更新通知權限", getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()
        ? "管理更新通知頻道" : "尚未允許通知 · 仍可手動檢查", "bell", () -> {
          Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
          intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
          startActivity(intent);
        });

    LinearLayout cleanup = settingGroup(body, "資料清理");
    settingRow(cleanup, "清除已保存圖片", "保留文字、歷程、收藏與備註", "trash", this::clearImagesDialog);
    settingRow(cleanup, "清除未收藏紀錄", "保留收藏，刪除其餘通知與歷程", "trash",
        () -> new AppDialog.Builder(this)
            .setTitle("清除所有未收藏紀錄？")
            .setMessage("此操作無法復原。建議先建立備份。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除", (dialog, which) ->
                mutate(() -> Store.get(this).clearUnstarred(), "已清除未收藏紀錄"))
            .show());

    LinearLayout about = settingGroup(body, "關於 HuangNotify");
    settingRow(about, "隱私與記錄範圍", "查看通知如何保存在手機", "lock", this::privacyDialog);
    TextView version = u.text("HuangNotify " + BuildConfig.VERSION_NAME + " · MIT 開源授權", 12, u.muted, false);
    version.setGravity(Gravity.CENTER);
    version.setPadding(0, u.d(18), 0, 0);
    body.addView(version);
    if (settingsScrollY != 0) {
      ScrollView scroll = settingsScroll;
      int position = settingsScrollY;
      scroll.post(() -> scroll.scrollTo(0, position));
    }
  }

  private void imageSetting(LinearLayout parent) {
    RecordSwitch control = new RecordSwitch(this);
    control.setContentDescription("保存通知圖片");
    control.setEnabled(false);
    addSetting(parent, "保存通知圖片", "每則最多 4 張 · 關閉只影響新通知", "image", control, null);
    Store.IO.execute(() -> {
      boolean enabled = !"0".equals(Store.get(this).setting("save_images", "1"));
      runOnUiThread(() -> {
        if (isDestroyed() || !control.isAttachedToWindow()) return;
        control.setChecked(enabled);
        control.setEnabled(true);
        control.setOnCheckedChangeListener((button, checked) -> {
          control.setEnabled(false);
          mutate(() -> Store.get(this).putSetting("save_images", checked ? "1" : "0"),
              checked ? "已開啟圖片記錄" : "已關閉圖片記錄，既有圖片保留", saved -> {
                if (!saved) {
                  control.setOnCheckedChangeListener(null);
                  control.setChecked(!checked);
                  if (page.equals("settings")) build();
                }
                control.setEnabled(true);
              });
        });
      });
    });
  }

  private final Runnable backupTick = this::refreshBackupPage;

  private void buildBackupPage() {
    TextView back = u.button("‹  返回設定", false);
    back.setPadding(u.d(10), 0, u.d(10), 0);
    back.setOnClickListener(v -> openPage("settings"));
    LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(-2, -2);
    backParams.setMarginStart(pageInset());
    backParams.topMargin = u.d(6);
    content.addView(back, backParams);
    pageTitle(content, "備份與儲存", "backup");

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout body = u.column();
    body.setPadding(pageInset(), 0, pageInset(), u.d(24));
    scroll.addView(body);
    content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    LinearLayout recent = settingGroup(body, "最近一次成功備份");
    recent.setPadding(u.d(16), u.d(14), u.d(16), u.d(14));
    lastBackup = u.text("", 15, u.ink, true);
    lastBackup.setLineSpacing(u.d(4), 1f);
    recent.addView(lastBackup);

    LinearLayout operations = settingGroup(body, "手動備份");
    settingRow(operations, "建立備份", "單份上限 " + BackupStreamCrypto.MAX_MIB + " MiB · "
        + BackupArchive.VERSION_LIMIT + "歷程", "backup", backupScreen::exportDialog);
    settingRow(operations, "還原備份", "先預覽檔案，再合併匯入", "check", backupScreen::chooseImport);

    LinearLayout automatic = settingGroup(body, "自動加密備份");
    automatic.setPadding(u.d(16), u.d(14), u.d(16), u.d(14));
    autoBackupState = u.text("", 14, u.ink, false);
    automatic.addView(autoBackupState);
    autoBackupProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    autoBackupProgress.setMax(100);
    automatic.addView(autoBackupProgress, new LinearLayout.LayoutParams(-1, u.d(12)));
    u.gap(automatic, 10);
    backupNow = u.button("", true);
    backupNow.setOnClickListener(v -> {
      if (AutoBackup.enabled(this)) runAutoBackup();
      else chooseBackupFolder(false);
    });
    automatic.addView(backupNow, new LinearLayout.LayoutParams(-1, -2));
    TextView cancel = u.button("取消本次備份", false);
    cancel.setOnClickListener(v -> { AutoBackup.cancel(); refreshBackupPage(); });
    cancelBackup = cancel;
    automatic.addView(cancel, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout actions = u.column();
    autoBackupActions = actions;
    automatic.addView(actions);
    backupFolder = settingRow(actions, "更換備份資料夾", "", "backup", () -> chooseBackupFolder(true));
    backupRetention = settingRow(actions, "保留份數", "", "clock", () ->
        chooseBackupRetention(AutoBackup.keep(this), keep -> {
          AutoBackup.setKeep(this, keep);
          refreshBackupPage();
        }));
    settingRow(actions, "變更備份密碼", "舊備份仍使用原密碼", "lock",
        () -> autoBackupPassword(AutoBackup.folder(this), false));
    settingRow(actions, "停用自動備份", "保留已儲存的備份", "close", () -> {
      AutoBackup.disable(this);
      refreshBackupPage();
    });

    LinearLayout storage = settingGroup(body, "本機儲存");
    storageSummary = settingRow(storage, "儲存概況", format(counts[0]) + " 則通知", "apps", this::storageDialog);
    refreshBackupPage();
  }

  private String backupSummary(BackupReceipt receipt) {
    if (receipt.time == 0) return "尚未記錄成功的備份";
    return new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(new Date(receipt.time))
        + " · " + (receipt.automatic ? "自動備份" : "手動備份");
  }

  private void refreshBackupPage() {
    handler.removeCallbacks(backupTick);
    if (lastBackup == null || !page.equals("backup")) return;
    BackupReceipt receipt = BackupReceipt.latest(this);
    String detail = backupSummary(receipt);
    if (!receipt.location.isEmpty()) detail += "\n" + receipt.location;
    if (receipt.bytes > 0) detail += "\n" + android.text.format.Formatter.formatFileSize(this, receipt.bytes);
    if (!detail.contentEquals(lastBackup.getText())) lastBackup.setText(detail);

    boolean enabled = AutoBackup.enabled(this), running = AutoBackup.running();
    BackupTask task = AutoBackup.task();
    String error = AutoBackup.prefs(this).getString("error", "");
    String state = enabled ? "約每天執行一次" : "選擇資料夾與密碼後啟用";
    if (running) {
      state = task == null ? "準備備份…" : task.exportStep() + "\n" + task.status();
    } else if (!error.isEmpty()) {
      state = error;
    }
    if (!state.contentEquals(autoBackupState.getText())) autoBackupState.setText(state);
    autoBackupProgress.setVisibility(running ? View.VISIBLE : View.GONE);
    long total = task == null ? 0 : task.total;
    autoBackupProgress.setIndeterminate(total <= 0);
    if (total > 0) autoBackupProgress.setProgress((int) Math.min(100, task.done * 100 / total));
    String action = enabled ? "立即備份" : "開啟自動備份";
    if (!action.contentEquals(backupNow.getText())) backupNow.setText(action);
    backupNow.setEnabled(!running && !configuringBackup);
    cancelBackup.setVisibility(running ? View.VISIBLE : View.GONE);
    cancelBackup.setEnabled(task == null || !task.cancelled.get());
    autoBackupActions.setVisibility(enabled ? View.VISIBLE : View.GONE);
    String location = AutoBackup.folderName(this), retention = "保留 " + AutoBackup.keep(this) + " 份";
    if (!location.contentEquals(backupFolder.getText())) backupFolder.setText(location);
    if (!retention.contentEquals(backupRetention.getText())) backupRetention.setText(retention);
    for (int i = 0; i < autoBackupActions.getChildCount(); i++)
      autoBackupActions.getChildAt(i).setEnabled(!configuringBackup);
    if (resumed) handler.postDelayed(backupTick, running ? 250 : 1200);
  }

  private void chooseBackupFolder(boolean move) {
    if (configuringBackup) return;
    try {
      startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
          Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
              | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), move ? MOVE_BACKUP_FOLDER : BACKUP_FOLDER);
    } catch (Exception e) {
      toast("無法開啟資料夾選擇器");
    }
  }

  private void chooseBackupRetention(int current, java.util.function.IntConsumer selected) {
    int index = 0;
    while (index < BACKUP_RETENTION.length - 1 && BACKUP_RETENTION[index] != current) index++;
    new AppDialog.Builder(this).setTitle("保留幾份自動備份？")
        .setMessage("下次備份完成驗證後才會清理超過份數的舊自動備份。")
        .setSingleChoiceItems(BACKUP_RETENTION_LABELS, index, (dialog, which) -> {
          selected.accept(BACKUP_RETENTION[which]);
          dialog.dismiss();
        }).setNegativeButton("取消", null).show();
  }

  private void autoBackupPassword(Uri tree, boolean firstSetup) {
    if (configuringBackup) return;
    LinearLayout body = u.column();
    body.addView(u.text("請記住備份密碼，換手機還原時會用到。舊備份仍使用建立時的密碼。", 14, u.muted, false));
    EditText password = u.field("備份密碼（至少 8 個字元）"), confirm = u.field("再次輸入密碼");
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    confirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    u.gap(body, 12); body.addView(password); u.gap(body, 8); body.addView(confirm);
    int[] keep = {AutoBackup.keep(this)};
    if (firstSetup) {
      u.gap(body, 8);
      TextView retention = u.button("保留 " + keep[0] + " 份", false);
      retention.setOnClickListener(v -> chooseBackupRetention(keep[0], value -> {
        keep[0] = value;
        retention.setText("保留 " + value + " 份");
      }));
      body.addView(retention);
    }
    AppDialog dialog = new AppDialog.Builder(this).setTitle(firstSetup ? "設定自動備份" : "變更備份密碼")
        .setView(body).setNegativeButton("取消", null)
        .setPositiveButton(firstSetup ? "開啟並備份" : "儲存", null).create();
    dialog.setOnShowListener(d -> dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      if (password.length() < 8) { password.setError("至少 8 個字元"); return; }
      if (!password.getText().toString().equals(confirm.getText().toString())) {
        confirm.setError("兩次密碼不相同");
        return;
      }
      char[] secret = password.getText().toString().toCharArray();
      password.setText(""); confirm.setText(""); dialog.dismiss();
      saveBackupSettings(() -> {
        try { AutoBackup.configure(this, tree, secret, keep[0]); }
        finally { Arrays.fill(secret, '\0'); }
      }, firstSetup, "無法儲存備份設定，請重試");
    }));
    dialog.show();
  }

  private void saveBackupSettings(Work work, boolean backup, String failure) {
    configuringBackup = true;
    refreshBackupPage();
    AutoBackup.IO.execute(() -> {
      boolean saved;
      try { work.run(); saved = true; } catch (Exception e) { saved = false; }
      boolean success = saved;
      if (success && backup) startAutoBackup();
      runOnUiThread(() -> {
        configuringBackup = false;
        if (isDestroyed()) return;
        refreshBackupPage();
        if (!success) toast(failure);
      });
    });
  }

  private void runAutoBackup() {
    if (BackupOperation.busy()) { toast("已有備份作業進行中"); return; }
    startAutoBackup();
    refreshBackupPage();
  }

  private void startAutoBackup() {
    AutoBackup.run(this, true, new java.util.concurrent.atomic.AtomicBoolean(), message -> runOnUiThread(() -> {
      if (!isDestroyed()) {
        refreshBackupPage();
        toast(message);
      }
    }));
  }
  private void upgradeOptions() {
    LinearLayout body=u.column();
    body.addView(u.text("開啟後會自動檢查 GitHub，並在 Wi-Fi 等非計量網路下載新版。下載完成會驗證簽章、套件名稱、版本與完整性。\n\n自動安裝需 Android 12 以上及安裝權限；系統仍可能要求確認。自動更新期間 App 可能短暫重啟。",14,u.muted,false));
    AppDialog.Toggle download=new AppDialog.Toggle(this), install=new AppDialog.Toggle(this);
    download.setText("自動下載更新（非計量網路）"); download.setChecked(UpgradeManager.automatic(this)); body.addView(download);
    install.setText("系統允許時自動安裝"); install.setChecked(UpgradeManager.autoInstall(this)); body.addView(install);
    download.setOnClickListener(v->{ download.toggle(); if (!download.isChecked()) install.setChecked(false); });
    install.setOnClickListener(v->{ install.toggle(); if (install.isChecked()) download.setChecked(true); });
    install.setEnabled(android.os.Build.VERSION.SDK_INT>=31);
    if (android.os.Build.VERSION.SDK_INT<31) body.addView(u.text("此 Android 版本需手動確認安裝。",13,u.muted,false));
    new AppDialog.Builder(this).setTitle("更新下載與安裝").setView(body).setNegativeButton("取消",null).setPositiveButton("儲存設定",(d,w)->{
      if (install.isChecked() && !download.isChecked()) { toast("自動安裝需同時開啟自動下載"); return; }
      UpgradeManager.configure(this,download.isChecked(),install.isChecked());
      if (download.isChecked()) { UpdateChecker.enable(this,true); UpdateChecker.check(this,false,r->{}); }
      if (download.isChecked() && install.isChecked() && !getPackageManager().canRequestPackageInstalls()) openInstallPermission();
      build(); toast("更新偏好已儲存");
    }).show();
  }
  private void openInstallPermission() {
    try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))); }
    catch(Exception e) { toast("請在系統設定允許 HuangNotify 安裝更新"); }
  }
  private void installDownloaded() { updateFlow.openDownloaded(); }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == 8413) {
      if (UpdateChecker.enabled(this)) UpdateChecker.check(this, false, result -> {});
      if (page.equals("settings")) build();
    }
  }

  private void checkForUpdates() {
    toast("正在檢查 GitHub 版本…"); updateFlow.check(true);
  }

  private LinearLayout settingGroup(LinearLayout parent, String label) {
    u.gap(parent, 13);
    TextView title = u.text(label, 15, u.accent, true);
    title.setPaddingRelative(u.d(3), 0, 0, u.d(8));
    u.heading(title);
    parent.addView(title);

    LinearLayout group = u.column();
    group.setBackground(u.outline(u.surface, 20));
    group.setClipToOutline(true);
    parent.addView(group, new LinearLayout.LayoutParams(-1, -2));
    return group;
  }

  private TextView settingRow(LinearLayout parent, String title, String subtitle, String icon, Runnable action) {
    return addSetting(parent, title, subtitle, icon, null, action);
  }

  private TextView addSetting(LinearLayout parent, String title, String subtitle, String icon,
      View control, Runnable action) {
    LinearLayout row = u.row();
    row.setMinimumHeight(u.d(64));
    row.setPaddingRelative(u.d(12), u.d(10), u.d(10), u.d(10));
    int color = icon.equals("trash") ? u.danger : u.accent;
    row.addView(u.iconTile(icon, color, u.pale, 12), new LinearLayout.LayoutParams(u.d(36), u.d(36)));
    LinearLayout text = u.column();
    LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
    textParams.setMarginStart(u.d(11));
    textParams.setMarginEnd(u.d(8));
    row.addView(text, textParams);
    TextView heading = u.text(title, 16, icon.equals("trash") ? u.danger : u.ink, true);
    text.addView(heading);
    TextView hint = u.text(subtitle, 13, u.muted, false);
    hint.setPadding(0, u.d(2), 0, 0);
    text.addView(hint);
    if (action != null) {
      u.ripple(row, Color.TRANSPARENT, 0);
      row.addView(u.icon("next", u.muted), new LinearLayout.LayoutParams(u.d(20), u.d(20)));
      row.setFocusable(true);
      row.setOnClickListener(v -> action.run());
    } else {
      row.addView(control);
      u.ripple(row, Color.TRANSPARENT, 0);
      heading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      row.setOnClickListener(v -> {
        if (control.isEnabled()) control.performClick();
      });
      row.setFocusable(false);
    }
    if (parent.getChildCount() > 0) {
      View divider = new View(this);
      divider.setBackgroundColor(u.line);
      LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, u.d(1));
      dividerParams.setMarginStart(u.d(59));
      dividerParams.setMarginEnd(u.d(12));
      parent.addView(divider, dividerParams);
    }
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    return hint;
  }

  private void accessDialog() {
    String state =
        hasAccess() ? (CaptureService.connected ? "通知服務已連線。" : "已有權限，但服務尚未連線。 ") : "尚未授予通知存取權。";
    new AppDialog.Builder(this)
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
                PermissionGuide.openListenerSettings(this);
              } catch (Exception e) {
                toast("請手動開啟系統的通知存取權設定");
              }
            })
        .show();
  }

  private boolean hasAccess() {
    return PermissionGuide.hasAccess(this);
  }

  private void chooseRetention() {
    String[] days = {"0", "7", "30", "90", "365"};
    String[] names = {"持續保留", "7 天", "30 天", "90 天", "365 天"};
    int checked = Arrays.asList(days).indexOf(retention);
    new AppDialog.Builder(this)
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
                new AppDialog.Builder(this)
                    .setTitle("啟用定期清理？")
                    .setMessage("會立即刪除超過 " + days[which] + " 天且未收藏的紀錄。之後在 App 開啟或收到通知時清理。此操作無法復原。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("啟用", (dd, w) -> apply.run())
                    .show();
            })
        .show();
  }

  private void manageApps() {
    if (loadingSources || (sourcePicker!=null && sourcePicker.dialog.isShowing())) return;
    loadingSources=true;
    Store.IO.execute(()->{
      try {
        List<AppSources.Entry> entries=AppSources.load(this,Store.get(this));
        runOnUiThread(()->{
          loadingSources=false; if(isFinishing() || isDestroyed()) return;
          sourcePicker=new SourcePicker(this,entries,changes->mutate(()->Store.get(this).setSourceChoices(changes),"記錄來源已更新"));
          sourcePicker.show();
        });
      } catch(Exception e) { runOnUiThread(()->{ loadingSources=false; showError("無法讀取已安裝 App，請稍後重試"); }); }
    });
  }

  private String formatSize(long size) {
    return android.text.format.Formatter.formatFileSize(this, size);
  }

  private void storageDialog() {
    Store.IO.execute(() -> {
      try {
        Store store = Store.get(this);
        Store.StorageUsage usage = store.storageUsage();
        long[] totals = store.counts();
        List<String[]> sources = store.apps();
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          LinearLayout body = u.column();
          addStorageRow(body, "資料庫總用量", usage.totalBytes,
              "分類用量為估算");
          addStorageRow(body, "通知與備註", usage.notificationBytes,
              format(totals[0]) + " 則通知 · 今日新增 " + format(totals[1]) + " 則 · 收藏 " + format(totals[2]) + " 則");
          addStorageRow(body, "更新歷程", usage.historyBytes,
              format(usage.historyCount) + " 筆（含每則通知的初次記錄）");
          addStorageRow(body, "圖片", usage.imageBytes, format(usage.imageCount) + " 張（相同圖片只計一次）");
          addStorageRow(body, "其他資料庫用量", usage.otherBytes(), "含索引、時間欄位、日誌與可重用空間");
          if (!sources.isEmpty()) {
            TextView heading = u.text("各 App 保存數量", 15, u.ink, true);
            u.heading(heading);
            body.addView(heading);
            StringBuilder sourceCounts = new StringBuilder();
            for (String[] source : sources) {
              if (sourceCounts.length() > 0) sourceCounts.append('\n');
              sourceCounts.append(source[1]).append(" · ").append(source[2]).append(" 則");
            }
            TextView sourceUsage = u.text(sourceCounts.toString(), 14, u.muted, false);
            sourceUsage.setLineSpacing(u.d(4), 1f);
            body.addView(sourceUsage);
          }
          new AppDialog.Builder(this).setTitle("儲存概況").setView(body)
              .setPositiveButton("完成", null)
              .setNeutralButton("清除已保存圖片", (dialog, button) -> clearImagesDialog()).show();
        });
      } catch (Exception error) {
        runOnUiThread(() -> toast("無法讀取儲存用量"));
      }
    });
  }

  private void addStorageRow(LinearLayout body, String label, long bytes, String detail) {
    LinearLayout row = u.row();
    row.addView(u.text(label, 15, u.ink, true), new LinearLayout.LayoutParams(0, -2, 1));
    TextView size = u.text(formatSize(bytes), 15, u.ink, true);
    LinearLayout.LayoutParams sizeParams = new LinearLayout.LayoutParams(-2, -2);
    sizeParams.setMarginStart(u.d(8));
    row.addView(size, sizeParams);
    body.addView(row);
    body.addView(u.text(detail, 13, u.muted, false));
    u.gap(body, 12);
  }
  private void clearImagesDialog(){ImageCleanupScreen.show(this,u,this::refresh);}

  private void privacyDialog() {
    new AppDialog.Builder(this)
        .setTitle("你的通知，由你保管")
        .setMessage(
            "• 所有紀錄保存在本機。\n"
                + "• App 向 GitHub 檢查公開版本及下載更新，不上傳通知內容；沒有廣告或分析追蹤。\n"
                + "• 通知診斷只在本機保留最近 200 筆時間、來源套件與處理狀態，可停用並清除；匯出前會提示，不自動分享。\n"
                + "• 不會代你清除通知或回覆訊息。\n"
                + "• 系統自動備份已停用，手動匯出與選用自動備份會寫入你選擇的位置；若選雲端資料夾，檔案會由該服務保存。\n\n"
                + "保存通知文字與系統提供且允許讀取的圖片，每則最多 4 張、每張 256 KB、最長邊 1440 px。只讀通知附帶的本機圖片，不掃描相簿、不直接下載網路圖片，也不保存 App 頭像。可在設定關閉新圖片記錄，既有圖片會保留。來源 App 未提供的內容，以及系統遮蔽的敏感內容，無法還原。\n\n"
                + "首次連線會補存目前仍在通知列的通知。服務中斷期間已消失的通知可能漏記；重連時會核對仍存在的通知。\n\n"
                + "解除安裝或清除 App 資料會刪除紀錄，請先匯出備份。")
        .setPositiveButton("了解", null)
        .show();
  }

  @Override protected void onActivityResult(int request,int result,Intent data) {
    super.onActivityResult(request,result,data);
    if (request == ANALYSIS_REQUEST) {
      if (result == RESULT_OK && data != null) {
        analysisReturn = data.getBundleExtra(AnalysisActivity.DASHBOARD_STATE);
        if (analysisReturn != null) {
          filtersBeforeAnalysis = new Bundle();
          saveRecordFilters(filtersBeforeAnalysis);
        }
        rangeStart = data.getStringExtra(AnalysisActivity.FIRST_DATE);
        rangeEnd = data.getStringExtra(AnalysisActivity.LAST_DATE);
        filterPkg = data.getStringExtra(AnalysisActivity.PACKAGE);
        filterName = filterPkg == null ? "全部 App" : data.getStringExtra(AnalysisActivity.APP_NAME);
        onlyToday = onlyImages = false;
        query = "";
        page = "records";
        updateBackAction();
        rebuildPage();
      }
      return;
    }
    if(backupScreen.onResult(request,result,data))return;
    if(result!=RESULT_OK||data==null||data.getData()==null)return;
    Uri uri=data.getData();
    if(request==104){Diagnostics.save(this,uri);return;}
    if (request == BACKUP_FOLDER || request == MOVE_BACKUP_FOLDER) {
      try {
        int flags=data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) throw new SecurityException("需要資料夾讀寫權限");
        getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (request == MOVE_BACKUP_FOLDER) saveBackupSettings(() -> AutoBackup.moveFolder(this, uri),
            false, "無法儲存備份位置，請重新選擇");
        else autoBackupPassword(uri, true);
      } catch(Exception e) { toast("無法保留資料夾權限，請重新選擇"); }
      return;
    }
  }

  private LinearLayout dialogBody() {
    LinearLayout b = u.column();
    b.setPadding(0, u.d(4), 0, u.d(8));
    b.setBackgroundColor(u.surface);
    return b;
  }

  private interface Work {
    void run() throws Exception;
  }

  private void mutate(Work work, String message) {
    mutate(work, message, null);
  }

  private void mutate(Work work, String message, java.util.function.Consumer<Boolean> finished) {
    Store.IO.execute(() -> {
      boolean success;
      try {
        work.run();
        success = true;
      } catch (Exception e) {
        success = false;
      }
      boolean saved = success;
      runOnUiThread(() -> {
        if (isDestroyed()) return;
        if (finished != null) finished.accept(saved);
        if (saved) {
          generation++;
          refresh();
          if (message != null) toast(message);
        } else {
          toast("儲存失敗，請檢查手機剩餘空間");
        }
      });
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
