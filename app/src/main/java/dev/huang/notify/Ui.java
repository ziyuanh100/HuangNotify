package dev.huang.notify;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
  final Context context;
  final boolean dark;
  final int bg, surface, ink, muted, accent, pale, line;

  Ui(Context context) {
    this.context = context;
    dark =
        (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    bg = dark ? 0xff101b18 : 0xfff5f7f6;
    surface = dark ? 0xff1c2a25 : 0xffffffff;
    ink = dark ? 0xffedf5ef : 0xff172c24;
    muted = dark ? 0xffa8bdb2 : 0xff60746a;
    accent = dark ? 0xff9ee1c2 : 0xff146c59;
    pale = dark ? 0xff2c4439 : 0xffe4f1e8;
    line = dark ? 0xff34473e : 0xffe0e8e3;
  }

  int d(float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(d(radius));
    return g;
  }

  void ripple(View v, int color, int radius) {
    v.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(dark ? 0x22ffffff : 0x18146c59),
            shape(color, radius),
            shape(0xffffffff, radius)));
  }

  TextView text(String value, int size, int color, boolean bold) {
    TextView t = new TextView(context);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("tnum");
    if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(android.view.Gravity.CENTER_VERTICAL);
    return l;
  }

  TextView button(String text, boolean primary) {
    TextView b = text(text, 15, primary ? (dark ? 0xff123c2a : 0xffffffff) : accent, true);
    b.setGravity(android.view.Gravity.CENTER);
    b.setMinHeight(d(50));
    b.setPadding(d(16), d(10), d(16), d(10));
    ripple(b, primary ? accent : pale, 16);
    b.setFocusable(true);
    return b;
  }

  void gap(LinearLayout l, int height) {
    View v = new View(context);
    l.addView(v, new LinearLayout.LayoutParams(1, d(height)));
  }

  View icon(String name, int color) {
    return new Glyph(context, name, color);
  }

  final class Glyph extends View {
    final String name;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final int color;

    Glyph(Context c, String n, int col) {
      super(c);
      name = n;
      color = col;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas c) {
      super.onDraw(c);
      c.save();
      float scale = Math.min(getWidth(), getHeight()) / 24f;
      c.translate((getWidth() - 24 * scale) / 2, (getHeight() - 24 * scale) / 2);
      c.scale(scale, scale);
      p.setColor(color);
      p.setStrokeWidth(1.8f);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      path.reset();
      switch (name) {
        case "bell":
          path.moveTo(5, 17);
          path.lineTo(7, 14);
          path.lineTo(7, 9);
          path.cubicTo(7, 2, 17, 2, 17, 9);
          path.lineTo(17, 14);
          path.lineTo(19, 17);
          path.close();
          c.drawPath(path, p);
          c.drawArc(9, 17, 15, 22, 0, 180, false, p);
          break;
        case "star":
          path.moveTo(7, 3);
          path.lineTo(17, 3);
          path.lineTo(17, 21);
          path.lineTo(12, 17);
          path.lineTo(7, 21);
          path.close();
          c.drawPath(path, p);
          break;
        case "search":
          c.drawCircle(10, 10, 6, p);
          c.drawLine(14.5f, 14.5f, 21, 21, p);
          break;
        case "settings":
          c.drawCircle(12, 12, 4, p);
          for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45, 12, 12);
            c.drawLine(12, 2, 12, 5, p);
            c.restore();
          }
          c.drawCircle(12, 12, 8, p);
          break;
        case "backup":
          c.drawRoundRect(3, 9, 21, 21, 3, 3, p);
          c.drawLine(12, 15, 12, 2, p);
          c.drawLine(8, 6, 12, 2, p);
          c.drawLine(16, 6, 12, 2, p);
          break;
        case "check":
          path.moveTo(5, 12);
          path.lineTo(10, 17);
          path.lineTo(19, 7);
          c.drawPath(path, p);
          break;
        default:
          c.drawCircle(12, 12, 9, p);
          c.drawLine(12, 7, 12, 12, p);
          c.drawPoint(12, 16, p);
      }
      c.restore();
    }
  }
}
