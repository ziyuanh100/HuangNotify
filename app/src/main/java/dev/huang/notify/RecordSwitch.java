package dev.huang.notify;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;

/** Checkable native control with an app-owned track and a 48dp touch target. */
public final class RecordSwitch extends CompoundButton {
  private final Ui u;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  public RecordSwitch(Context context) {
    super(context); u = new Ui(context); setButtonDrawable(null);
    setBackground(null); setFocusable(true); setClickable(true);
  }
  @Override protected void onMeasure(int w, int h) { setMeasuredDimension(resolveSize(u.d(64), w), resolveSize(u.d(48), h)); }
  @Override public void setChecked(boolean value) { super.setChecked(value); invalidate(); }
  @Override protected void onDraw(Canvas canvas) {
    float x = (getWidth() - u.d(52)) / 2f, y = (getHeight() - u.d(30)) / 2f;
    paint.setAlpha(isEnabled() ? 255 : 100); paint.setColor(isChecked() ? u.accent : u.line);
    canvas.drawRoundRect(x, y, x + u.d(52), y + u.d(30), u.d(15), u.d(15), paint);
    paint.setColor(isChecked() ? (u.dark ? 0xff123c2a : 0xffffffff) : u.muted);
    canvas.drawCircle(x + u.d(isChecked() ? 37 : 15), y + u.d(15), u.d(11), paint);
    if (isFocused() || isPressed()) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(u.d(2)); paint.setColor(u.accent);
      canvas.drawRoundRect(1, 1, getWidth()-1, getHeight()-1, u.d(18), u.d(18), paint); paint.setStyle(Paint.Style.FILL); }
  }
  @Override public CharSequence getAccessibilityClassName() { return "android.widget.Switch"; }
  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.Switch"); info.setCheckable(true); info.setChecked(isChecked());
  }
}
