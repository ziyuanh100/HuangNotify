package dev.huang.notify;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/** Selects a source from the apps with retained records, independent of the current date range. */
final class AnalysisAppPicker {
  private final Activity owner;
  private final Ui u;
  private final AppDialog dialog;
  private final EditText search;
  private final TextView empty;
  private final String selected;
  private final BiConsumer<String, String> onSelect;
  private final List<String[]> matches = new ArrayList<>();
  private final Rows rows = new Rows();
  private List<String[]> sources = Collections.emptyList();

  AnalysisAppPicker(Activity owner, Ui u, String selected, BiConsumer<String, String> onSelect) {
    this.owner = owner;
    this.u = u;
    this.selected = selected;
    this.onSelect = onSelect;
    LinearLayout body = u.column();
    body.setFocusableInTouchMode(true);
    search = u.field("搜尋 App 名稱或套件名稱");
    search.setSingleLine(true);
    body.addView(search, new LinearLayout.LayoutParams(-1, -2));
    u.gap(body, 8);
    AppDialog.Toggle all = new AppDialog.Toggle(owner, true);
    all.setText("全部 App");
    all.setChecked(selected == null);
    all.setTag("analysis_picker_all");
    all.setOnClickListener(v -> select(null, null));
    body.addView(all, new LinearLayout.LayoutParams(-1, -2));
    u.gap(body, 8);
    ListView list = new ListView(owner);
    list.setDividerHeight(u.d(6));
    list.setItemsCanFocus(true);
    list.setAdapter(rows);
    body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    empty = u.text("正在讀取 App…", 14, u.muted, false);
    empty.setPadding(0, u.d(8), 0, u.d(8));
    body.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    list.setEmptyView(empty);
    dialog = new AppDialog.Builder(owner).setTitle("依 App 篩選").setExpandedView(body)
        .setNegativeButton("取消", null).create();
    search.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
        filter();
      }
      @Override public void afterTextChanged(Editable text) {}
    });
    body.requestFocus();
  }

  void show() {
    dialog.show();
    Store.IO.execute(() -> {
      try {
        List<String[]> apps = Store.get(owner).apps();
        apps.removeIf(app -> "0".equals(app[2]));
        owner.runOnUiThread(() -> {
          if (!dialog.isShowing() || owner.isDestroyed()) return;
          sources = apps;
          filter();
        });
      } catch (RuntimeException e) {
        owner.runOnUiThread(() -> {
          if (dialog.isShowing()) empty.setText("無法讀取 App，請重新開啟篩選。");
        });
      }
    });
  }

  private void filter() {
    String term = search.getText().toString().trim().toLowerCase(Locale.ROOT);
    matches.clear();
    for (String[] app : sources) {
      if (name(app).toLowerCase(Locale.ROOT).contains(term)
          || app[0].toLowerCase(Locale.ROOT).contains(term)) matches.add(app);
    }
    empty.setText(term.isEmpty() ? "目前沒有已保存通知的 App" : "找不到符合的 App");
    rows.notifyDataSetChanged();
  }

  private void select(String pkg, String name) {
    dialog.dismiss();
    onSelect.accept(pkg, name);
  }

  private static String name(String[] app) {
    return app[1] == null || app[1].trim().isEmpty() ? app[0] : app[1];
  }

  private final class Rows extends BaseAdapter {
    @Override public int getCount() { return matches.size(); }
    @Override public String[] getItem(int position) { return matches.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
      AppDialog.Toggle row = recycled instanceof AppDialog.Toggle
          ? (AppDialog.Toggle) recycled : new AppDialog.Toggle(owner, true);
      String[] app = getItem(position);
      row.setText(name(app));
      row.setIcon(u.appIcon(app[0]));
      row.setChecked(app[0].equals(selected));
      row.setTag(app[0]);
      row.setOnClickListener(v -> select(app[0], name(app)));
      return row;
    }
  }
}
