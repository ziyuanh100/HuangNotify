package dev.huang.notify;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import java.util.Arrays;

/** Re-attachable view of the application-owned operation; destroying a screen does not cancel work. */
final class BackupScreen implements BackupOperation.Observer {
  final Activity activity;final Ui u;final Runnable changed;
  private boolean destroyed,resumed;private BackupOperation shown;private BackupOperation.State shownState;
  AppDialog progress;private AppDialog prompt;private TextView phase;private ProgressBar bar;
  private final Handler handler=new Handler(Looper.getMainLooper());
  BackupScreen(Activity activity,Ui u,Runnable changed){this.activity=activity;this.u=u;this.changed=changed;}
  void resume(){resumed=true;BackupOperation.observe(this);if(BackupOperation.recover(activity))toast("上次備份作業已中斷，請重新操作；手機紀錄仍保留");update();}
  void pause(){resumed=false;BackupOperation.detach(this);hideProgress();}
  void destroy(){destroyed=true;pause();if(prompt!=null)prompt.dismiss();handler.removeCallbacksAndMessages(null);}
  private void toast(String text){if(!destroyed)Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
  private EditText password(String hint){EditText e=u.field(hint);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
  private LinearLayout body(){LinearLayout b=u.column();b.setBackgroundColor(u.surface);return b;}
  private final Runnable tick=new Runnable(){public void run(){BackupOperation op=BackupOperation.current();if(progress!=null&&progress.isShowing()&&op!=null&&resumed&&!destroyed){phase.setText(op.task.status());bar.setIndeterminate(op.task.total<=0);if(op.task.total>0)bar.setProgress((int)Math.min(100,op.task.done*100/op.task.total));handler.postDelayed(this,250);}}};
  private void showProgress(BackupOperation op){
    hideProgress();LinearLayout b=body();phase=u.text(op.task.status(),14,u.ink,false);b.addView(phase);u.gap(b,12);
    bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);b.addView(bar,new LinearLayout.LayoutParams(-1,u.d(12)));u.gap(b,12);
    b.addView(u.text("可切換畫面，返回後查看進度。取消不會刪除手機內的通知紀錄。",12,u.muted,false));
    progress=new AppDialog.Builder(activity).setTitle(op.state==BackupOperation.State.CANCELLING?"正在取消":"正在處理備份").setView(b).setNegativeButton("取消",(d,w)->op.cancel()).create();
    progress.setOnCancelListener(d->op.cancel());progress.show();if(op.state==BackupOperation.State.CANCELLING)progress.getButton(AppDialog.BUTTON_NEGATIVE).setEnabled(false);handler.post(tick);
  }
  private void hideProgress(){handler.removeCallbacks(tick);if(progress!=null)progress.dismiss();progress=null;}
  public void update(){
    if(!resumed||destroyed)return;BackupOperation op=BackupOperation.current();if(op==null){hideProgress();return;}BackupOperation.State state=op.visibleState();
    if(op==shown&&state==shownState){if((progress==null||!progress.isShowing())&&op.working)showProgress(op);return;}
    shown=op;shownState=state;hideProgress();if(prompt!=null){prompt.dismiss();prompt=null;}
    switch(state){
      case EXPORT_PICKER:if(!op.pickerLaunched){op.pickerLaunched=true;try{activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"HuangNotify_"+System.currentTimeMillis()+(op.session.encrypted?".hnbk":".hnjson")),101);}catch(Exception e){op.fail(e);}}break;
      case IMPORT_PICKER:break;
      case LOCKED:unlockDialog(op);break;
      case PREVIEW:preview(op);break;
      case DONE:
        int[] counts=op.counts;BackupOperation.clear(op);changed.run();
        if(counts==null)toast("備份已儲存並完成驗證");else new AppDialog.Builder(activity).setTitle("還原完成").setMessage("新增 "+counts[0]+" 則\n略過 "+counts[1]+" 則既有紀錄\n補入或復原 "+counts[2]+" 個歷程").setPositiveButton("完成",null).show();break;
      case FAILED:String message=op.error;boolean saveFailed=op.pickerLaunched&&op.session!=null&&op.session.ready!=null;BackupOperation.clear(op);toast(message+(saveFailed?"；目標檔可能未完成，請重新匯出":""));break;
      default:showProgress(op);
    }
  }
  void exportDialog(){
    if(BackupOperation.busy()){toast("已有備份作業進行中");update();return;}
    LinearLayout b=body();b.addView(u.text("包含文字、圖片、歷程、收藏與備註，單份上限 100 MiB。新版備份需用 1.2.0 以上還原；仍可匯入舊 v1／v2。",14,u.muted,false));
    EditText password=password("備份密碼（至少 8 個字元）"),confirm=password("再次輸入密碼");u.gap(b,12);b.addView(password);u.gap(b,8);b.addView(confirm);
    AppDialog dialog=new AppDialog.Builder(activity).setTitle("建立備份").setView(b).setNegativeButton("取消",null)
      .setNeutralButton("不加密",(d,w)->new AppDialog.Builder(activity).setTitle("匯出未加密備份？").setMessage("取得檔案的人可閱讀文字及圖片，請妥善保管。").setNegativeButton("取消",null).setPositiveButton("匯出",(dd,ww)->prepareExport(null)).show()).setPositiveButton("加密匯出",null).create();
    dialog.setOnShowListener(d->dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(password.length()<8){password.setError("至少 8 個字元");return;}if(!password.getText().toString().equals(confirm.getText().toString())){confirm.setError("兩次密碼不相同");return;}char[] secret=password.getText().toString().toCharArray();password.setText("");confirm.setText("");dialog.dismiss();prepareExport(secret);}));dialog.show();
  }
  void prepareExport(char[] password){BackupOperation op=BackupOperation.begin(activity,BackupOperation.State.EXPORTING);if(op==null){if(password!=null)Arrays.fill(password,'\0');toast("已有備份作業進行中");return;}op.export(password);update();}
  void chooseImport(){BackupOperation op=BackupOperation.begin(activity,BackupOperation.State.IMPORT_PICKER);if(op==null){toast("已有備份作業進行中");update();return;}op.pickerLaunched=true;try{activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),102);}catch(Exception e){op.fail(e);}}
  boolean onResult(int request,int result,Intent data){
    if(request!=101&&request!=102)return false;BackupOperation op=BackupOperation.current();
    if(op==null){if(result==Activity.RESULT_OK)toast("備份作業已中斷，請重新操作");return true;}
    if((request==101&&op.state!=BackupOperation.State.EXPORT_PICKER)||(request==102&&op.state!=BackupOperation.State.IMPORT_PICKER))return true;
    if(result!=Activity.RESULT_OK||data==null||data.getData()==null){op.cancel();return true;}Uri uri=data.getData();
    if(!"content".equals(uri.getScheme())){op.fail(new BackupProblem("請使用系統檔案選擇器選擇位置"));return true;}
    if(request==101)op.save(uri);else op.read(uri);return true;
  }
  private void unlockDialog(BackupOperation op){
    EditText field=password("輸入備份密碼");prompt=new AppDialog.Builder(activity).setTitle("解鎖備份").setView(field).setNegativeButton("取消",(d,w)->op.cancel()).setPositiveButton("解鎖",null).create();
    prompt.setOnCancelListener(d->op.cancel());AppDialog dialog=prompt;dialog.setOnShowListener(d->dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v->{char[] secret=field.getText().toString().toCharArray();field.setText("");dialog.dismiss();op.unlock(secret);}));dialog.show();
  }
  private void preview(BackupOperation op){
    BackupArchive.Stage stage=op.session.stage;LinearLayout b=body();b.addView(u.text("已檢查備份 v"+stage.format+"\n"+stage.records+" 則通知 · "+stage.versions+" 個歷程 · "+stage.images+" 張圖片\n\n合併時保留手機現有內容、收藏與備註。",14,u.ink,false));
    AppDialog.Toggle settings=new AppDialog.Toggle(activity);settings.setText("同時還原保存期限、圖片記錄與來源設定");settings.setChecked(op.restoreSettings);settings.setOnClickListener(v->{settings.toggle();op.restoreSettings=settings.isChecked();});b.addView(settings);
    prompt=new AppDialog.Builder(activity).setTitle("確認還原備份").setView(b).setNegativeButton("取消",(d,w)->op.cancel()).setPositiveButton("合併匯入",(d,w)->op.merge()).create();prompt.setOnCancelListener(d->op.cancel());prompt.show();
  }
}
