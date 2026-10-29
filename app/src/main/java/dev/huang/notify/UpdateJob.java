package dev.huang.notify;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

public final class UpdateJob extends JobService {
  private JobParameters running;
  @Override public boolean onStartJob(JobParameters params) {
    running = params;
    UpdateChecker.check(this, false, result -> new Handler(Looper.getMainLooper()).post(() -> {
      if (running == params) { running = null; jobFinished(params, false); }
    }));
    return true;
  }
  @Override public boolean onStopJob(JobParameters params) { running = null; return true; }
}
