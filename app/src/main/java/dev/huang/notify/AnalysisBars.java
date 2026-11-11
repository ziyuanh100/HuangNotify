package dev.huang.notify;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** A compact plot with one focusable cell per date or hour. */
final class AnalysisBars extends LinearLayout {
  private final Ui u;
  private final FrameLayout[] cells;
  private int selected = -1;

  AnalysisBars(Ui u, long[] values, long max, int height, String[] labels,
      String[] descriptions, int[] ticks, IntConsumer onSelect) {
    super(u.context);
    this.u = u;
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
      cell.setOnClickListener(v -> {
        select(index);
        onSelect.accept(index);
      });
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

  void select(int index) {
    if (selected != -1) setSelected(cells[selected], false);
    selected = index;
    setSelected(cells[selected], true);
  }

  private void setSelected(View cell, boolean active) {
    cell.setSelected(active);
    cell.setBackground(active ? u.outline(u.pale, 3) : null);
  }
}
