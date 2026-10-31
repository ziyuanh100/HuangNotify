package dev.huang.notify;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Checkable;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** App-owned modal sheet. System permission and document pickers remain OS-owned. */
public final class AppDialog extends Dialog {
  private static final WeakHashMap<Context, List<AppDialog>> OPEN = new WeakHashMap<>();
  private final Builder spec;
  private final Ui u;
  private final android.util.SparseArray<TextView> buttons = new android.util.SparseArray<>();
  private AppDialog(Builder spec) { super(spec.context); this.spec = spec; u = new Ui(spec.context); }
  static boolean hasOpen(Context context) {
    List<AppDialog> dialogs=OPEN.get(context);
    if (dialogs!=null) for(AppDialog dialog:dialogs) if(dialog.isShowing()) return true;
    return false;
  }
  public static void dismissAll(Context context) {
    List<AppDialog> dialogs = OPEN.remove(context);
    if (dialogs != null) for (AppDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
  }
  @Override public void dismiss() {
    super.dismiss();
    List<AppDialog> dialogs = OPEN.get(spec.context);
    if (dialogs != null) { dialogs.remove(this); if (dialogs.isEmpty()) OPEN.remove(spec.context); }
  }
  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout card = u.column();
    card.setPadding(u.d(20), u.d(12), u.d(20), u.d(20));
    card.setBackground(u.shape(u.surface, 28));
    View handle = new View(getContext()); handle.setBackground(u.shape(u.line, 3));
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(u.d(36), u.d(4));
    hp.gravity = Gravity.CENTER_HORIZONTAL; hp.bottomMargin = u.d(20); card.addView(handle, hp);
    TextView title = u.text(spec.title, 22, u.ink, true);
    if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
    card.addView(title); u.gap(card, 14);
    if (spec.message != null) {
      TextView message = u.text(spec.message, 15, u.muted, false); message.setLineSpacing(u.d(4), 1);
      card.addView(message); u.gap(card, 16);
    }
    if (spec.view != null) card.addView(spec.view, new LinearLayout.LayoutParams(-1, -2));
    if (spec.items != null) {
      List<Toggle> choices = new ArrayList<>();
      for (int i = 0; i < spec.items.length; i++) {
        final int index = i;
        Toggle choice = new Toggle(getContext(), spec.multi == null);
        choice.setText(spec.items[i]);
        choice.setChecked(spec.multi == null ? spec.selected == i : spec.multi[i]);
        choice.setOnClickListener(v -> {
          if (spec.multi != null) {
            choice.toggle(); spec.multi[index] = choice.isChecked();
            if (spec.multiListener != null) spec.multiListener.onClick(this, index, choice.isChecked());
          } else {
            for (int j = 0; j < choices.size(); j++) choices.get(j).setChecked(j == index);
            if (spec.itemListener != null) spec.itemListener.onClick(this, index);
          }
        });
        choices.add(choice); card.addView(choice, new LinearLayout.LayoutParams(-1, -2)); u.gap(card, 8);
      }
    }
    if (spec.positive == null && spec.negative == null && spec.neutral == null) spec.negative = "關閉";
    u.gap(card, 14);
    action(card, BUTTON_POSITIVE, spec.positive, spec.positiveListener, true);
    action(card, BUTTON_NEUTRAL, spec.neutral, spec.neutralListener, false);
    action(card, BUTTON_NEGATIVE, spec.negative, spec.negativeListener, false);
    ScrollView scroll = new ScrollView(getContext()) {
      @Override protected void onMeasure(int width, int height) {
        int cap = (int) (getResources().getDisplayMetrics().heightPixels * .88);
        if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED) cap = Math.min(cap, MeasureSpec.getSize(height));
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
      }
    };
    scroll.setFillViewport(false); scroll.setClipToOutline(true); scroll.setBackground(u.shape(u.surface, 28));
    scroll.addView(card); setContentView(scroll);
    setCanceledOnTouchOutside(true);
    Window window = getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(.4f);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
  }
  @Override public void show() {
    if (spec.context instanceof Activity && ((Activity) spec.context).isFinishing()) return;
    super.show();
    OPEN.computeIfAbsent(spec.context, k -> new ArrayList<>()).add(this);
    Window window = getWindow();
    if (window != null) {
      int width = Math.min(u.d(560), getContext().getResources().getDisplayMetrics().widthPixels - u.d(24));
      window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
      WindowManager.LayoutParams p = window.getAttributes(); p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; p.y = u.d(12); window.setAttributes(p);
    }
  }
  private void action(LinearLayout body, int which, String label, DialogInterface.OnClickListener listener, boolean primary) {
    if (label == null) return;
    TextView button = u.button(label, primary);
    if (primary && (label.equals("刪除") || label.equals("清除"))) {
      u.ripple(button, u.dark ? 0xffe7a09b : 0xffa73532, 16);
      button.setTextColor(u.dark ? 0xff35100e : Color.WHITE);
    }
    button.setOnClickListener(v -> { dismiss(); if (listener != null) listener.onClick(this, which); });
    body.addView(button, new LinearLayout.LayoutParams(-1, -2)); u.gap(body, 8); buttons.put(which, button);
  }
  public TextView getButton(int which) { return buttons.get(which); }

  public static final class Builder {
    final Context context;
    String title = "", message, positive, negative, neutral;
    View view; String[] items; int selected; boolean[] multi;
    DialogInterface.OnClickListener positiveListener, negativeListener, neutralListener, itemListener;
    DialogInterface.OnMultiChoiceClickListener multiListener;
    public Builder(Context context) { this.context = context; }
    public Builder setTitle(String value) { title = value; return this; }
    public Builder setMessage(String value) { message = value; return this; }
    public Builder setView(View value) { view = value; return this; }
    public Builder setPositiveButton(String label, DialogInterface.OnClickListener action) { positive = label; positiveListener = action; return this; }
    public Builder setNegativeButton(String label, DialogInterface.OnClickListener action) { negative = label; negativeListener = action; return this; }
    public Builder setNeutralButton(String label, DialogInterface.OnClickListener action) { neutral = label; neutralListener = action; return this; }
    public Builder setSingleChoiceItems(String[] values, int choice, DialogInterface.OnClickListener listener) { items = values; selected = choice; itemListener = listener; return this; }
    public Builder setMultiChoiceItems(String[] values, boolean[] checked, DialogInterface.OnMultiChoiceClickListener listener) { items = values; multi = checked.clone(); multiListener = listener; return this; }
    public AppDialog create() { return new AppDialog(this); }
    public AppDialog show() { AppDialog d = create(); d.show(); return d; }
  }

  /** Custom checkable card with native accessibility semantics. */
  public static final class Toggle extends LinearLayout implements Checkable {
    private final Ui u;
    private final TextView label, mark;
    private boolean checked;
    private final boolean radio;
    public Toggle(Context context) { this(context, false); }
    Toggle(Context context, boolean radio) {
      super(context); this.radio = radio; u = new Ui(context);
      setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL); setMinimumHeight(u.d(56));
      setPadding(u.d(14), u.d(14), u.d(14), u.d(14)); setFocusable(true); setClickable(true);
      label = u.text("", 15, u.ink, false); label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      addView(label, new LayoutParams(0, -2, 1));
      mark = u.text("", 20, u.accent, true); mark.setGravity(Gravity.CENTER); mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      addView(mark, new LayoutParams(u.d(32), u.d(32)));
      setOnClickListener(v -> toggle()); setChecked(false);
    }
    public void setText(String text) { label.setText(text); setContentDescription(text); }
    public void setTextColor(int color) { label.setTextColor(color); }
    @Override public boolean isChecked() { return checked; }
    @Override public void toggle() { setChecked(!checked); }
    @Override public void setChecked(boolean value) {
      checked = value; mark.setText(value ? "✓" : radio ? "○" : "□");
      u.ripple(this, value ? u.pale : u.bg, 16);
      sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
      super.onInitializeAccessibilityNodeInfo(info);
      info.setClassName(radio ? "android.widget.RadioButton" : "android.widget.CheckBox");
      info.setCheckable(true); info.setChecked(checked);
    }
  }
}
