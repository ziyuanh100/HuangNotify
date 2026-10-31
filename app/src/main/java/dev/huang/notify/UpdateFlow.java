package dev.huang.notify;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;

/** Foreground update offers and a continuous in-app download/install UI. */
final class UpdateFlow {
  private final Activity activity;
  private final Ui u;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private boolean entered,resumed,working,destroyed;
  private int epoch, confirmationSession=-1;
  private UpdateChecker.Result pending;
  AppDialog dialog;
  interface DownloadAction { void start(UpdateChecker.Result result); }
  DownloadAction download;
  private TextView detail;
  private ProgressBar progress;
  private final Runnable tick=new Runnable() { public void run() { if (!destroyed && resumed) { present(); handler.postDelayed(this,500); } } };
  UpdateFlow(Activity activity) { this.activity=activity; u=new Ui(activity); download=result->UpgradeManager.request(activity,result,false); }
  void resume() {
    resumed=true; handler.removeCallbacks(tick); handler.post(tick);
    if (!entered) { entered=true; check(false); }
  }
  void pause() { resumed=false; handler.removeCallbacks(tick); }
  void leave() { entered=false; pending=null; epoch++; if (!working && dialog!=null && dialog.isShowing()) dialog.dismiss(); }
  void destroy() { destroyed=true; handler.removeCallbacksAndMessages(null); }
  void check(boolean manual) {
    int request=++epoch;
    UpdateChecker.Callback callback=result->handler.post(()->{
      if (destroyed || request!=epoch || (!manual && !UpdateChecker.enabled(activity))) return;
      if (!working && !UpgradeManager.prefs(activity).getBoolean("show_progress",false) && (manual || result.available())) pending=result;
      present();
    });
    if (manual) UpdateChecker.check(activity,true,callback); else UpdateChecker.checkOnEntry(activity,callback);
  }
  void automaticChanged() {
    pending=null; epoch++;
    if (UpdateChecker.enabled(activity)) check(false);
  }
  private void present() {
    if (!resumed || destroyed || activity.isFinishing() || activity.isDestroyed()) return;
    if (working && dialog!=null && dialog.isShowing()) { renderProgress(); return; }
    if (AppDialog.hasOpen(activity)) return;
    if (UpgradeManager.prefs(activity).getBoolean("completion_pending",false)) { completed(); return; }
    if (UpgradeManager.prefs(activity).getBoolean("show_progress",false)) { progress(); return; }
    if (pending==null) return;
    UpdateChecker.Result result=pending; pending=null;
    AppDialog.Builder offer=new AppDialog.Builder(activity).setTitle(result.available()?"有新版本可以更新":"版本檢查")
      .setMessage(result.message+(result.available()?"\n\n目前版本 "+BuildConfig.VERSION_NAME+"\n可在 App 內下載並驗證更新，不必前往瀏覽器。安裝時仍由 Android 確認；更新前建議完成備份。":""))
      .setNegativeButton(result.available()?"稍後再說":"關閉",null);
    if (result.installable()) offer.setPositiveButton("現在更新",(d,w)->{
      UpgradeManager.prefs(activity).edit().putBoolean("show_progress",true).apply();
      download.start(result); progress();
    });
    else if(result.available()) offer.setMessage(result.message+"\n\n此版本缺少完整驗證資訊，暫時不能在 App 內安裝，請查看官方版本說明。");
    if(result.available()) offer.setNeutralButton("版本說明",(d,w)->{
      try { activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(result.url))); } catch(Exception e) { Toast.makeText(activity,"無法開啟版本說明",Toast.LENGTH_SHORT).show(); }
    });
    dialog=offer.show();
  }
  void openDownloaded() {
    if (!UpgradeManager.prefs(activity).getBoolean("ready",false)) { Toast.makeText(activity,"請先檢查並下載更新",Toast.LENGTH_SHORT).show(); return; }
    UpgradeManager.prefs(activity).edit().putBoolean("show_progress",true).apply(); progress();
  }
  private void progress() {
    if (dialog!=null && dialog.isShowing()) dialog.dismiss();
    working=true; pending=null; confirmationSession=-1;
    LinearLayout body=u.column(); detail=u.text(UpgradeManager.status(activity),15,u.muted,false); body.addView(detail); u.gap(body,16);
    progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(u.accent)); body.addView(progress,new LinearLayout.LayoutParams(-1,u.d(8)));
    dialog=new AppDialog.Builder(activity).setTitle("正在更新 HuangNotify").setView(body)
      .setPositiveButton("準備中…",null).setNegativeButton("稍後繼續",(d,w)->closeProgress()).create();
    dialog.setOnCancelListener(d->closeProgress());
    dialog.setOnShowListener(d->{ dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v->advance()); renderProgress(); });
    dialog.show();
  }
  private void closeProgress() { working=false; UpgradeManager.prefs(activity).edit().putBoolean("show_progress",false).apply(); }
  private void renderProgress() {
    if (detail==null || dialog==null) return;
    detail.setText(UpgradeManager.status(activity));
    String stage=UpgradeManager.prefs(activity).getString("stage","");
    boolean ready=UpgradeManager.prefs(activity).getBoolean("ready",false);
    progress.setIndeterminate(!stage.equals("downloading") && !ready && !stage.equals("failed"));
    progress.setProgress(ready?100:UpgradeManager.prefs(activity).getInt("progress",0));
    TextView button=dialog.getButton(AppDialog.BUTTON_POSITIVE); if(button==null) return;
    button.setEnabled(ready && !stage.equals("installing")); button.setAlpha(button.isEnabled()?1f:.5f);
    button.setText(!ready?stage.equals("failed")?"下載未完成":"下載與驗證中…":!activity.getPackageManager().canRequestPackageInstalls()?"允許安裝更新":stage.equals("confirm")?"確認安裝":stage.equals("installing")?"等待 Android 完成…":"安裝更新");
    if (ready && stage.equals("ready") && activity.getPackageManager().canRequestPackageInstalls()) advance();
    if (stage.equals("confirm") && UpgradeManager.confirmation!=null && confirmationSession!=UpgradeManager.prefs(activity).getInt("session",-1)) advance();
  }
  private void advance() {
    if (!UpgradeManager.prefs(activity).getBoolean("ready",false)) return;
    if (!activity.getPackageManager().canRequestPackageInstalls()) {
      try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName()))); }
      catch(Exception e) { detail.setText("請在系統設定允許 HuangNotify 安裝更新，回來後繼續。"); }
      return;
    }
    if (UpgradeManager.confirmation!=null) {
      confirmationSession=UpgradeManager.prefs(activity).getInt("session",-1);
      try { activity.startActivity(UpgradeManager.confirmation); } catch(Exception e) { UpgradeManager.confirmation=null; UpgradeManager.install(activity,false); }
    } else if(!UpgradeManager.prefs(activity).getString("stage","").equals("installing")) UpgradeManager.install(activity,false);
  }
  private void completed() {
    working=false;
    dialog=new AppDialog.Builder(activity).setTitle("更新完成")
      .setMessage("已成功安裝 "+BuildConfig.VERSION_NAME+"。原有紀錄仍保留，你可以完成並重新開啟 HuangNotify。")
      .setNegativeButton("繼續使用",(d,w)->UpdateCompletion.acknowledge(activity))
      .setPositiveButton("完成並重新開啟",(d,w)->{
        UpdateCompletion.acknowledge(activity);
        Intent restart=Intent.makeRestartActivityTask(new ComponentName(activity,MainActivity.class));
        activity.startActivity(restart); activity.finish();
      }).create();
    dialog.setOnCancelListener(d->UpdateCompletion.acknowledge(activity)); dialog.show();
  }
}
