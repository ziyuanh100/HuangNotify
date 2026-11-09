package dev.huang.notify;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
  final Context context;
  final boolean dark;
  final int bg, surface, ink, muted, accent, onAccent, pale, line, danger;
  final int hero, heroInk, heroMuted, heroLine, lime, warning;
  private final LruCache<String, Drawable.ConstantState> appIcons = new LruCache<>(48);

  Ui(Context context) {
    this.context = context;
    dark =
        (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    bg = dark ? 0xff111c19 : 0xfff5f7f2;
    surface = dark ? 0xff1b2a25 : 0xffffffff;
    ink = dark ? 0xffeef5ee : 0xff192b23;
    muted = dark ? 0xffb0c3b8 : 0xff53665b;
    accent = dark ? 0xffcde991 : 0xff235e48;
    onAccent = dark ? 0xff1b321f : 0xffffffff;
    pale = dark ? 0xff2a4036 : 0xffe6efe2;
    line = dark ? 0xff354b40 : 0xffdce6dc;
    danger = dark ? 0xffffb4a9 : 0xff9f3933;
    hero = dark ? 0xff233f34 : 0xff1b4236;
    heroInk = 0xfff7fff3;
    heroMuted = 0xffccded0;
    heroLine = 0xff537267;
    lime = 0xffd8ef9c;
    warning = 0xfff6d38d;
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

  GradientDrawable outline(int color, int radius) {
    GradientDrawable g = shape(color, radius);
    g.setStroke(d(1), line);
    return g;
  }

  void ripple(View v, int color, int radius) {
    v.setBackground(rippleDrawable(shape(color, radius), radius, color == hero));
  }

  RippleDrawable rippleDrawable(Drawable content, int radius, boolean onHero) {
    return new RippleDrawable(
        ColorStateList.valueOf(dark || onHero ? 0x26ffffff : 0x20235e48), content,
        shape(Color.WHITE, radius));
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

  EditText field(String hint) {
    EditText field = new EditText(context);
    field.setTextColor(ink);
    field.setHintTextColor(muted);
    field.setHint(hint);
    field.setTextSize(15);
    field.setBackground(outline(bg, 14));
    field.setPadding(d(14), d(10), d(14), d(10));
    field.setMinHeight(d(52));
    return field;
  }

  TextView button(String text, boolean primary) {
    TextView b = new TextView(context) {
      @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
      @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : .55f);
      }
    };
    b.setText(text);
    b.setTextSize(15);
    b.setTextColor(primary ? onAccent : accent);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setGravity(Gravity.CENTER);
    b.setMinHeight(d(50));
    b.setPadding(d(16), d(10), d(16), d(10));
    ripple(b, primary ? accent : pale, 16);
    b.setFocusable(true);
    return b;
  }

  void heading(View view) {
    if (android.os.Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
  }

  FrameLayout iconTile(String name, int color, int fill, int radius) {
    FrameLayout tile = new FrameLayout(context);
    tile.setBackground(shape(fill, radius));
    tile.addView(icon(name, color), new FrameLayout.LayoutParams(d(22), d(22), Gravity.CENTER));
    return tile;
  }

  View iconButton(String name, String description, int color, Runnable action) {
    FrameLayout button = new FrameLayout(context) {
      @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
    };
    button.addView(icon(name, color), new FrameLayout.LayoutParams(d(22), d(22), Gravity.CENTER));
    ripple(button, Color.TRANSPARENT, 18);
    button.setContentDescription(description);
    button.setFocusable(true);
    button.setOnClickListener(v -> action.run());
    return button;
  }

  Drawable appIcon(String pkg) {
    Drawable.ConstantState cached = appIcons.get(pkg);
    if (cached != null) return cached.newDrawable(context.getResources());
    android.content.pm.PackageManager manager = context.getPackageManager();
    try {
      Drawable icon = manager.getApplicationIcon(pkg);
      if (icon.getConstantState() != null) appIcons.put(pkg, icon.getConstantState());
      return icon;
    } catch (android.content.pm.PackageManager.NameNotFoundException | SecurityException e) {
      return manager.getDefaultActivityIcon();
    }
  }

  void gap(LinearLayout l, int height) {
    View v = new View(context);
    l.addView(v, new LinearLayout.LayoutParams(1, d(height)));
  }

  Glyph icon(String name, int color) {
    return new Glyph(context, name, color);
  }

  final class Glyph extends View {
    final String name;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    private int color;

    Glyph(Context c, String n, int col) {
      super(c);
      name = n;
      color = col;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setColor(int value) {
      if (color == value) return;
      color = value;
      invalidate();
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
        case "starFilled":
          path.moveTo(7, 3);
          path.lineTo(17, 3);
          path.lineTo(17, 21);
          path.lineTo(12, 17);
          path.lineTo(7, 21);
          path.close();
          if (name.equals("starFilled")) p.setStyle(Paint.Style.FILL);
          c.drawPath(path, p);
          break;
        case "search":
          c.drawCircle(10, 10, 6, p);
          c.drawLine(14.5f, 14.5f, 21, 21, p);
          break;
        case "close":
          c.drawLine(6, 6, 18, 18, p);
          c.drawLine(18, 6, 6, 18, p);
          break;
        case "next":
          c.drawLine(9, 6, 15, 12, p);
          c.drawLine(15, 12, 9, 18, p);
          break;
        case "lock":
          c.drawRoundRect(5, 10, 19, 21, 3, 3, p);
          c.drawArc(8, 3, 16, 13, 180, 180, false, p);
          c.drawLine(8, 8, 8, 10, p);
          c.drawLine(16, 8, 16, 10, p);
          c.drawLine(12, 14, 12, 17, p);
          break;
        case "image":
          c.drawRoundRect(3, 4, 21, 20, 3, 3, p);
          c.drawCircle(8.5f, 9.5f, 1.5f, p);
          path.moveTo(5, 18); path.lineTo(13, 11); path.lineTo(19, 17);
          c.drawPath(path, p);
          break;
        case "clock":
          c.drawCircle(12, 12, 8.5f, p);
          path.moveTo(12, 7); path.lineTo(12, 12); path.lineTo(15, 14);
          c.drawPath(path, p);
          break;
        case "apps":
          c.drawRoundRect(4, 4, 10, 10, 1.5f, 1.5f, p);
          c.drawRoundRect(14, 4, 20, 10, 1.5f, 1.5f, p);
          c.drawRoundRect(4, 14, 10, 20, 1.5f, 1.5f, p);
          c.drawRoundRect(14, 14, 20, 20, 1.5f, 1.5f, p);
          break;
        case "trash":
          c.drawLine(4, 6, 20, 6, p);
          c.drawLine(9, 3, 15, 3, p);
          path.moveTo(6, 6); path.lineTo(7.5f, 21); path.lineTo(16.5f, 21); path.lineTo(18, 6);
          c.drawPath(path, p);
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
