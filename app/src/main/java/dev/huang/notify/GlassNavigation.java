package dev.huang.notify;

import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;

/** A capsule over the page, with a bounded hardware backdrop and a tinted fallback. */
final class GlassNavigation extends LinearLayout {
  private static final String[] PAGES = {"records", "favorites", "settings"};
  private static final String[] LABELS = {"紀錄", "收藏", "設定"};
  private static final String[] ICONS = {"bell", "star", "settings"};
  private final Ui u;
  private final LinearLayout[] tabs = new LinearLayout[PAGES.length];
  private final TextView[] labels = new TextView[PAGES.length];
  private final Ui.Glyph[] icons = new Ui.Glyph[PAGES.length];
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF capsule = new RectF();
  private final Path clip = new Path();
  private final Backdrop backdrop;
  private LinearGradient glass, fallback, edge, selection;
  private View pageContent;
  private boolean backdropDirty = true, recordingBackdrop;
  private int selected = -1;
  private float indicator;
  private ValueAnimator animator;

  GlassNavigation(Ui u, String page, Consumer<String> open) {
    super(u.context);
    this.u = u;
    backdrop = Build.VERSION.SDK_INT >= 31 ? new Backdrop(u) : null;
    setTag("floating_nav");
    setOrientation(HORIZONTAL);
    setGravity(Gravity.CENTER_VERTICAL);
    setPadding(u.d(5), u.d(5), u.d(5), u.d(5));
    setWillNotDraw(false);
    setElevation(u.d(10));
    setOutlineProvider(new ViewOutlineProvider() {
      @Override public void getOutline(View view, Outline outline) {
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
      }
    });
    setClipToOutline(true);

    for (int i = 0; i < PAGES.length; i++) {
      String destination = PAGES[i];
      LinearLayout tab = new LinearLayout(u.context) {
        @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
      };
      tab.setOrientation(VERTICAL);
      tab.setGravity(Gravity.CENTER);
      tab.setMinimumHeight(u.d(52));
      tab.setPadding(u.d(3), u.d(4), u.d(3), u.d(4));
      u.ripple(tab, Color.TRANSPARENT, 25);
      tab.setTag("nav_" + destination);
      tab.setContentDescription(LABELS[i]);
      tab.setFocusable(true);
      tab.setOnClickListener(v -> open.accept(destination));

      icons[i] = u.icon(ICONS[i], u.muted);
      tab.addView(icons[i], new LayoutParams(u.d(22), u.d(22)));
      labels[i] = u.text(LABELS[i], 12, u.muted, true);
      labels[i].setGravity(Gravity.CENTER);
      labels[i].setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      LayoutParams label = new LayoutParams(-1, -2);
      label.topMargin = u.d(2);
      tab.addView(labels[i], label);
      tabs[i] = tab;
      addView(tab, new LayoutParams(0, -2, 1));
    }
    selectPage(page);
  }

  void setPageContent(View content) {
    pageContent = content;
    invalidateBackdrop();
  }

  void invalidateBackdrop() {
    if (backdrop == null || recordingBackdrop) return;
    backdropDirty = true;
    if (isShown() && isHardwareAccelerated()) invalidate();
  }

  void selectPage(String page) {
    int index = 0;
    while (index < PAGES.length - 1 && !PAGES[index].equals(page)) index++;
    if (index == selected) return;
    selected = index;
    for (int i = 0; i < tabs.length; i++) {
      boolean active = i == selected;
      int color = active ? u.accent : u.muted;
      tabs[i].setSelected(active);
      labels[i].setTextColor(color);
      icons[i].setColor(color);
    }
    if (animator != null) animator.cancel();
    if (!isLaidOut() || !isShown() || !ValueAnimator.areAnimatorsEnabled()) {
      indicator = selected;
      invalidate();
      return;
    }
    animator = ValueAnimator.ofFloat(indicator, selected);
    animator.setDuration(260);
    animator.setInterpolator(new PathInterpolator(.2f, .8f, .25f, 1f));
    animator.addUpdateListener(value -> {
      indicator = (float) value.getAnimatedValue();
      invalidate();
    });
    animator.start();
  }

  @Override protected void onDetachedFromWindow() {
    if (animator != null) animator.cancel();
    indicator = selected;
    super.onDetachedFromWindow();
  }

  @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    if (width <= 0 || height <= 0) return;
    capsule.set(0, 0, width, height);
    clip.reset();
    clip.addRoundRect(capsule, height / 2f, height / 2f, Path.Direction.CW);
    glass = gradient(height, u.dark
        ? new int[]{0xc92b4035, 0xb9192b22, 0xd71a2c23}
        : new int[]{0xd6fcfff9, 0xaeeef8ee, 0xc7e4f0e7});
    fallback = gradient(height, u.dark
        ? new int[]{0xfa2b4035, 0xfa1e2e26, 0xfa192921}
        : new int[]{0xfbfcfff9, 0xf3f1f8ef, 0xf5e6f0e7});
    edge = gradient(height, u.dark
        ? new int[]{0x9db8dfc9, 0x325e8a76, 0x684f7661}
        : new int[]{0xffffffff, 0x7d8eac9b, 0xb1c0d9cb});
    selection = gradient(height, u.dark
        ? new int[]{0x8a739b68, 0x5649735a, 0x73426850}
        : new int[]{0xf2e5f4c9, 0xe3d5eacf, 0xe5c5dfce});
    backdropDirty = true;
  }

  @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    if (changed) backdropDirty = true;
  }

  private LinearGradient gradient(int height, int[] colors) {
    return new LinearGradient(0, 0, 0, height, colors, null, Shader.TileMode.CLAMP);
  }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (glass == null) return;
    float radius = getHeight() / 2f;
    boolean blur = backdrop != null && canvas.isHardwareAccelerated()
        && pageContent != null && pageContent.isLaidOut();
    if (blur) {
      int save = canvas.save();
      canvas.clipPath(clip);
      recordingBackdrop = true;
      try {
        backdrop.draw(canvas, pageContent, getLeft(), getTop(), getWidth(), getHeight(), backdropDirty);
        backdropDirty = false;
      } finally {
        recordingBackdrop = false;
        canvas.restoreToCount(save);
      }
    }
    paint.setStyle(Paint.Style.FILL);
    paint.setShader(blur ? glass : fallback);
    canvas.drawRoundRect(capsule, radius, radius, paint);

    int from = Math.min((int) indicator, tabs.length - 1);
    int to = Math.min(from + 1, tabs.length - 1);
    float progress = indicator - from;
    float left = interpolate(tabs[from].getLeft(), tabs[to].getLeft(), progress) + u.d(2);
    float right = interpolate(tabs[from].getRight(), tabs[to].getRight(), progress) - u.d(2);
    float inset = u.d(5);
    float innerRadius = (getHeight() - inset * 2) / 2f;
    paint.setShader(selection);
    canvas.drawRoundRect(left, inset, right, getHeight() - inset, innerRadius, innerRadius, paint);

    float rim = u.d(1) / 2f;
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(u.d(1));
    paint.setShader(edge);
    canvas.drawRoundRect(rim, rim, getWidth() - rim, getHeight() - rim, radius - rim, radius - rim, paint);
    paint.setShader(null);
  }

  private static float interpolate(float from, float to, float progress) {
    return from + (to - from) * progress;
  }

  @TargetApi(31)
  private static final class Backdrop {
    private final RenderNode layer = new RenderNode("navigation-backdrop");
    private final int bleed, color;

    Backdrop(Ui u) {
      color = u.bg;
      bleed = u.d(32);
      int radius = u.d(15);
      layer.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
    }

    void draw(Canvas canvas, View source, int left, int top, int width, int height, boolean dirty) {
      if (dirty || !layer.hasDisplayList()) {
        layer.setPosition(-bleed, -bleed, width + bleed, height + bleed);
        RecordingCanvas recording = layer.beginRecording();
        try {
          recording.drawColor(color);
          recording.translate(source.getLeft() - left + bleed, source.getTop() - top + bleed);
          source.draw(recording);
        } finally {
          layer.endRecording();
        }
      }
      canvas.drawRenderNode(layer);
    }
  }
}
