package dev.huang.notify;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.CompoundButton;

/** An animated switch with a fixed touch target and native checkable semantics. */
public final class RecordSwitch extends CompoundButton {
  private final Ui u;
  private final boolean onHero;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path check = new Path();
  private final ArgbEvaluator colors = new ArgbEvaluator();
  private float position, destination;
  private ValueAnimator animation;

  public RecordSwitch(Context context) { this(context, false); }

  RecordSwitch(Context context, boolean onHero) {
    super(context);
    u = new Ui(context);
    this.onHero = onHero;
    setButtonDrawable(null);
    setBackground(null);
    setFocusable(true);
    setClickable(true);
  }

  @Override protected void onMeasure(int width, int height) {
    setMeasuredDimension(resolveSize(u.d(64), width), resolveSize(u.d(48), height));
  }

  @Override public void setChecked(boolean checked) {
    super.setChecked(checked);
    // A listener can restore the previous state while an asynchronous save is running.
    float target = isChecked() ? 1f : 0f;
    if (target == destination) return;
    destination = target;
    if (animation != null) animation.cancel();
    if (!isLaidOut() || !isShown() || !ValueAnimator.areAnimatorsEnabled()) {
      position = target;
      invalidate();
      return;
    }
    animation = ValueAnimator.ofFloat(position, target);
    animation.setDuration(190);
    animation.setInterpolator(new DecelerateInterpolator());
    animation.addUpdateListener(value -> {
      position = (float) value.getAnimatedValue();
      invalidate();
    });
    animation.start();
  }

  @Override public void setEnabled(boolean enabled) {
    if (isEnabled() == enabled) return;
    super.setEnabled(enabled);
    invalidate();
  }

  @Override protected void onDetachedFromWindow() {
    if (animation != null) animation.cancel();
    position = destination;
    super.onDetachedFromWindow();
  }

  @Override protected void onDraw(Canvas canvas) {
    float left = (getWidth() - u.d(50)) / 2f;
    float top = (getHeight() - u.d(30)) / 2f;
    float right = left + u.d(50), bottom = top + u.d(30);
    float radius = u.d(15);
    float centerY = (top + bottom) / 2f;
    float travel = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 1f - position : position;
    float centerX = left + u.d(15) + u.d(20) * travel;
    int active = onHero ? u.lime : u.accent;
    int offTrack = onHero ? u.heroLine : u.pale;
    int outline = onHero ? u.heroMuted : u.muted;
    int alpha = isEnabled() ? 255 : 138;

    paint.setStyle(Paint.Style.FILL);
    paint.setColor((int) colors.evaluate(position, offTrack, active));
    paint.setAlpha(alpha);
    canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);

    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(u.d(1.5f));
    paint.setColor(outline);
    paint.setAlpha((int) (alpha * .75f * (1f - position)));
    canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);

    paint.setStyle(Paint.Style.FILL);
    int offThumb = onHero ? u.heroInk : u.muted;
    int onThumb = onHero ? u.hero : u.onAccent;
    paint.setColor((int) colors.evaluate(position, offThumb, onThumb));
    paint.setAlpha(alpha);
    canvas.drawCircle(centerX, centerY, u.d(10.5f), paint);

    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(u.d(1.6f));
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setColor(onHero ? u.hero : u.surface);
    paint.setAlpha((int) (alpha * (1f - position)));
    canvas.drawLine(centerX - u.d(3), centerY, centerX + u.d(3), centerY, paint);
    paint.setColor(active);
    paint.setAlpha((int) (alpha * position));
    check.rewind();
    check.moveTo(centerX - u.d(4), centerY);
    check.lineTo(centerX - u.d(1), centerY + u.d(3));
    check.lineTo(centerX + u.d(4), centerY - u.d(3));
    canvas.drawPath(check, paint);

    if (isFocused() || isPressed()) {
      float inset = u.d(2);
      paint.setColor(active);
      paint.setAlpha(isPressed() ? 150 : 255);
      canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset,
          u.d(20), u.d(20), paint);
    }
  }

  @Override public CharSequence getAccessibilityClassName() { return "android.widget.Switch"; }

  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName(getAccessibilityClassName());
    info.setCheckable(true);
    info.setChecked(isChecked());
  }
}
