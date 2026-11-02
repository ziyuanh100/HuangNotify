package dev.huang.notify;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.*;

/** One application-owned manual operation. All state transitions and observers are on the main thread. */
final class BackupOperation {
  enum State { EXPORTING,EXPORT_PICKER,IMPORT_PICKER,READING,LOCKED,PREVIEW,UNLOCKING,MERGING,SAVING,CANCELLING,DONE,FAILED }
  interface Observer {void update();}
  private interface Work {void run()throws Exception;}
  private static final Handler MAIN=new Handler(Looper.getMainLooper());
  private static BackupOperation current;
  private static WeakReference<Observer> observer=new WeakReference<>(null);
  final Context context;final String id=UUID.randomUUID().toString();final BackupTask task=new BackupTask();
  volatile BackupFiles.Session session;State state;boolean pickerLaunched,working,restoreSettings;String error="";int[] counts;
  private BackupOperation(Context c,State state){context=c.getApplicationContext();this.state=state;if(state==State.IMPORT_PICKER)task.pauseClock();context.getSharedPreferences("backup_operation",0).edit().putString("pending",id).apply();}
  static BackupOperation current(){return current;}
  static void observe(Observer value){observer=new WeakReference<>(value);}
  static void detach(Observer value){if(observer.get()==value)observer.clear();}
  static boolean busy(){return current!=null&&current.state!=State.DONE&&current.state!=State.FAILED;}
  static BackupOperation begin(Context c,State state){if(busy())return null;if(current!=null)clear(current);return current=new BackupOperation(c,state);}
  static boolean recover(Context c){
    if(current!=null)return false;boolean interrupted=c.getSharedPreferences("backup_operation",0).contains("pending");
    if(interrupted){c.getSharedPreferences("backup_operation",0).edit().remove("pending").apply();AutoBackup.IO.execute(()->BackupFiles.cleanInterrupted(c.getApplicationContext()));}return interrupted;
  }
  private void notifyUi(){Observer value=observer.get();if(value!=null)value.update();}
  static void clear(BackupOperation op){
    if(current!=op)return;current=null;op.context.getSharedPreferences("backup_operation",0).edit().remove("pending").apply();
    BackupFiles.Session owned=op.session;op.session=null;if(owned!=null)AutoBackup.IO.execute(owned::close);
  }
  private void execute(State running,State next,Work work){
    task.resumeClock();state=running;working=true;notifyUi();AutoBackup.IO.execute(()->{
      try{work.run();MAIN.post(()->{working=false;if(current!=this)return;if(task.cancelled.get()&&next!=State.DONE){fail(new java.io.InterruptedIOException());return;}state=next;if(next==State.EXPORT_PICKER||next==State.PREVIEW)task.pauseClock();notifyUi();});}
      catch(Exception e){MAIN.post(()->{working=false;if(current==this)fail(e);});}
    });
  }
  void fail(Throwable cause){error=BackupFiles.error(cause);state=State.FAILED;notifyUi();}
  void cancel(){task.cancelled.set(true);if(working){state=State.CANCELLING;notifyUi();}else fail(new java.io.InterruptedIOException());}
  void export(char[] password){execute(State.EXPORTING,State.EXPORT_PICKER,()->{try{session=BackupFiles.export(context,password,task);}finally{if(password!=null)Arrays.fill(password,'\0');}});}
  void read(Uri uri){execute(State.READING,State.PREVIEW,()->{session=BackupFiles.read(context,uri,task);});}
  void unlock(char[] password){execute(State.UNLOCKING,State.PREVIEW,()->{try{BackupFiles.unlock(session,password,task);}finally{Arrays.fill(password,'\0');}});}
  void save(Uri uri){execute(State.SAVING,State.DONE,()->BackupFiles.save(context,session,uri,task));}
  void merge(){execute(State.MERGING,State.DONE,()->{counts=Store.IO.submit(()->Store.get(context).restoreStage(session.stage,restoreSettings,task)).get();});}
  State visibleState(){if(state==State.PREVIEW&&session!=null&&session.stage==null&&session.encrypted)return State.LOCKED;return state;}
}
