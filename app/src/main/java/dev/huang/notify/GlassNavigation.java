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
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;

/** A capsule that refracts the page underneath while keeping the navigation controls sharp. */
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
  private LinearGradient glass, fallback, edge, reflection, selection, selectionEdge;
  private View pageContent;
  private boolean backdropDirty = true, recordingBackdrop;
  private int selected = -1;
  private float indicator, stretch;
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
    setElevation(u.d(8));
    setOutlineProvider(new ViewOutlineProvider() {
      @Override public void getOutline(View view, Outline outline) {
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
            Math.min(view.getWidth(), view.getHeight()) / 2f);
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
      int color = active ? u.accent : u.ink;
      tabs[i].setSelected(active);
      labels[i].setTextColor(color);
      icons[i].setColor(color);
    }
    if (animator != null) animator.cancel();
    if (!isLaidOut() || !isShown() || !ValueAnimator.areAnimatorsEnabled()) {
      indicator = selected;
      stretch = 0;
      invalidate();
      return;
    }
    animator = ValueAnimator.ofFloat(indicator, selected);
    animator.setDuration(320);
    animator.setInterpolator(new PathInterpolator(.25f, .75f, .3f, 1f));
    float startStretch = stretch;
    float peakStretch = u.d(9);
    animator.addUpdateListener(value -> {
      indicator = (float) value.getAnimatedValue();
      float progress = value.getAnimatedFraction();
      stretch = (startStretch + 4 * peakStretch * progress) * (1 - progress);
      invalidate();
    });
    animator.start();
  }

  @Override protected void onDetachedFromWindow() {
    if (animator != null) animator.cancel();
    indicator = selected;
    stretch = 0;
    super.onDetachedFromWindow();
  }

  @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    if (width <= 0 || height <= 0) return;
    float radius = Math.min(width, height) / 2f;
    capsule.set(0, 0, width, height);
    clip.reset();
    clip.addRoundRect(capsule, radius, radius, Path.Direction.CW);
    // The rim passes more of the refracted scene through than the area behind the labels.
    glass = gradient(height, u.dark
        ? new int[]{0x61345346, 0xc21c342a, 0xbc15281f, 0x6a294f40}
        : new int[]{0x69fbfff9, 0xa7f9fef8, 0xa1eef8f2, 0x75d6eee3},
        new float[]{0f, .19f, .79f, 1f});
    fallback = gradient(height, u.dark
        ? new int[]{0xe6325144, 0xef1c3028, 0xe6223f32}
        : new int[]{0xf5fcfff9, 0xeef1f9f3, 0xe8ddf0e6}, null);
    edge = gradient(height, u.dark
        ? new int[]{0xd7d7f5e6, 0x635b8f7a, 0xad9ed7c3}
        : new int[]{0xffffffff, 0x9781aa9a, 0xf5f3fffa}, null);
    reflection = new LinearGradient(0, 0, width * .72f, height, u.dark
        ? new int[]{0x92e6fff5, 0x00e6fff5, 0x74b3e3ce}
        : new int[]{0xe8ffffff, 0x00ffffff, 0xc6ffffff},
        new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP);
    selection = gradient(height, u.dark
        ? new int[]{0xaa72945c, 0x8a355a43, 0xac3d684d}
        : new int[]{0xbdeaf8ce, 0xa8d8edcf, 0xb4bfe2cf}, null);
    selectionEdge = gradient(height, u.dark
        ? new int[]{0x9fdff7be, 0x38668561, 0x7893d3b0}
        : new int[]{0xe8ffffff, 0x5985b59c, 0xb7f6fff9}, null);
    if (backdrop != null) backdrop.resize(width, height);
    backdropDirty = true;
  }

  @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    if (changed) backdropDirty = true;
  }

  private static LinearGradient gradient(int height, int[] colors, float[] stops) {
    return new LinearGradient(0, 0, 0, height, colors, stops, Shader.TileMode.CLAMP);
  }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (glass == null) return;
    float radius = Math.min(getWidth(), getHeight()) / 2f;
    boolean live = backdrop != null && canvas.isHardwareAccelerated()
        && pageContent != null && pageContent.isLaidOut();
    if (live) {
      int save = canvas.save();
      canvas.clipPath(clip);
      recordingBackdrop = true;
      try {
        backdrop.draw(canvas, pageContent, getLeft(), getTop(), backdropDirty);
        backdropDirty = false;
      } finally {
        recordingBackdrop = false;
        canvas.restoreToCount(save);
      }
    }
    paint.setStyle(Paint.Style.FILL);
    paint.setShader(live ? glass : fallback);
    canvas.drawRoundRect(capsule, radius, radius, paint);

    int from = Math.min((int) indicator, tabs.length - 1);
    int to = Math.min(from + 1, tabs.length - 1);
    float progress = indicator - from;
    float inset = u.d(5);
    float left = Math.max(inset, interpolate(tabs[from].getLeft(), tabs[to].getLeft(), progress)
        + u.d(2) - stretch);
    float right = Math.min(getWidth() - inset, interpolate(tabs[from].getRight(), tabs[to].getRight(), progress)
        - u.d(2) + stretch);
    float innerRadius = (getHeight() - inset * 2) / 2f;
    paint.setShader(selection);
    canvas.drawRoundRect(left, inset, right, getHeight() - inset, innerRadius, innerRadius, paint);

    float rim = u.d(1) / 2f;
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(u.d(1));
    paint.setShader(selectionEdge);
    canvas.drawRoundRect(left, inset, right, getHeight() - inset, innerRadius, innerRadius, paint);
    drawRim(canvas, rim, radius, edge);
    drawRim(canvas, rim + u.d(1.5f), radius, reflection);
    paint.setShader(null);
  }

  private void drawRim(Canvas canvas, float inset, float radius, Shader shader) {
    paint.setShader(shader);
    float corner = Math.max(0f, radius - inset);
    canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, corner, corner, paint);
  }

  private static float interpolate(float from, float to, float progress) {
    return from + (to - from) * progress;
  }

  @TargetApi(31)
  private static final class Backdrop {
    private final RenderNode layer = new RenderNode("navigation-backdrop");
    private final int bleed, color;
    private final RenderEffect blur;
    private final Lens lens;

    Backdrop(Ui u) {
      color = u.bg;
      bleed = u.d(28);
      int radius = u.d(8);
      blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP);
      Lens supportedLens = null;
      if (Build.VERSION.SDK_INT >= 33) {
        try {
          supportedLens = new Lens(u, bleed);
        } catch (IllegalArgumentException error) {
          Log.w("HuangNotify", "Navigation refraction unavailable", error);
        }
      }
      lens = supportedLens;
    }

    void resize(int width, int height) {
      layer.setPosition(-bleed, -bleed, width + bleed, height + bleed);
      layer.setRenderEffect(Build.VERSION.SDK_INT >= 33 && lens != null ? lens.effect(width, height) : blur);
    }

    void draw(Canvas canvas, View source, int left, int top, boolean dirty) {
      if (dirty || !layer.hasDisplayList()) {
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

  @TargetApi(33)
  private static final class Lens {
    private final RuntimeShader shader = new RuntimeShader("""
        uniform shader scene;
        uniform float2 size;
        uniform float padding;
        uniform float band;
        uniform float bend;

        half4 main(float2 point) {
          float2 center = point - float2(padding) - size * 0.5;
          float2 spine = max((size - size.yx) * 0.5, float2(0.0));
          float2 radial = center - clamp(center, -spine, spine);
          float lengthFromSpine = length(radial);
          float edgeDepth = min(size.x, size.y) * 0.5 - lengthFromSpine;
          float rim = 1.0 - smoothstep(0.0, band, edgeDepth);
          // Sample toward the center of the capsule to bend detail at the curved rim.
          float2 normal = radial / max(lengthFromSpine, 1.0);
          return scene.eval(point - normal * bend * rim);
        }
        """);
    private final RenderEffect softness;

    Lens(Ui u, int bleed) {
      shader.setFloatUniform("padding", bleed);
      shader.setFloatUniform("band", u.d(24));
      shader.setFloatUniform("bend", u.d(14));
      int radius = u.d(4);
      softness = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP);
    }

    RenderEffect effect(int width, int height) {
      shader.setFloatUniform("size", width, height);
      return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "scene"), softness);
    }
  }
}
