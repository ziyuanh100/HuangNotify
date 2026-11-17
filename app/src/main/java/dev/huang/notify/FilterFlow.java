package dev.huang.notify;

import android.view.View;
import android.view.ViewGroup;

/** Wrapping filters keep every option discoverable, including with enlarged system text. */
final class FilterFlow extends ViewGroup {
  private final int gap;

  FilterFlow(Ui ui) {
    super(ui.context);
    gap = ui.d(6);
  }

  @Override protected void onMeasure(int widthSpec, int heightSpec) {
    int width = MeasureSpec.getSize(widthSpec);
    int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
    int x = 0, y = 0, line = 0;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() == GONE) continue;
      child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
      if (x > 0 && x + child.getMeasuredWidth() > available) {
        y += line + gap;
        x = line = 0;
      }
      x += child.getMeasuredWidth() + gap;
      line = Math.max(line, child.getMeasuredHeight());
    }
    setMeasuredDimension(resolveSize(width, widthSpec),
        resolveSize(y + line + getPaddingTop() + getPaddingBottom(), heightSpec));
  }

  @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
    int available = getWidth() - getPaddingLeft() - getPaddingRight();
    int x = 0, y = getPaddingTop(), line = 0;
    boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() == GONE) continue;
      int width = child.getMeasuredWidth(), height = child.getMeasuredHeight();
      if (x > 0 && x + width > available) {
        y += line + gap;
        x = line = 0;
      }
      int left = rtl ? getWidth() - getPaddingRight() - x - width : getPaddingLeft() + x;
      child.layout(left, y, left + width, y + height);
      x += width + gap;
      line = Math.max(line, height);
    }
  }

  @Override protected LayoutParams generateDefaultLayoutParams() {
    return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
  }
}
