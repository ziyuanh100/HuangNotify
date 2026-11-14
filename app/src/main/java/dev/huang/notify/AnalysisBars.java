package dev.huang.notify;

import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** A compact plot with one focusable cell per date or hour. */
final class AnalysisBars extends LinearLayout {
  private final Ui u;
  private final IntConsumer onSelect;
  private final FrameLayout[] cells;
  private final int touchSlop;
  private float startX, startY;
  private boolean dragging;
  private int selected = -1;
  private TextView previous, next;

  AnalysisBars(Ui u, long[] values, long max, int height, String[] labels,
      String[] descriptions, int[] ticks, IntConsumer onSelect) {
    super(u.context);
    this.u = u;
    this.onSelect = onSelect;
    touchSlop = ViewConfiguration.get(u.context).getScaledTouchSlop();
    setOrientation(VERTICAL);
    setLayoutDirection(LAYOUT_DIRECTION_LTR);
    cells = new FrameLayout[values.length];
    LinearLayout plot = u.row();
    plot.setGravity(Gravity.BOTTOM);
    addView(plot, new LayoutParams(-1, u.d(height)));
    for (int i = 0; i < values.length; i++) {
      int index = i;
      FrameLayout cell = new FrameLayout(u.context);
      cell.setContentDescription(descriptions[i]);
      cell.setFocusable(true);
      cell.setOnClickListener(v -> select(index));
      cells[i] = cell;
      if (values[i] > 0) {
        View fill = new View(u.context);
        fill.setBackground(u.shape(u.accent, 3));
        int barHeight = Math.max(u.d(2), (int) Math.round(u.d(height - 2) * (values[i] / (double) max)));
        FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(-1, barHeight, Gravity.BOTTOM);
        int margin = u.d(values.length <= 7 ? 5 : 1);
        bar.setMargins(margin, 0, margin, 0);
        cell.addView(fill, bar);
      }
      plot.addView(cell, new LayoutParams(0, -1, 1));
    }

    View baseline = new View(u.context);
    baseline.setBackgroundColor(u.line);
    addView(baseline, new LayoutParams(-1, u.d(1)));
    FrameLayout axis = new FrameLayout(u.context) {
      @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
          View label = getChildAt(i);
          FrameLayout cell = cells[ticks[i]];
          int width = label.getMeasuredWidth();
          int center = cell.getLeft() + cell.getWidth() / 2;
          int x = Math.max(0, Math.min(getWidth() - width, center - width / 2));
          label.layout(x, 0, x + width, label.getMeasuredHeight());
        }
      }
    };
    axis.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    for (int tick : ticks) {
      TextView label = u.text(labels[tick], 11, u.muted, false);
      axis.addView(label, new FrameLayout.LayoutParams(-2, -2));
    }
    addView(axis, new LayoutParams(-1, -2));
  }

  @Override public boolean onInterceptTouchEvent(MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      startX = event.getX();
      startY = event.getY();
      dragging = false;
    } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
      float x = Math.abs(event.getX() - startX), y = Math.abs(event.getY() - startY);
      if (x > touchSlop && x > y) {
        dragging = true;
        getParent().requestDisallowInterceptTouchEvent(true);
        selectAt(event.getX());
        return true;
      }
    }
    return super.onInterceptTouchEvent(event);
  }

  @Override public boolean onTouchEvent(MotionEvent event) {
    if (!dragging) return super.onTouchEvent(event);
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_MOVE -> selectAt(event.getX());
      case MotionEvent.ACTION_UP -> {
        selectAt(event.getX());
        finishDrag();
      }
      case MotionEvent.ACTION_CANCEL -> finishDrag();
    }
    return true;
  }

  private void finishDrag() {
    dragging = false;
    getParent().requestDisallowInterceptTouchEvent(false);
  }

  private void selectAt(float x) {
    for (int index = 0; index < cells.length - 1; index++) {
      if (x < cells[index].getRight()) {
        select(index);
        return;
      }
    }
    select(cells.length - 1);
  }

  LinearLayout selectionControls(TextView detail, String previousLabel, String nextLabel) {
    LinearLayout controls = u.row();
    previous = step("‹", previousLabel, -1);
    next = step("›", nextLabel, 1);
    controls.addView(previous, new LayoutParams(u.d(48), -2));
    detail.setGravity(Gravity.CENTER);
    detail.setPadding(u.d(4), 0, u.d(4), 0);
    controls.addView(detail, new LayoutParams(0, -2, 1));
    controls.addView(next, new LayoutParams(u.d(48), -2));
    updateSteps();
    return controls;
  }

  private TextView step(String glyph, String label, int delta) {
    TextView button = u.button(glyph, false);
    button.setTextSize(23);
    button.setMinHeight(u.d(48));
    button.setPadding(0, 0, 0, 0);
    button.setContentDescription(label);
    button.setOnClickListener(v -> select(selected + delta));
    return button;
  }

  void select(int index) {
    if (index < 0 || index >= cells.length || selected == index) return;
    if (selected != -1) setSelected(cells[selected], false);
    selected = index;
    setSelected(cells[selected], true);
    updateSteps();
    onSelect.accept(index);
  }

  private void updateSteps() {
    if (previous == null) return;
    previous.setEnabled(selected > 0);
    next.setEnabled(selected >= 0 && selected < cells.length - 1);
  }

  private void setSelected(View cell, boolean active) {
    cell.setSelected(active);
    cell.setBackground(active ? u.outline(u.pale, 3) : null);
  }
}
