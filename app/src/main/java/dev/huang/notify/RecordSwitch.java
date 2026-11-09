package dev.huang.notify;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;

/** Checkable native control with an app-owned track and a 48dp touch target. */
public final class RecordSwitch extends CompoundButton {
  private final Ui u;
  private final boolean onHero;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  public RecordSwitch(Context context) { this(context, false); }
  RecordSwitch(Context context, boolean onHero) {
    super(context); u = new Ui(context); setButtonDrawable(null);
    this.onHero = onHero;
    setBackground(null); setFocusable(true); setClickable(true);
  }
  @Override protected void onMeasure(int w, int h) { setMeasuredDimension(resolveSize(u.d(64), w), resolveSize(u.d(48), h)); }
  @Override public void setChecked(boolean value) { super.setChecked(value); invalidate(); }
  @Override protected void onDraw(Canvas canvas) {
    float x = (getWidth() - u.d(52)) / 2f, y = (getHeight() - u.d(30)) / 2f;
    paint.setColor(isChecked() ? (onHero ? u.lime : u.accent) : (onHero ? u.heroLine : u.line));
    paint.setAlpha(isEnabled() ? 255 : 110);
    canvas.drawRoundRect(x, y, x + u.d(52), y + u.d(30), u.d(15), u.d(15), paint);
    paint.setColor(onHero ? (isChecked() ? u.hero : u.heroInk) : (isChecked() ? u.onAccent : u.muted));
    paint.setAlpha(isEnabled() ? 255 : 150);
    canvas.drawCircle(x + u.d(isChecked() ? 37 : 15), y + u.d(15), u.d(11), paint);
    if (isFocused() || isPressed()) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(u.d(2)); paint.setColor(onHero ? u.lime : u.accent);
      canvas.drawRoundRect(1, 1, getWidth()-1, getHeight()-1, u.d(18), u.d(18), paint); paint.setStyle(Paint.Style.FILL); }
  }
  @Override public CharSequence getAccessibilityClassName() { return "android.widget.Switch"; }
  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.Switch"); info.setCheckable(true); info.setChecked(isChecked());
  }
}
