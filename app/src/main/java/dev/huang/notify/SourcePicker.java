package dev.huang.notify;

import android.content.Context;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;

/** Searchable, recycled app rows; filtered bulk actions affect only the visible result set. */
final class SourcePicker {
  private static final String[] CATEGORIES = {"全部", "一般 App", "系統 App", "歷史來源", "已排除"};
  interface Save { void accept(Map<String,Boolean> changes); }
  final AppDialog dialog;
  final EditText search;
  final ListView list;
  private final Ui u;
  private final List<AppSources.Entry> all, visible=new ArrayList<>();
  private final Map<String,Boolean> selected=new HashMap<>();
  private final List<TextView> filters=new ArrayList<>();
  private final TextView summary, empty;
  private final Rows adapter=new Rows();
  private int category;

  SourcePicker(Context c,List<AppSources.Entry> entries,Save save) {
    u=new Ui(c); all=new ArrayList<>(entries);
    for(AppSources.Entry e:all) selected.put(e.pkg,e.allowed);
    LinearLayout body=u.column();
    search = u.field("搜尋 App 名稱或套件名稱");
    search.setSingleLine(true);
    search.setContentDescription("搜尋記錄來源");
    body.addView(search, new LinearLayout.LayoutParams(-1, -2));
    u.gap(body, 8);
    HorizontalScrollView categories=new HorizontalScrollView(c); categories.setHorizontalScrollBarEnabled(false);
    LinearLayout chips=u.row(); categories.addView(chips);
    for(int i=0;i<CATEGORIES.length;i++) {
      final int index=i; TextView chip=u.button(CATEGORIES[i],false); chip.setMinHeight(u.d(48));
      chip.setContentDescription("來源分類："+CATEGORIES[i]); chip.setOnClickListener(v->filter(index)); filters.add(chip);
      LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2); p.setMargins(0,0,u.d(6),0); chips.addView(chip,p);
    }
    body.addView(categories,new LinearLayout.LayoutParams(-1,-2));
    summary=u.text("",12,u.muted,false); summary.setPadding(0,u.d(8),0,u.d(8)); body.addView(summary);
    LinearLayout actions=u.row();
    TextView allow=u.button("全選目前結果",false), block=u.button("取消選取結果",false);
    allow.setOnClickListener(v->selectVisible(true)); block.setOnClickListener(v->selectVisible(false));
    actions.addView(allow,new LinearLayout.LayoutParams(0,-2,1));
    LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-2,1); second.leftMargin=u.d(6); actions.addView(block,second);
    body.addView(actions); u.gap(body,8);
    // Keep search, results and save reachable when the keyboard or large text reduces space.
    body.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
      int visibility=bottom-top<u.d(280)*c.getResources().getConfiguration().fontScale?View.GONE:View.VISIBLE;
      if(categories.getVisibility()!=visibility) { categories.setVisibility(visibility); actions.setVisibility(visibility); }
    });
    list=new ListView(c);
    android.graphics.drawable.ColorDrawable divider = new android.graphics.drawable.ColorDrawable(u.surface);
    list.setDivider(divider); list.setDividerHeight(u.d(5));
    list.setAdapter(adapter); list.setContentDescription("記錄來源 App 清單");
    list.setItemsCanFocus(true); body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    empty=u.text("找不到符合的 App，請換個名稱或分類。",15,u.muted,false); empty.setPadding(0,u.d(12),0,u.d(12)); body.addView(empty);
    dialog=new AppDialog.Builder(c).setTitle("選擇記錄來源").setExpandedView(body)
        .setPositiveButton("儲存選擇",(d,w)->save.accept(changes()))
        .setNegativeButton("取消",null)
        .setNeutralButton("說明",(d,w)->{}).create();
    dialog.setOnShowListener(d->{
      // The help sheet keeps unsaved selections intact.
      dialog.getButton(AppDialog.BUTTON_NEUTRAL).setOnClickListener(v->new AppDialog.Builder(c)
          .setTitle("哪些 App 可以記錄？")
          .setMessage("預設允許所有來源，不必等到收到第一則通知才設定。搜尋與分類只改變顯示清單；全選與取消選取只作用於目前結果，按「儲存選擇」才生效。\n\n顯示目前使用者下已安裝的 App（含系統與停用的 App），以及曾有紀錄或已排除的歷史來源。HuangNotify 自身不記錄。\n\n已停用的 App 不會發送通知；允許記錄不會開啟它的系統通知權限。工作設定檔、安全資料夾及其他使用者仍受 Android 隔離限制。\n\nApp 清單只用於本機來源選擇，不會上傳。若系統沒有送出通知、隱藏內容或限制背景連線，仍可能無法記錄。")
          .setPositiveButton("知道了",null).show());
    });
    dialog.setOnDismissListener(d->{ InputMethodManager keyboard=c.getSystemService(InputMethodManager.class); if(keyboard!=null) keyboard.hideSoftInputFromWindow(search.getWindowToken(),0); });
    search.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
      public void onTextChanged(CharSequence s,int start,int before,int count) { filter(category); }
      public void afterTextChanged(Editable value) { }
    });
    filter(0);
  }
  void show() { dialog.show(); }
  void filter(int value) {
    category=value; visible.clear(); String term=search.getText().toString().trim().toLowerCase(Locale.ROOT);
    for(AppSources.Entry e:all) {
      boolean match = switch (category) {
        case 1 -> e.installed && !e.system;
        case 2 -> e.installed && e.system;
        case 3 -> !e.installed;
        case 4 -> !selected.get(e.pkg);
        default -> true;
      };
      if (match && (e.label.toLowerCase(Locale.ROOT).contains(term)
          || e.pkg.toLowerCase(Locale.ROOT).contains(term))) visible.add(e);
    }
    for(int i=0;i<filters.size();i++) { TextView chip=filters.get(i); chip.setSelected(i==category); u.ripple(chip,i==category?u.pale:u.bg,16); }
    list.setVisibility(visible.isEmpty()?View.GONE:View.VISIBLE); empty.setVisibility(visible.isEmpty()?View.VISIBLE:View.GONE);
    empty.setText(category == 4 && term.isEmpty() ? "目前沒有排除的 App。" : "找不到符合的 App，請換個名稱或分類。");
    adapter.notifyDataSetChanged(); updateSummary();
  }
  void selectVisible(boolean allowed) {
    for (AppSources.Entry entry : visible) selected.put(entry.pkg, allowed);
    filter(category);
  }
  private void updateSummary() {
    int count=0; for(boolean value:selected.values()) if(value) count++;
    summary.setText(CATEGORIES[category]+" · 顯示 "+visible.size()+" / "+all.size()+" 個 · 允許記錄 "+count+" 個");
  }
  Map<String,Boolean> changes() {
    Map<String,Boolean> changes=new LinkedHashMap<>();
    for(AppSources.Entry e:all) if(selected.get(e.pkg)!=e.allowed) changes.put(e.pkg,selected.get(e.pkg));
    return changes;
  }
  private final class Rows extends BaseAdapter {
    public int getCount() { return visible.size(); }
    public AppSources.Entry getItem(int position) { return visible.get(position); }
    public long getItemId(int position) { return position; }
    public View getView(int position,View recycled,ViewGroup parent) {
      AppDialog.Toggle row=recycled instanceof AppDialog.Toggle?(AppDialog.Toggle)recycled:new AppDialog.Toggle(u.context);
      AppSources.Entry entry = getItem(position);
      row.setText(entry.label + "\n" + entry.description());
      row.setIcon(u.appIcon(entry.pkg));
      row.setChecked(selected.get(entry.pkg));
      row.setTag(entry.pkg);
      row.setOnClickListener(v -> {
        row.toggle();
        selected.put(entry.pkg, row.isChecked());
        if (category == 4) filter(category);
        else updateSummary();
      });
      return row;
    }
  }
}
