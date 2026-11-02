package dev.huang.notify;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import java.util.Arrays;

/** Activity-owned, cancellable backup UI; long work and private-file cleanup stay off the UI thread. */
final class BackupScreen {
  final Activity activity;final Ui u;final Runnable changed;
  private BackupTask task;private BackupFiles.Session session;private boolean busy,destroyed;
  AppDialog progress;private TextView phase;private ProgressBar bar;private final Handler handler=new Handler(Looper.getMainLooper());
  BackupScreen(Activity activity,Ui u,Runnable changed){this.activity=activity;this.u=u;this.changed=changed;}
  private void toast(String text){if(!destroyed)Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
  private EditText password(String hint){EditText e=new EditText(activity);e.setHint(hint);e.setTextColor(u.ink);e.setHintTextColor(u.muted);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
  private final Runnable tick=new Runnable(){public void run(){if(progress!=null&&progress.isShowing()&&task!=null&&!destroyed){phase.setText(task.status());bar.setIndeterminate(task.total<=0);if(task.total>0)bar.setProgress((int)Math.min(100,task.done*100/task.total));handler.postDelayed(this,250);}}};
  private void showProgress(String title){
    hideProgress();LinearLayout body=u.column();phase=u.text(task.status(),14,u.ink,false);body.addView(phase);u.gap(body,12);bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);body.addView(bar,new LinearLayout.LayoutParams(-1,u.d(12)));
    body.addView(u.text("取消不會刪除手機內的通知紀錄。",12,u.muted,false));
    progress=new AppDialog.Builder(activity).setTitle(title).setView(body).setNegativeButton("取消",(d,w)->task.cancelled.set(true)).create();progress.setOnCancelListener(d->task.cancelled.set(true));progress.show();handler.post(tick);
  }
  private void hideProgress(){handler.removeCallbacks(tick);if(progress!=null)progress.dismiss();progress=null;}
  private void finish(){hideProgress();busy=false;if(task!=null)task.cancelled.set(true);BackupFiles.Session old=session;session=null;if(old!=null)AutoBackup.IO.execute(old::close);}
  void destroy(){destroyed=true;finish();handler.removeCallbacksAndMessages(null);}
  private void failed(Throwable error){hideProgress();String message=BackupFiles.error(error);finish();toast(message);}
  void exportDialog(){
    if(busy){toast("已有備份作業進行中，請完成或取消後再試");return;}
    LinearLayout body=u.column();body.addView(u.text("備份包含文字、圖片、歷程、收藏與備註，單份資料上限 100 MiB。新版備份 v3 需用 1.2.0 以上還原；仍可匯入舊版 v1／v2。",14,u.muted,false));
    EditText password=password("備份密碼（至少 8 個字元）"),confirm=password("再次輸入密碼");body.addView(password);body.addView(confirm);
    AppDialog dialog=new AppDialog.Builder(activity).setTitle("建立備份").setView(body).setNegativeButton("取消",null)
      .setNeutralButton("不加密",(d,w)->new AppDialog.Builder(activity).setTitle("匯出未加密備份？").setMessage("取得檔案的人可閱讀文字及圖片，請妥善保管。").setNegativeButton("取消",null).setPositiveButton("匯出",(dd,ww)->prepareExport(null)).show())
      .setPositiveButton("加密匯出",null).create();
    dialog.setOnShowListener(d->dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      if(password.length()<8){password.setError("至少 8 個字元");return;}if(!password.getText().toString().equals(confirm.getText().toString())){confirm.setError("兩次密碼不相同");return;}
      char[] secret=password.getText().toString().toCharArray();password.setText("");confirm.setText("");dialog.dismiss();prepareExport(secret);
    }));dialog.show();
  }
  void prepareExport(char[] password){
    if(busy){if(password!=null)Arrays.fill(password,'\0');toast("已有備份作業進行中");return;}
    busy=true;task=new BackupTask();BackupTask active=task;showProgress("正在建立備份");
    AutoBackup.IO.execute(()->{
      try {
        BackupFiles.Session result=BackupFiles.export(activity.getApplicationContext(),password,active);
        activity.runOnUiThread(()->{
          if(destroyed){AutoBackup.IO.execute(result::close);return;}session=result;hideProgress();
          try {activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"HuangNotify_"+System.currentTimeMillis()+(result.encrypted?".hnbk":".hnjson")),101);}
          catch(Exception e){failed(e);}
        });
      }catch(Exception e){activity.runOnUiThread(()->failed(e));}finally{if(password!=null)Arrays.fill(password,'\0');}
    });
  }
  void chooseImport(){if(busy){toast("已有備份作業進行中");return;}busy=true;task=new BackupTask();try{activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),102);}catch(Exception e){failed(e);}}
  boolean onResult(int request,int result,Intent data){
    if(request!=101&&request!=102)return false;
    if(result!=Activity.RESULT_OK||data==null||data.getData()==null){finish();return true;}
    Uri uri=data.getData();if(!"content".equals(uri.getScheme())){finish();toast("請使用系統檔案選擇器選擇位置");return true;}
    if(task==null)task=new BackupTask();BackupTask active=task;
    if(request==101){
      if(session==null||session.ready==null){finish();toast("備份作業已中斷，請重新匯出");return true;}BackupFiles.Session current=session;showProgress("正在儲存備份");
      AutoBackup.IO.execute(()->{try{BackupFiles.save(activity,current,uri,active);activity.runOnUiThread(()->{finish();toast("備份已儲存並完成驗證");});}catch(Exception e){activity.runOnUiThread(()->{failed(e);toast("目標檔可能未完成，請重新匯出；手機紀錄不受影響。");});}});
    }else{
      busy=true;showProgress("正在讀取備份");AutoBackup.IO.execute(()->{
        try{BackupFiles.Session resultSession=BackupFiles.read(activity,uri,active);activity.runOnUiThread(()->{if(destroyed){AutoBackup.IO.execute(resultSession::close);return;}session=resultSession;hideProgress();if(resultSession.encrypted)unlockDialog();else preview();});}
        catch(Exception e){activity.runOnUiThread(()->failed(e));}
      });
    }
    return true;
  }
  private void unlockDialog(){
    EditText password=password("輸入備份密碼");AppDialog dialog=new AppDialog.Builder(activity).setTitle("解鎖備份").setView(password).setNegativeButton("取消",(d,w)->finish()).setPositiveButton("解鎖",null).create();
    dialog.setOnCancelListener(d->finish());dialog.setOnShowListener(d->dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      char[] secret=password.getText().toString().toCharArray();password.setText("");dialog.dismiss();BackupFiles.Session current=session;BackupTask active=task;showProgress("正在解鎖備份");
      AutoBackup.IO.execute(()->{try{BackupFiles.unlock(current,secret,active);activity.runOnUiThread(()->{hideProgress();if(!destroyed)preview();});}catch(Exception e){activity.runOnUiThread(()->failed(e));}finally{Arrays.fill(secret,'\0');}});
    }));dialog.show();
  }
  private void preview(){
    if(destroyed)return;BackupArchive.Stage stage=session.stage;LinearLayout body=u.column();
    body.addView(u.text("已檢查備份 v"+stage.format+"\n"+stage.records+" 則通知 · "+stage.versions+" 個歷程 · "+stage.images+" 張圖片\n\n合併時保留手機現有內容、收藏與備註。取消不會變更紀錄。",14,u.ink,false));
    AppDialog.Toggle settings=new AppDialog.Toggle(activity);settings.setText("同時還原保存期限、圖片記錄與來源設定");settings.setTextColor(u.ink);body.addView(settings);
    AppDialog dialog=new AppDialog.Builder(activity).setTitle("確認還原備份").setView(body).setNegativeButton("取消",(d,w)->finish()).setPositiveButton("合併匯入",(d,w)->{
      boolean restoreSettings=settings.isChecked();BackupTask active=task;showProgress("正在還原備份");AutoBackup.IO.execute(()->{
        try {int[] counts=Store.IO.submit(()->Store.get(activity).restoreStage(stage,restoreSettings,active)).get();activity.runOnUiThread(()->{finish();if(destroyed)return;changed.run();new AppDialog.Builder(activity).setTitle("還原完成").setMessage("新增 "+counts[0]+" 則\n略過 "+counts[1]+" 則既有紀錄\n補入或復原 "+counts[2]+" 個歷程").setPositiveButton("完成",null).show();});}
        catch(Exception e){activity.runOnUiThread(()->failed(e));}
      });
    }).create();dialog.setOnCancelListener(d->finish());dialog.show();
  }
}
