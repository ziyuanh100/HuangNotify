package dev.huang.notify;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Locale;

/** Re-attachable view of the application-owned operation; destroying a screen does not cancel work. */
final class BackupScreen implements BackupOperation.Observer {
  final Activity activity;final Ui u;final Runnable changed;
  private boolean destroyed,resumed;private BackupOperation shown;private BackupOperation.State shownState;
  AppDialog progress;private AppDialog prompt,exportOptions;private TextView step,phase;private ProgressBar bar;
  private final Handler handler=new Handler(Looper.getMainLooper());
  BackupScreen(Activity activity,Ui u,Runnable changed){this.activity=activity;this.u=u;this.changed=changed;}
  void resume(){resumed=true;BackupOperation.observe(this);if(BackupOperation.recover(activity))toast("上次備份作業已中斷，請重新操作；手機紀錄仍保留");update();}
  void pause(){resumed=false;BackupOperation.detach(this);hideProgress();}
  void destroy(){destroyed=true;pause();if(prompt!=null)prompt.dismiss();if(exportOptions!=null)exportOptions.dismiss();handler.removeCallbacksAndMessages(null);}
  private void toast(String text){if(!destroyed)Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
  private EditText password(String hint){EditText e=u.field(hint);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
  private LinearLayout body(){LinearLayout b=u.column();b.setBackgroundColor(u.surface);return b;}
  private final Runnable tick=new Runnable(){public void run(){
    BackupOperation op=BackupOperation.current();
    if(progress==null||!progress.isShowing()||op==null||!resumed||destroyed)return;
    if(op.exporting)step.setText(op.task.exportStep());
    phase.setText(op.task.status());
    long total=op.task.total;
    bar.setIndeterminate(total<=0);
    if(total>0)bar.setProgress((int)Math.min(100,op.task.done*100/total));
    handler.postDelayed(this,250);
  }};
  private void showProgress(BackupOperation op){
    hideProgress();LinearLayout b=body();
    if(op.exporting){step=u.text(op.task.exportStep(),16,u.ink,true);b.addView(step);u.gap(b,6);}
    phase=u.text(op.task.status(),14,u.muted,false);b.addView(phase);u.gap(b,12);
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
      case EXPORT_PICKER:if(!op.pickerLaunched){op.pickerLaunched=true;try{activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"HuangNotify_"+System.currentTimeMillis()+(op.session.encrypted?".hnbk":BackupArchive.EXTENSION)),101);}catch(Exception e){op.fail(e);}}break;
      case IMPORT_PICKER:break;
      case LOCKED:unlockDialog(op);break;
      case PREVIEW:preview(op);break;
      case DONE:
        int[] counts=op.counts;BackupOperation.clear(op);changed.run();
        if(counts==null)toast("備份已儲存並完成驗證");else new AppDialog.Builder(activity).setTitle("還原完成").setMessage("新增 "+counts[0]+" 則\n略過 "+counts[1]+" 則既有紀錄\n補入或復原 "+counts[2]+" 個歷程").setPositiveButton("完成",null).show();break;
      case FAILED:showFailure(op);break;
      default:showProgress(op);
    }
  }
  private void showFailure(BackupOperation op){
    boolean saveFailed=op.exporting&&op.pickerLaunched&&op.session!=null;
    if(op.failure==BackupFiles.Failure.CANCELLED){
      BackupOperation.clear(op);
      toast(op.error+(saveFailed?"。本次選取的目標檔可能不完整。":""));
      return;
    }
    if(!saveFailed)BackupOperation.clear(op);
    AppDialog.Builder builder=new AppDialog.Builder(activity).setTitle(op.exporting?"備份未完成":"還原未完成")
        .setMessage(op.error+(saveFailed?"。本次選取的目標檔可能不完整。":""));
    if(saveFailed){
      builder.setNegativeButton("關閉",(d,w)->BackupOperation.clear(op))
          .setPositiveButton("重新選擇位置",(d,w)->op.chooseAnotherDestination());
    }else if(op.failure==BackupFiles.Failure.SPACE||op.failure==BackupFiles.Failure.LOCATION){
      builder.setNegativeButton("關閉",null).setPositiveButton(op.exporting?"重新建立":"重新選擇檔案",
          (d,w)->{if(op.exporting)exportDialog();else chooseImport();});
    }else builder.setPositiveButton("關閉",null);
    prompt=builder.create();
    if(saveFailed)prompt.setOnCancelListener(d->BackupOperation.clear(op));
    prompt.show();
  }
  void exportDialog() {
    if (BackupOperation.busy()) {
      toast("已有備份作業進行中");
      update();
      return;
    }
    if (exportOptions != null && exportOptions.isShowing()) return;
    LinearLayout b = body();
    LinearLayout summary = u.card();
    TextView total = u.text("正在估算備份容量…", 16, u.ink, true);
    total.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    summary.addView(total);
    b.addView(summary);
    u.gap(b, 10);
    b.addView(u.text("完整備份包含通知、備註、收藏、圖片與歷程。超過 250 MiB 的備份，請用 1.10.0 以上版本還原。",
        13, u.muted, false));
    EditText password = password("備份密碼（至少 8 個字元）"), confirm = password("再次輸入密碼");
    u.gap(b, 12);
    b.addView(password);
    u.gap(b, 8);
    b.addView(confirm);
    AppDialog dialog = new AppDialog.Builder(activity).setTitle("建立備份").setView(b)
        .setNegativeButton("取消", null)
        .setNeutralButton("不加密", (d, w) -> new AppDialog.Builder(activity)
            .setTitle("匯出未加密備份？").setMessage("取得檔案的人可閱讀文字及圖片，請妥善保管。")
            .setNegativeButton("取消", null).setPositiveButton("匯出", (dd, ww) -> prepareExport(null)).show())
        .setPositiveButton("加密匯出", null).create();
    CancellationSignal cancellation = new CancellationSignal();
    exportOptions = dialog;
    dialog.setOnDismissListener(d -> {
      cancellation.cancel();
      if (exportOptions == dialog) exportOptions = null;
    });
    dialog.show();
    TextView encrypted = dialog.getButton(AppDialog.BUTTON_POSITIVE);
    TextView plain = dialog.getButton(AppDialog.BUTTON_NEUTRAL);
    if (encrypted == null || plain == null) return;
    encrypted.setEnabled(false);
    plain.setEnabled(false);
    encrypted.setOnClickListener(v -> {
      if (password.length() < 8) { password.setError("至少 8 個字元"); return; }
      if (!password.getText().toString().equals(confirm.getText().toString())) {
        confirm.setError("兩次密碼不相同");
        return;
      }
      char[] secret = password.getText().toString().toCharArray();
      password.setText("");
      confirm.setText("");
      dialog.dismiss();
      prepareExport(secret);
    });
    Store.SEARCH.execute(() -> {
      BackupEstimate result = null;
      long required = 0, available = -1;
      try {
        cancellation.throwIfCanceled();
        Store store = Store.get(activity);
        result = BackupEstimate.read(store.getReadableDatabase(), cancellation);
        required = store.databaseBytes() + BackupStreamCrypto.encryptedBytes(result.totalBytes)
            + BackupFiles.SPACE_MARGIN;
        available = BackupFiles.availableBytes(activity.getCacheDir());
      } catch (OperationCanceledException cancelled) {
        return;
      } catch (RuntimeException unavailable) {
        // Counts and capacity can still be displayed if only the disk query failed.
      }
      BackupEstimate estimate = result;
      long requiredBytes = required, availableBytes = available;
      handler.post(() -> {
        if (destroyed || !dialog.isShowing() || cancellation.isCanceled()) return;
        renderEstimate(summary, total, estimate, requiredBytes, availableBytes);
        boolean allowed = estimate == null || !estimate.exceedsCountLimit();
        encrypted.setEnabled(allowed);
        plain.setEnabled(allowed);
      });
    });
  }

  private void renderEstimate(LinearLayout summary, TextView total, BackupEstimate estimate,
      long required, long available) {
    String cap = BackupStreamCrypto.MAX_MIB + " MiB";
    if (estimate == null) {
      total.setText("無法估算容量");
      summary.addView(u.text("仍可匯出，單份上限 " + cap + "、" + BackupArchive.VERSION_LIMIT + "更新歷程。",
          13, u.muted, false));
      return;
    }
    NumberFormat number = NumberFormat.getIntegerInstance(Locale.TAIWAN);
    total.setText("預估容量 " + BackupEstimate.size(estimate.totalBytes) + " / " + cap);
    u.gap(summary, 6);
    estimateRow(summary, "通知與備註", estimate.recordBytes);
    estimateRow(summary, "更新歷程", estimate.historyBytes);
    estimateRow(summary, "圖片", estimate.imageBytes);
    u.gap(summary, 6);
    summary.addView(u.text("歷程 " + number.format(estimate.versions) + " / "
        + BackupArchive.VERSION_LIMIT + "（含首次記錄）", 13, u.muted, false));
    countWarning(summary, "更新歷程", estimate.versions - BackupArchive.MAX_VERSIONS, "筆", number);
    countWarning(summary, "通知", estimate.records - BackupArchive.MAX_RECORDS, "則", number);
    countWarning(summary, "不同圖片", estimate.images - BackupArchive.MAX_IMAGES, "張", number);
    if (estimate.totalBytes > BackupStreamCrypto.MAX_BYTES) {
      summary.addView(u.text("預估超出容量上限 "
          + BackupEstimate.size(estimate.totalBytes - BackupStreamCrypto.MAX_BYTES)
          + "，以實際匯出容量為準。", 13, u.danger, true));
    }
    u.gap(summary, 10);
    summary.addView(u.text("本機暫存", 14, u.ink, true));
    if (available < 0) {
      summary.addView(u.text("暫時無法取得手機可用空間。", 13, u.muted, false));
      return;
    }
    estimateRow(summary, "預估所需", required);
    estimateRow(summary, "目前可用", available);
    if (available < required) {
      summary.addView(u.text("暫存空間預估還差 " + BackupEstimate.size(required - available)
          + "。可先釋出手機空間。", 13, u.danger, true));
    }
    summary.addView(u.text("依資料庫快照與加密檔估算，儲存位置需另有空間。", 12, u.muted, false));
  }

  private void countWarning(LinearLayout summary, String label, long excess, String unit, NumberFormat number) {
    if (excess <= 0) return;
    summary.addView(u.text(label + "超出上限 " + number.format(excess) + " " + unit,
        13, u.danger, true));
  }

  private void estimateRow(LinearLayout summary, String label, long bytes) {
    LinearLayout row = u.row();
    row.addView(u.text(label, 14, u.ink, false), new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(u.text(BackupEstimate.size(bytes), 14, u.ink, false));
    summary.addView(row);
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
