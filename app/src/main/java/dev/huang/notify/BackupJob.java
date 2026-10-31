package dev.huang.notify;

import android.app.job.*;
import android.os.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BackupJob extends JobService {
  private AtomicBoolean cancelled;
  @Override public boolean onStartJob(JobParameters params) {
    AtomicBoolean token = new AtomicBoolean(); cancelled = token;
    AutoBackup.run(this, false, token, message -> new Handler(Looper.getMainLooper()).post(() -> {
      if (!token.get()) jobFinished(params, false);
    })); return true;
  }
  @Override public boolean onStopJob(JobParameters params) { if (cancelled != null) cancelled.set(true); return true; }
}
