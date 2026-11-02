package dev.huang.notify;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class BackupTask {
  final AtomicBoolean cancelled;
  final long started=System.nanoTime();private long phaseStarted=started,pausedAt,excluded;
  private final java.util.Map<String,Long> durations=new java.util.LinkedHashMap<>();
  boolean encryptedExport;
  volatile String phase="準備中"; volatile long done,total;
  BackupTask(){this(new AtomicBoolean());}
  BackupTask(AtomicBoolean cancelled){this.cancelled=cancelled;}
  synchronized void pauseClock(){if(pausedAt==0)pausedAt=System.nanoTime();}
  synchronized void resumeClock(){if(pausedAt!=0){excluded+=System.nanoTime()-pausedAt;pausedAt=0;}}
  synchronized long elapsedMillis(){return ((pausedAt==0?System.nanoTime():pausedAt)-started-excluded)/1000000;}
  void check() throws InterruptedIOException {BackupStreamCrypto.check(cancelled);}
  synchronized void phase(String value,long total){long now=System.nanoTime();durations.put(phase,durations.getOrDefault(phase,0L)+now-phaseStarted);phaseStarted=now;this.phase=value;this.done=0;this.total=total;}
  synchronized java.util.Map<String,Long> timingsMillis(){java.util.Map<String,Long> result=new java.util.LinkedHashMap<>();for(java.util.Map.Entry<String,Long> e:durations.entrySet())result.put(e.getKey(),e.getValue()/1000000);result.put(phase,result.getOrDefault(phase,0L)+(System.nanoTime()-phaseStarted)/1000000);return result;}
  void progress(long count){done=count;}
  String status(){return (cancelled.get()?"正在取消，等待目前讀寫結束":phase)+" · "+(elapsedMillis()/1000)+" 秒"+(done>0?" · 已處理 "+String.format(java.util.Locale.TAIWAN,"%.1f",done/(1024f*1024f))+" MiB":"");}
}
