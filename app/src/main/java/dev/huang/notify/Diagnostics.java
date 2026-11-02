package dev.huang.notify;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

/** Bounded, local operational metadata. Notification content and keys never enter this log. */
final class Diagnostics {
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
    return new JSONObject().put("format","HuangNotify-diagnostics").put("version",1).put("app_version",BuildConfig.VERSION_NAME)
      .put("android_api",android.os.Build.VERSION.SDK_INT).put("notification_access",PermissionGuide.hasAccess(c)).put("connected",CaptureService.connected)
      .put("paused","1".equals(store.setting("paused","0"))).put("enabled",!"0".equals(store.setting("diagnostics","1")))
      .put("last_received",Long.parseLong(store.setting("diagnostic_received","0"))).put("last_saved",Long.parseLong(store.setting("diagnostic_saved","0")))
      .put("events",store.diagnosticEvents());
  }
  static String time(long millis){return millis==0?"尚無資料":new SimpleDateFormat("MM/dd HH:mm:ss",Locale.TAIWAN).format(new Date(millis));}
  static void show(Activity a){Store.IO.execute(()->{try{
    JSONObject data=report(Store.get(a),a);a.runOnUiThread(()->{
      if(a.isDestroyed())return;Ui u=new Ui(a);LinearLayout body=u.column();StringBuilder text=new StringBuilder();
      text.append("通知存取權：").append(data.optBoolean("notification_access")?"已開啟":"未開啟").append("\n服務：").append(data.optBoolean("connected")?"已連線":"未連線")
        .append(" · ").append(data.optBoolean("paused")?"記錄暫停":"記錄中").append("\n最近收到：").append(time(data.optLong("last_received"))).append("\n最近保存：").append(time(data.optLong("last_saved")));
      body.addView(u.text(text.toString(),14,u.ink,false));u.gap(body,12);
      AppDialog.Toggle enabled=new AppDialog.Toggle(a);enabled.setText("保留最近 200 筆本機診斷");enabled.setChecked(data.optBoolean("enabled"));body.addView(enabled);
      enabled.setOnClickListener(v->{enabled.toggle();boolean value=enabled.isChecked();Store.IO.execute(()->{Store s=Store.get(a);s.putSetting("diagnostics",value?"1":"0");if(!value)s.clearDiagnostics();});});
      body.addView(u.text("只記錄時間、來源套件名稱及處理狀態，不包含通知標題、內文或圖片。關閉會清除診斷。沒有送達本 App 的通知無法出現在此處。",12,u.muted,false));
      TextView reconnect=u.button("重新連線通知服務",false);body.addView(reconnect);reconnect.setOnClickListener(v->{try{android.service.notification.NotificationListenerService.requestRebind(new ComponentName(a,CaptureService.class));Toast.makeText(a,"已要求重新連線，稍後重新開啟診斷查看",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(a,"請先開啟通知存取權",Toast.LENGTH_LONG).show();}});
      StringBuilder events=new StringBuilder("\n最近處理狀態\n");JSONArray rows=data.optJSONArray("events");for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);events.append(time(row.optLong("time"))).append(" · ").append(label(row.optString("code"))).append("\n").append(row.optString("package")).append("\n");}
      if(rows.length()==0)events.append("尚無診斷紀錄\n");body.addView(u.text(events.toString(),12,u.muted,false));
      ScrollView scroll=new ScrollView(a);scroll.addView(body);
      new AppDialog.Builder(a).setTitle("通知診斷").setExpandedView(scroll).setPositiveButton("完成",null).setNeutralButton("匯出診斷",(d,w)->
        new AppDialog.Builder(a).setTitle("匯出診斷資料？").setMessage("檔案包含 Android 版本、權限／連線狀態、時間與來源套件名稱，可能透露你使用的 App。請檢查後再自行分享；不會自動上傳。").setNegativeButton("取消",null).setPositiveButton("選擇儲存位置",(dd,ww)->{
          try{a.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"HuangNotify_diagnostics.json"),104);}catch(Exception e){Toast.makeText(a,"無法開啟檔案選擇器",Toast.LENGTH_LONG).show();}}).show())
        .setNegativeButton("清除診斷",(d,w)->Store.IO.execute(()->Store.get(a).clearDiagnostics())).show();
    });
  }catch(Exception e){a.runOnUiThread(()->Toast.makeText(a,"無法讀取診斷資料",Toast.LENGTH_LONG).show());}});}
  static void save(Activity a,Uri uri){if(!"content".equals(uri.getScheme()))return;Store.IO.execute(()->{try{
    byte[] bytes=report(Store.get(a),a).toString(2).getBytes(StandardCharsets.UTF_8);
    try(OutputStream out=a.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();out.write(bytes);}
    a.runOnUiThread(()->Toast.makeText(a,"診斷已匯出，請自行檢查後再分享",Toast.LENGTH_LONG).show());
  }catch(Exception e){a.runOnUiThread(()->Toast.makeText(a,"診斷匯出失敗，請重新選擇位置",Toast.LENGTH_LONG).show());}});}
}
