package dev.huang.notify;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class BackupTask {
  final AtomicBoolean cancelled;
  volatile String phase="準備中"; volatile long done,total;
  BackupTask(){this(new AtomicBoolean());}
  BackupTask(AtomicBoolean cancelled){this.cancelled=cancelled;}
  void check() throws InterruptedIOException {BackupStreamCrypto.check(cancelled);}
  void phase(String value,long total){this.phase=value;this.done=0;this.total=total;}
  void progress(long count){done=count;}
  String status(){return phase+(done>0?" · 已處理 "+String.format(java.util.Locale.TAIWAN,"%.1f",done/(1024f*1024f))+" MiB":"");}
}
