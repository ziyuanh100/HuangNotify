package dev.huang.notify;
import android.app.job.*;
import android.os.*;
import java.util.concurrent.atomic.AtomicBoolean;
public final class UpgradeJob extends JobService {
  private AtomicBoolean cancelled;
  @Override public boolean onStartJob(JobParameters params) {
    AtomicBoolean token = new AtomicBoolean(); cancelled = token;
    UpgradeManager.IO.execute(() -> {
      try { UpgradeManager.download(this,params.getExtras(),token); }
      catch (Exception e) { UpgradeManager.stage(this,token.get()?"queued":"failed",token.get()?"下載已中斷，等待重新排程":"下載或驗證失敗，請檢查網路、空間或重新下載"); }
      new Handler(Looper.getMainLooper()).post(() -> { if (!token.get()) jobFinished(params,false); });
    }); return true;
  }
  @Override public boolean onStopJob(JobParameters params) { if (cancelled!=null) cancelled.set(true); return true; }
}
