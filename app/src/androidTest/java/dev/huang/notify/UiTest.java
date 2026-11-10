package dev.huang.notify;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.test.ActivityInstrumentationTestCase2;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("deprecation")
public class UiTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public UiTest() {
    super(MainActivity.class);
  }

  @Override protected void setUp() throws Exception {
    super.setUp();
    UpdateChecker.enable(getInstrumentation().getTargetContext(),false);
    getInstrumentation().getTargetContext().getSharedPreferences("permission_guide", 0).edit().putInt("step", 3).commit();
  }

  public void testNavigationAndEmptyState() throws Exception {
    MainActivity activity = getActivity();
    getInstrumentation().waitForIdleSync();
    View decor = activity.getWindow().getDecorView();
    assertNotNull(find(decor, "通知紀錄"));
    View nav = decor.findViewWithTag("floating_nav");
    assertNotNull(nav);
    assertTrue(nav.findViewWithTag("nav_records").isSelected());
    View settings = find(decor, "設定");
    assertNotNull(settings);
    getInstrumentation().runOnMainSync(() -> ((View) settings.getParent()).performClick());
    getInstrumentation().waitForIdleSync();
    assertSame(nav, decor.findViewWithTag("floating_nav"));
    assertTrue(nav.findViewWithTag("nav_settings").isSelected());
    assertNotNull(find(decor, "偏好設定"));
    assertNotNull(find(decor, "建立備份"));

    Rect navBounds = new Rect(), hostBounds = new Rect(), scrollBounds = new Rect();
    ScrollView scroll = decor.findViewWithTag("settings_scroll");
    assertTrue(nav.getGlobalVisibleRect(navBounds));
    assertTrue(((View) nav.getParent()).getGlobalVisibleRect(hostBounds));
    assertTrue(scroll.getGlobalVisibleRect(scrollBounds));
    assertTrue("The navigation should float in from both edges", navBounds.left > hostBounds.left
        && navBounds.right < hostBounds.right && navBounds.bottom < hostBounds.bottom);
    assertTrue("The page should scroll behind the navigation", scrollBounds.bottom > navBounds.top);
    capture("glass-settings.png");
    getInstrumentation().runOnMainSync(() -> scroll.scrollTo(0, scroll.getChildAt(0).getHeight()));
    getInstrumentation().waitForIdleSync();
    Rect lastSetting = new Rect();
    View privacy = find(decor, "隱私與記錄範圍");
    assertNotNull(privacy);
    assertTrue(privacy.getGlobalVisibleRect(lastSetting));
    assertTrue("The last setting should clear the floating navigation", lastSetting.bottom <= navBounds.top);

    View star = find(decor, "收藏");
    getInstrumentation().runOnMainSync(() -> ((View) star.getParent()).performClick());
    getInstrumentation().waitForIdleSync();
    assertTrue(nav.findViewWithTag("nav_favorites").isSelected());
    assertNotNull(find(decor, "收藏通知"));
  }

  @TargetApi(33)
  public void testGlassNavigationRefractsAndRefreshesItsBackdrop() throws Exception {
    if (Build.VERSION.SDK_INT < 33) return;
    MainActivity activity = getActivity();
    getInstrumentation().waitForIdleSync();
    GlassNavigation nav = activity.getWindow().getDecorView().findViewWithTag("floating_nav");
    FrameLayout host = (FrameLayout) nav.getParent();
    View page = host.getChildAt(0);
    Ui ui = new Ui(activity);

    class Bands extends View {
      final Paint blue = new Paint();
      int depth = ui.d(13);

      Bands() {
        super(activity);
        blue.setColor(Color.BLUE);
      }

      @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.RED);
        canvas.drawRect(0, nav.getTop() - getTop() + depth, getWidth(), getHeight(), blue);
      }
    }

    FutureTask<Bands> createBands = new FutureTask<>(Bands::new);
    getInstrumentation().runOnMainSync(createBands);
    Bands bands = createBands.get();
    try {
      int refracted = glassRimPixel(activity, nav, ui, () -> {
        host.addView(bands, host.indexOfChild(nav), new FrameLayout.LayoutParams(-1, -1));
        nav.setPageContent(bands);
      });
      assertTrue("Expected blue from inside the upper rim, got " + Integer.toHexString(refracted),
          Color.blue(refracted) > Color.red(refracted) + 24);

      int moved = glassRimPixel(activity, nav, ui, () -> {
        bands.depth = ui.d(29);
        bands.invalidate();
        nav.invalidateBackdrop();
      });
      assertTrue("Expected red after the backdrop moved, got " + Integer.toHexString(moved),
          Color.red(moved) > Color.blue(moved) + 24);
    } finally {
      getInstrumentation().runOnMainSync(() -> {
        nav.setPageContent(page);
        host.removeView(bands);
      });
    }
  }

  @TargetApi(29)
  private int glassRimPixel(MainActivity activity, View nav, Ui ui, Runnable change) throws Exception {
    CompletableFuture<Rect> frame = new CompletableFuture<>();
    getInstrumentation().runOnMainSync(() -> {
      change.run();
      int[] location = new int[2];
      nav.getLocationInWindow(location);
      int x = location[0] + nav.getWidth() / 2;
      int y = location[1] + ui.d(6);
      nav.getViewTreeObserver().registerFrameCommitCallback(() -> frame.complete(new Rect(x, y, x + 1, y + 1)));
    });
    Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    try {
      CompletableFuture<Integer> copied = new CompletableFuture<>();
      PixelCopy.request(activity.getWindow(), frame.get(8, TimeUnit.SECONDS), pixel, copied::complete,
          new Handler(activity.getMainLooper()));
      assertEquals(PixelCopy.SUCCESS, copied.get(8, TimeUnit.SECONDS).intValue());
      return pixel.getPixel(0, 0);
    } finally {
      pixel.recycle();
    }
  }

  public void testRecordingSwitchPersistsAcrossPages() throws Exception {
    MainActivity activity=getActivity();
    long deadline=android.os.SystemClock.elapsedRealtime()+8000;
    while (android.os.SystemClock.elapsedRealtime()<deadline && !recordSwitch(activity.getWindow().getDecorView()).isEnabled()) { getInstrumentation().waitForIdleSync(); android.os.SystemClock.sleep(50); }
    RecordSwitch control=recordSwitch(activity.getWindow().getDecorView()); assertTrue(control.isEnabled());
    boolean previous=control.isChecked();
    try {
      getInstrumentation().runOnMainSync(()->control.performClick());
      Store.IO.submit(()->{}).get(); getInstrumentation().waitForIdleSync();
      assertEquals(previous?"1":"0",Store.IO.submit(()->Store.get(activity).setting("paused","0")).get());
      getInstrumentation().runOnMainSync(()->AppDialog.dismissAll(activity));
      View settings=find(activity.getWindow().getDecorView(),"設定");
      getInstrumentation().runOnMainSync(()->((View)settings.getParent()).performClick()); getInstrumentation().waitForIdleSync();
      assertEquals(!previous,recordSwitch(activity.getWindow().getDecorView()).isChecked());
      capture("record-switch.png");
      View backup=find(activity.getWindow().getDecorView(),"自動加密備份");
      getInstrumentation().runOnMainSync(()->((View)backup.getParent().getParent()).performClick());
      getInstrumentation().waitForIdleSync(); capture("auto-backup.png");
      getInstrumentation().runOnMainSync(()->AppDialog.dismissAll(activity));
      View update=find(activity.getWindow().getDecorView(),"更新下載與安裝");
      getInstrumentation().runOnMainSync(()->((View)update.getParent().getParent()).performClick());
      getInstrumentation().waitForIdleSync(); capture("update-options.png");
      getInstrumentation().runOnMainSync(()->AppDialog.dismissAll(activity));
    } finally { Store.IO.submit(()->Store.get(activity).putSetting("paused",previous?"0":"1")).get(); }
  }

  public void testSettingsStayScrolledWhenNotificationCountChanges() throws Exception {
    MainActivity activity = getActivity();
    getInstrumentation().runOnMainSync(() -> activity.getWindow().getDecorView()
        .findViewWithTag("nav_settings").performClick());
    getInstrumentation().waitForIdleSync();
    ScrollView scroll = activity.getWindow().getDecorView().findViewWithTag("settings_scroll");
    int offset = new Ui(activity).d(300);
    getInstrumentation().runOnMainSync(() -> scroll.scrollTo(0, offset));
    assertEquals(offset, scroll.getScrollY());

    String title = "settings-scroll-" + java.util.UUID.randomUUID();
    Record record = Store.IO.submit(() -> {
      Store store = Store.get(activity);
      String paused = store.setting("paused", "0");
      store.putSetting("paused", "0");
      try {
        store.capture(title, "settings.fixture", "Fixture", title, "", 1);
        return store.list(title, null, false, false, 1).get(0);
      } finally {
        store.putSetting("paused", paused);
      }
    }).get();
    try {
      long count = Store.IO.submit(() -> Store.get(activity).counts()[0]).get();
      String expected = java.text.NumberFormat.getIntegerInstance(java.util.Locale.TAIWAN)
          .format(count) + " 則通知 · ";
      View storage = find(activity.getWindow().getDecorView(), "儲存概況");
      TextView summary = (TextView) ((ViewGroup) storage.getParent()).getChildAt(1);
      long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
      while (!summary.getText().toString().startsWith(expected)
          && android.os.SystemClock.elapsedRealtime() < deadline) {
        android.os.SystemClock.sleep(50);
        getInstrumentation().waitForIdleSync();
      }
      assertTrue(summary.getText().toString().startsWith(expected));
      assertSame(scroll, activity.getWindow().getDecorView().findViewWithTag("settings_scroll"));
      assertEquals(offset, scroll.getScrollY());
    } finally {
      Store.IO.submit(() -> Store.get(activity).delete(record.id)).get();
    }
  }
  private void capture(String name) throws Exception {
    for(String command:new String[]{"mkdir -p /data/local/tmp/huangnotify-ui-preview", "screencap -p /data/local/tmp/huangnotify-ui-preview/"+name}) {
      try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand(command); java.io.FileInputStream in=new java.io.FileInputStream(fd.getFileDescriptor())) { byte[] bytes=new byte[1024]; while(in.read(bytes)!=-1) {} }
    }
  }
  private RecordSwitch recordSwitch(View v) {
    if(v instanceof RecordSwitch) return (RecordSwitch)v;
    if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) { RecordSwitch result=recordSwitch(((ViewGroup)v).getChildAt(i)); if(result!=null)return result; }
    return null;
  }

  private View find(View v, String text) {
    if (v instanceof TextView && text.equals(((TextView) v).getText().toString())) return v;
    if (v instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) v;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }
}
