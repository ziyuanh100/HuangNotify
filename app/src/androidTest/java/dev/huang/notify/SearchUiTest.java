package dev.huang.notify;

import android.content.Context;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.util.function.Predicate;

@SuppressWarnings("deprecation")
public class SearchUiTest extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity main;
  private Store store;

  public SearchUiTest() { super(MainActivity.class); }

  @Override protected void setUp() throws Exception {
    super.setUp();
    Context context = getInstrumentation().getTargetContext();
    UpdateChecker.enable(context, false);
    context.getSharedPreferences("permission_guide", 0).edit().putInt("step", 3).commit();
    store = Store.get(context);
    Store.IO.submit(() -> {
      clear();
      store.capture("pickup", "search.fixture", "LINE", "包裹到店", "取件代碼 １2３", 1);
      store.capture("pickup", "search.fixture", "LINE", "包裹已領取", "完成", 2);
    }).get();
  }

  private void clear() {
    store.getWritableDatabase().delete("records", null, null);
    store.getWritableDatabase().delete("settings", null, null);
    store.pruneImages();
  }

  @Override protected void tearDown() throws Exception {
    try {
      if (main != null) getInstrumentation().runOnMainSync(() -> { AppDialog.dismissAll(main); main.finish(); });
      Store.IO.submit(this::clear).get();
    } finally { super.tearDown(); }
  }

  public void testExpandSearchToHistoryAndOpenTheHighlightedVersion() throws Exception {
    main = getActivity();
    View content = main.getWindow().getDecorView();
    EditText search = (EditText) await(content, view -> view instanceof EditText);
    getInstrumentation().runOnMainSync(() -> search.setText("LINE 取件 123"));
    View expand = awaitText(content, "搜尋更新歷程");
    getInstrumentation().runOnMainSync(() -> assertTrue(expand.performClick()));
    TextView match = (TextView) awaitText(content, "取件代碼 １2３");
    CharSequence snippet = match.getText();
    assertTrue(snippet instanceof Spanned);
    assertTrue(((Spanned) snippet).getSpans(0, snippet.length(), BackgroundColorSpan.class).length >= 2);

    ListView records = (ListView) await(content, view -> view instanceof ListView);
    getInstrumentation().runOnMainSync(() -> {
      int position = records.getHeaderViewsCount();
      assertTrue(records.performItemClick(records.getChildAt(position - records.getFirstVisiblePosition()),
          position, records.getItemIdAtPosition(position)));
    });
    AppDialog detail = awaitDialog();
    View details = detail.getWindow().getDecorView();
    awaitText(details, "命中的更新歷程");
    awaitText(details, "包裹到店\n取件代碼 １2３");
    getInstrumentation().runOnMainSync(detail::dismiss);
    assertEquals("LINE 取件 123", search.getText().toString());
  }

  public void testRecentDateChipAndClearFiltersKeepTheQuery() throws Exception {
    main = getActivity();
    View content = main.getWindow().getDecorView();
    EditText search = (EditText) await(content, view -> view instanceof EditText);
    getInstrumentation().runOnMainSync(() -> { search.setText("LINE 領取"); main.dateFilterDialog(); });
    AppDialog dates = awaitDialog();
    View picker = dates.getWindow().getDecorView();
    View custom = awaitText(picker, "自訂日期");
    getInstrumentation().runOnMainSync(() -> ((View) custom.getParent()).performClick());
    await(picker, view -> "用日曆選擇開始日期".contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription()));
    View quick = awaitText(picker, "最近 7 天");
    getInstrumentation().runOnMainSync(() -> {
      ((View) quick.getParent()).performClick();
      dates.getButton(AppDialog.BUTTON_POSITIVE).performClick();
    });
    awaitText(content, "最近 7 天 ▾");
    View reset = awaitText(content, "清除篩選");
    getInstrumentation().runOnMainSync(() -> assertTrue(reset.performClick()));
    awaitText(content, "所有日期 ▾");
    assertEquals("LINE 領取", search.getText().toString());
  }

  private AppDialog awaitDialog() throws Exception {
    java.lang.reflect.Field open = AppDialog.class.getDeclaredField("OPEN");
    open.setAccessible(true);
    long deadline = SystemClock.elapsedRealtime() + 10000;
    while (SystemClock.elapsedRealtime() < deadline) {
      getInstrumentation().waitForIdleSync();
      @SuppressWarnings("unchecked") java.util.Map<Context, java.util.List<AppDialog>> dialogs =
          (java.util.Map<Context, java.util.List<AppDialog>>) open.get(null);
      java.util.List<AppDialog> list = dialogs.get(main);
      if (list != null && !list.isEmpty()) return list.get(list.size() - 1);
      SystemClock.sleep(50);
    }
    throw new AssertionError("Dialog did not appear");
  }

  private View awaitText(View root, String text) {
    return await(root, view -> view instanceof TextView && text.contentEquals(((TextView) view).getText()));
  }

  private View await(View root, Predicate<View> matches) {
    long deadline = SystemClock.elapsedRealtime() + 10000;
    while (SystemClock.elapsedRealtime() < deadline) {
      getInstrumentation().waitForIdleSync();
      View view = find(root, matches);
      if (view != null) return view;
      SystemClock.sleep(50);
    }
    throw new AssertionError("Search control or result did not appear");
  }

  private View find(View view, Predicate<View> matches) {
    if (view.getVisibility() != View.VISIBLE) return null;
    if (matches.test(view)) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View result = find(group.getChildAt(i), matches);
        if (result != null) return result;
      }
    }
    return null;
  }
}
