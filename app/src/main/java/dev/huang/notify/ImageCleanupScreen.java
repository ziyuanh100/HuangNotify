package dev.huang.notify;

import android.app.Activity;
import android.widget.*;

/** Recomputes the estimate before confirmation; the deletion transaction uses current references. */
final class ImageCleanupScreen {
  static void show(Activity a,Ui u,Runnable changed){
    LinearLayout body=u.column();body.setBackgroundColor(u.surface);
    body.addView(u.text("清除通知與歷程中的圖片，文字、收藏及備註仍保留。請先備份需要的圖片。",14,u.muted,false));
    AppDialog.Toggle include=new AppDialog.Toggle(a);include.setText("也清除收藏通知的圖片");body.addView(include);u.gap(body,12);
    TextView summary=u.text("正在計算圖片用量…",14,u.ink,false);body.addView(summary);
    body.addView(u.text("共用圖片只計一次。預估值可能隨新通知改變；已釋出的資料庫空間可重用，檔案大小不一定立即縮小。外部備份及另存圖片保留。",12,u.muted,false));
    AppDialog dialog=new AppDialog.Builder(a).setTitle("清除已保存圖片？").setView(body).setNegativeButton("取消",null).setPositiveButton("確認清除",null).create();
    int[] generation={0};Runnable load=()->{int gen=++generation[0];boolean favorites=include.isChecked();dialog.getButton(AppDialog.BUTTON_POSITIVE).setEnabled(false);summary.setText("正在計算圖片用量…");Store.IO.execute(()->{try{long[] result=Store.get(a).imageCleanupPreview(favorites);a.runOnUiThread(()->{if(a.isDestroyed()||!dialog.isShowing()||gen!=generation[0])return;summary.setText("影響 "+result[0]+" 則通知 · "+result[1]+" 張圖片\n預估可重用空間 "+android.text.format.Formatter.formatFileSize(a,result[2])+"\n"+(favorites?"包含收藏圖片":"保護收藏圖片"));dialog.getButton(AppDialog.BUTTON_POSITIVE).setEnabled(result[1]>0);});}catch(Exception e){a.runOnUiThread(()->{if(dialog.isShowing())summary.setText("無法計算圖片用量，請稍後重試");});}});};
    include.setOnClickListener(v->{include.toggle();load.run();});
    dialog.setOnShowListener(d->{dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v->{boolean favorites=include.isChecked();dialog.getButton(AppDialog.BUTTON_POSITIVE).setEnabled(false);dialog.dismiss();Store.IO.execute(()->{try{long freed=Store.get(a).clearImages(favorites);a.runOnUiThread(()->{if(a.isDestroyed())return;changed.run();Toast.makeText(a,"已清除圖片，釋出可重用空間 "+android.text.format.Formatter.formatFileSize(a,freed),Toast.LENGTH_LONG).show();});}catch(Exception e){a.runOnUiThread(()->Toast.makeText(a,"圖片清除失敗，請稍後重試",Toast.LENGTH_LONG).show());}});});load.run();});dialog.show();
  }
}
