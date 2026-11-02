package dev.huang.notify;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.widget.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

/** Bounded local metadata. Guided tests observe delivery; they cannot infer absent source events. */
final class Diagnostics {
  private static WeakReference<Screen> current=new WeakReference<>(null);
  static void event(Context c,String code,String pkg){Store.IO.execute(()->record(c,code,pkg));}
  static void record(Context c,String code,String pkg){try{record(Store.get(c),code,pkg);}catch(Exception ignored){}}
  static void record(Store store,String code,String pkg){try{store.diagnostic(code,pkg);}catch(Exception ignored){}}
  static String label(String code){switch(code){
    case "connected":return "通知服務已連線";case "disconnected":return "通知服務已斷線";case "destroyed":return "通知服務已停止";
    case "received":return "系統已送達通知";case "replayed":return "重連補讀通知";case "saved":return "已保存通知";case "unchanged":return "內容相同，略過重複";
    case "paused":return "記錄已暫停";case "excluded":return "來源已排除";case "group_summary":return "群組摘要，已有子通知";
    case "parse_failed":return "通知格式無法讀取";case "write_failed":return "通知儲存失敗";case "image_unavailable":return "圖片無法讀取，保留文字";case "image_partial":return "部分圖片無法讀取";
    default:return "未知狀態";}}
  static JSONObject report(Store store,Context c)throws Exception {
    return new JSONObject().put("format","HuangNotify-diagnostics").put("version",2).put("app_version",BuildConfig.VERSION_NAME)
      .put("android_api",android.os.Build.VERSION.SDK_INT).put("notification_access",PermissionGuide.hasAccess(c)).put("connected",CaptureService.connected)
      .put("paused","1".equals(store.setting("paused","0"))).put("enabled",!"0".equals(store.setting("diagnostics","1")))
      .put("last_received",Long.parseLong(store.setting("diagnostic_received","0"))).put("last_saved",Long.parseLong(store.setting("diagnostic_saved","0")))
      .put("sequence",store.diagnosticSequence()).put("events",store.diagnosticEvents());
  }
  static String time(long millis){return millis==0?"尚無資料":new SimpleDateFormat("MM/dd HH:mm:ss",Locale.TAIWAN).format(new Date(millis));}
  static void resume(Activity a){Screen screen=current.get();if(screen!=null&&screen.a==a&&screen.dialog!=null&&screen.dialog.isShowing())screen.refresh();}
  static void show(Activity a){Screen s=new Screen(a);current=new WeakReference<>(s);s.open();}
  static boolean matches(JSONObject row,String pkg,long since,int result){
    if(!pkg.isEmpty()&&!pkg.equals(row.optString("package")))return false;if(row.optLong("time")<since)return false;
    String code=row.optString("code");switch(result){case 1:return code.equals("received")||code.equals("replayed");case 2:return code.equals("saved");case 3:return code.equals("paused")||code.equals("excluded");case 4:return code.endsWith("failed")||code.startsWith("image_");default:return true;}
  }
  static boolean truncated(JSONArray rows,long sequence){return rows.length()>0&&rows.optJSONObject(rows.length()-1).optLong("id")>sequence+1;}
  private static final class Screen {
    final Activity a;final Ui u;final SharedPreferences prefs;AppDialog dialog;TextView status,events,testButton,source,timeFilter,resultFilter;AppDialog.Toggle enabled;
    JSONObject data;String pkg="";int window,result,generation;boolean loading;
    final String[] windows={"所有保留紀錄","最近 5 分鐘","本次測試"},results={"全部結果","已收到","已保存","暫停／排除","失敗／圖片問題"};
    Screen(Activity a){this.a=a;u=new Ui(a);prefs=a.getSharedPreferences("diagnostic_test",0);pkg=prefs.getString("source","");window=prefs.getInt("window",0);result=prefs.getInt("result",0);}
    void open(){
      LinearLayout body=u.column();body.setBackgroundColor(u.surface);status=u.text("正在讀取狀態…",14,u.ink,false);body.addView(status);u.gap(body,12);
      enabled=new AppDialog.Toggle(a);enabled.setText("保留最近 200 筆本機診斷");enabled.setEnabled(false);body.addView(enabled);
      enabled.setOnClickListener(v->{enabled.toggle();boolean value=enabled.isChecked();enabled.setEnabled(false);Store.IO.execute(()->{try{Store s=Store.get(a);android.database.sqlite.SQLiteDatabase db=s.getWritableDatabase();db.beginTransaction();try{s.putSetting("diagnostics",value?"1":"0");if(!value)s.clearDiagnostics();db.setTransactionSuccessful();}finally{db.endTransaction();}a.runOnUiThread(()->{if(!value){prefs.edit().clear().apply();pkg="";window=0;result=0;}refresh();});}catch(Exception error){a.runOnUiThread(()->{toast("無法儲存診斷設定，請檢查手機空間後重試");refresh();});}});});
      body.addView(u.text("只記錄時間、處理序號、來源套件與狀態，不包含通知標題、內文、圖片或通知識別碼。關閉會清除紀錄。",12,u.muted,false));
      testButton=button(body,"開始通知測試",this::test);
      body.addView(u.text("開始測試後，請自行截圖或讓來源 App 發出通知；回到這裡會重新整理，也可手動重新整理。若沒有送達事件，不能直接判定來源沒有發通知。",12,u.muted,false));
      source=button(body,"全部來源 ▾",this::chooseSource);timeFilter=button(body,"時間範圍 ▾",()->choose("時間範圍",windows,window,i->{window=i;saveFilters();refresh();}));
      resultFilter=button(body,"處理結果 ▾",()->choose("處理結果",results,result,i->{result=i;saveFilters();refresh();}));
      button(body,"重新整理",this::refresh);button(body,"重新連線通知服務",()->{try{android.service.notification.NotificationListenerService.requestRebind(new ComponentName(a,CaptureService.class));toast("已要求重新連線，請稍後重新整理實際狀態");}catch(Exception e){toast("請先開啟通知存取權");}});
      button(body,"檢查來源與權限",()->new AppDialog.Builder(a).setTitle("漏記排查").setMessage("先確認上方通知存取權與連線狀態。若結果為暫停或排除，請回到設定開啟記錄或調整來源。若完全未觀察到送達事件，請確認來源 App 的系統通知設定與手機省電限制；浮動工具列可能沒有通知事件。Samsung 截圖仍需依實際結果判斷。").setPositiveButton("了解",null).show());
      events=u.text("",12,u.muted,false);body.addView(events);ScrollView scroll=new ScrollView(a);scroll.addView(body);
      dialog=new AppDialog.Builder(a).setTitle("通知診斷").setExpandedView(scroll).setPositiveButton("完成",null).setNeutralButton("匯出診斷",(d,w)->exportDialog(a)).setNegativeButton("清除診斷",(d,w)->{prefs.edit().clear().apply();Store.IO.execute(()->{try{Store.get(a).clearDiagnostics();}catch(Exception error){a.runOnUiThread(()->toast("診斷清除失敗，請稍後重試"));}});}).show();refresh();
    }
    TextView button(LinearLayout parent,String label,Runnable action){TextView b=u.button(label,false);parent.addView(b);u.gap(parent,6);b.setOnClickListener(v->action.run());return b;}
    void toast(String text){if(!a.isDestroyed())Toast.makeText(a,text,Toast.LENGTH_LONG).show();}
    interface Choice{void selected(int index);}
    void choose(String title,String[] items,int selected,Choice choice){new AppDialog.Builder(a).setTitle(title).setSingleChoiceItems(items,selected,(d,i)->{d.dismiss();choice.selected(i);}).setNegativeButton("取消",null).show();}
    void saveFilters(){prefs.edit().putString("source",pkg).putInt("window",window).putInt("result",result).apply();}
    void chooseSource(){TreeSet<String> sources=new TreeSet<>();if(!pkg.isEmpty())sources.add(pkg);if(data!=null){JSONArray rows=data.optJSONArray("events");for(int i=0;i<rows.length();i++){String p=rows.optJSONObject(i).optString("package");if(!p.isEmpty())sources.add(p);}}
      String[] items=new String[sources.size()+1];items[0]="全部來源";int i=1,selected=0;for(String p:sources){items[i]=p;if(p.equals(pkg))selected=i;i++;}choose("篩選已觀察到的來源",items,selected,n->{pkg=n==0?"":items[n];saveFilters();refresh();});}
    void test(){
      if(data==null||!data.optBoolean("enabled")){toast("請先手動開啟本機診斷再開始測試");return;}
      if(prefs.getLong("started",0)>0&&prefs.getLong("ended",0)==0){prefs.edit().putLong("ended",System.currentTimeMillis()).apply();refresh();return;}
      testButton.setEnabled(false);Store.IO.execute(()->{try{long sequence=Store.get(a).diagnosticSequence(),now=System.currentTimeMillis();a.runOnUiThread(()->{if(a.isDestroyed()||!dialog.isShowing())return;prefs.edit().putLong("started",now).remove("ended").putLong("sequence",sequence).apply();window=2;result=0;pkg="";saveFilters();refresh();toast("測試已開始，請操作截圖或來源 App，再回來查看");});}catch(Exception error){a.runOnUiThread(()->{toast("無法開始診斷測試，請稍後重試");refresh();});}});
    }
    void refresh(){
      if(a.isDestroyed()||dialog==null||!dialog.isShowing())return;int gen=++generation;
      Store.IO.execute(()->{try{JSONObject snapshot=report(Store.get(a),a);a.runOnUiThread(()->{if(a.isDestroyed()||!dialog.isShowing()||gen!=generation)return;data=snapshot;render();});}catch(Exception e){a.runOnUiThread(()->{if(dialog.isShowing())status.setText("無法讀取診斷資料，請稍後重試");});}});
    }
    void render(){
      status.setText("通知存取權："+(data.optBoolean("notification_access")?"已開啟":"未開啟")+"\n服務："+(data.optBoolean("connected")?"已連線":"未連線")+" · "+(data.optBoolean("paused")?"記錄暫停":"記錄已開啟")+"\n最近收到："+time(data.optLong("last_received"))+"\n最近保存："+time(data.optLong("last_saved")));
      enabled.setChecked(data.optBoolean("enabled"));enabled.setEnabled(true);long started=prefs.getLong("started",0),ended=prefs.getLong("ended",0),sequence=prefs.getLong("sequence",0);
      testButton.setText(started>0&&ended==0?"結束本次測試":"開始新的通知測試");testButton.setEnabled(data.optBoolean("enabled"));source.setText(pkg.isEmpty()?"全部來源 ▾":pkg+" ▾");timeFilter.setText(windows[window]+" ▾");resultFilter.setText(results[result]+" ▾");
      long since=window==1?System.currentTimeMillis()-300000:window==2?started:0;JSONArray rows=data.optJSONArray("events");StringBuilder text=new StringBuilder("\n處理狀態\n");int count=0,received=0;
      if(window==2&&started==0){events.setText("\n請先開始通知測試。測試不會自行變更來源或權限。");return;}
      boolean incomplete=window==2&&truncated(rows,sequence);if(incomplete)text.append("本次測試的較早紀錄已超過 200 筆上限，以下資料不完整。\n");
      if(window==2)text.append("測試開始：").append(time(started)).append(ended>0?"\n測試結束："+time(ended):" · 進行中").append("\n");
      for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(window==2&&(row.optLong("id")<=sequence||(ended>0&&row.optLong("time")>ended)))continue;if(!matches(row,pkg,since,0))continue;String code=row.optString("code");if(code.equals("received")||code.equals("replayed"))received++;if(!matches(row,pkg,since,result))continue;count++;text.append(time(row.optLong("time"))).append(" · ").append(label(code)).append("\n").append(row.optString("package")).append("\n\n");}
      if(window==2&&received==0)text.append("這段時間尚未觀察到符合來源的送達事件，請先檢查來源、權限與連線；不代表能判定來源沒有發通知。\n");if(count==0)text.append("沒有符合目前條件的診斷紀錄。\n");events.setText(text.toString());
    }
  }
  private static void exportDialog(Activity a){new AppDialog.Builder(a).setTitle("匯出診斷資料？").setMessage("包含 Android／App 版本、權限與連線狀態，以及最近 200 筆處理序號、時間、來源套件與結果，可能透露使用的 App。會匯出全部保留紀錄；請檢查後再自行分享，不自動上傳。").setNegativeButton("取消",null).setPositiveButton("選擇儲存位置",(d,w)->{try{a.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"HuangNotify_diagnostics.json"),104);}catch(Exception e){Toast.makeText(a,"無法開啟檔案選擇器",Toast.LENGTH_LONG).show();}}).show();}
  static void save(Activity a,Uri uri){if(!"content".equals(uri.getScheme()))return;Store.IO.execute(()->{try{byte[] bytes=report(Store.get(a),a).toString(2).getBytes(StandardCharsets.UTF_8);try(OutputStream out=a.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();out.write(bytes);}a.runOnUiThread(()->Toast.makeText(a,"診斷已匯出，請自行檢查後再分享",Toast.LENGTH_LONG).show());}catch(Exception e){a.runOnUiThread(()->Toast.makeText(a,"診斷匯出失敗，請重新選擇位置",Toast.LENGTH_LONG).show());}});}
}
