package dev.huang.notify;

import android.content.Context;
import android.database.sqlite.*;
import android.net.Uri;
import android.os.StatFs;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutionException;

/** Operation-owned private files for streaming backup and restore. */
final class BackupFiles {
  enum Failure { CANCELLED, SPACE, LOCATION, OTHER }
  private static final String NO_SPACE="儲存空間不足，請先清理可用空間再重試";
  private static final String LOCATION_ERROR="無法存取備份位置，請檢查權限或重新選擇";
  private static final Set<String> ACTIVE_DIRS=new HashSet<>();
  static final class Session implements AutoCloseable {
    final File dir,input,plain,output,database;File ready;BackupArchive.Stage stage;boolean encrypted;byte[] sha256;long readyBytes;
    Session(Context c)throws IOException {
      File root=new File(c.getCacheDir(),"backup-work");if(!root.isDirectory()&&!root.mkdirs())throw new IOException();
      dir=new File(root,"op_"+UUID.randomUUID());synchronized(ACTIVE_DIRS){if(!dir.mkdir())throw new IOException();ACTIVE_DIRS.add(dir.getAbsolutePath());}
      input=new File(dir,"input.bin");plain=new File(dir,"plain.json");output=new File(dir,"output.hnbk");database=new File(dir,"stage.db");
    }
    public void close(){try{if(stage!=null){stage.close();stage=null;}}finally{synchronized(ACTIVE_DIRS){erase(dir);ACTIVE_DIRS.remove(dir.getAbsolutePath());}}}
  }
  static void erase(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)erase(child);file.delete();}
  static void cleanStale(Context c){synchronized(ACTIVE_DIRS){File[] dirs=new File(c.getCacheDir(),"backup-work").listFiles();if(dirs!=null)for(File dir:dirs)if(dir.getName().matches("op_[0-9a-f-]{36}")&&!ACTIVE_DIRS.contains(dir.getAbsolutePath())&&System.currentTimeMillis()-dir.lastModified()>86400000L)erase(dir);}}
  static void cleanInterrupted(Context c){synchronized(ACTIVE_DIRS){File[] dirs=new File(c.getCacheDir(),"backup-work").listFiles();if(dirs!=null)for(File dir:dirs)if(dir.getName().matches("op_[0-9a-f-]{36}")&&!ACTIVE_DIRS.contains(dir.getAbsolutePath()))erase(dir);}}
  static void space(Context c,long needed){if(new StatFs(c.getCacheDir().getAbsolutePath()).getAvailableBytes()<needed+4*1024*1024L)throw new BackupProblem(NO_SPACE,Failure.SPACE);}
  static Session export(Context context,char[] password,BackupTask task)throws Exception {
    Session session=new Session(context);boolean success=false;File snapshot=new File(session.dir,"snapshot.db");
    try {
      task.check();File live=context.getDatabasePath("notifications.db");space(context,live.length()+new File(live.getPath()+"-wal").length());task.phase("準備備份",0);
      Store.IO.submit(()->{Store.get(context).snapshot(snapshot,task);return null;}).get();task.check();
      session.encrypted=password!=null;session.ready=session.encrypted?session.output:session.plain;task.encryptedExport=session.encrypted;
      MessageDigest hash=MessageDigest.getInstance("SHA-256");
      try(SQLiteDatabase db=SQLiteDatabase.openDatabase(snapshot.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
          OutputStream file=new BufferedOutputStream(new FileOutputStream(session.ready),64*1024);
          OutputStream digested=new java.security.DigestOutputStream(file,hash)) {
        if(password!=null){task.phase("密碼金鑰導出",0);try(BackupStreamCrypto.Encoder encrypted=new BackupStreamCrypto.Encoder(digested,password,task.cancelled,n->{})){BackupArchive.write(db,encrypted,task);}}
        else BackupArchive.write(db,digested,task);
      }
      session.sha256=hash.digest();session.readyBytes=session.ready.length();SQLiteDatabase.deleteDatabase(snapshot);
      task.check();success=true;return session;
    } finally {if(!success)session.close();}
  }
  static Session read(Context c,Uri uri,BackupTask task)throws Exception {
    Session session=new Session(c);boolean success=false;
    try {
      task.phase("讀取備份",0);
      try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new BufferedOutputStream(new FileOutputStream(session.input))){if(in==null)throw new IOException();copy(in,out,BackupStreamCrypto.MAX_FILE_BYTES,task);}
      byte[] prefix=new byte[5];try(DataInputStream in=new DataInputStream(new FileInputStream(session.input))){in.readFully(prefix);}
      session.encrypted=BackupStreamCrypto.encrypted(prefix)||BackupCrypto.encrypted(prefix);
      if(!session.encrypted){session.stage=BackupArchive.read(session.input,session.database,task);session.input.delete();}
      success=true;return session;
    } finally {if(!success)session.close();}
  }
  static void unlock(Session session,char[] password,BackupTask task)throws Exception {
    task.phase("解鎖備份",session.input.length());byte[] prefix=new byte[5];try(DataInputStream in=new DataInputStream(new FileInputStream(session.input))){in.readFully(prefix);}
    try {
      if(BackupStreamCrypto.encrypted(prefix)) {
        spaceForDecryption(session);
        try(InputStream in=new BufferedInputStream(new FileInputStream(session.input));OutputStream out=new BufferedOutputStream(new FileOutputStream(session.plain))){BackupStreamCrypto.decrypt(in,out,password,task.cancelled,task::progress);}
      } else {
        // Legacy HNBK1 is deliberately kept at its original 20-MiB bound.
        if(session.input.length()>BackupCrypto.MAX_BYTES+53)throw new BackupProblem("舊版加密備份超過 20 MiB 上限");
        byte[] bytes=new byte[(int)session.input.length()],clear=null;
        try {try(DataInputStream in=new DataInputStream(new FileInputStream(session.input))){in.readFully(bytes);}task.check();clear=BackupCrypto.decrypt(bytes,password);try(OutputStream out=new FileOutputStream(session.plain)){out.write(clear);}}
        finally {Arrays.fill(bytes,(byte)0);if(clear!=null)Arrays.fill(clear,(byte)0);}
      }
      task.check();session.input.delete();
      session.stage=BackupArchive.read(session.plain,session.database,task);
    } finally {session.plain.delete();}
  }
  private static void spaceForDecryption(Session session){
    if(new StatFs(session.dir.getAbsolutePath()).getAvailableBytes()<Math.min(session.input.length(),BackupStreamCrypto.MAX_BYTES))
      throw new BackupProblem(NO_SPACE,Failure.SPACE);
  }
  static long copy(InputStream in,OutputStream out,long maximum,BackupTask task)throws IOException {
    byte[] buffer=new byte[64*1024];long count=0;int n;
    while((n=in.read(buffer))!=-1){task.check();if(count+n>maximum)throw new BackupProblem(BackupStreamCrypto.TOO_LARGE);out.write(buffer,0,n);count+=n;task.progress(count);}task.check();out.flush();return count;
  }
  static byte[] digest(InputStream in,long length,BackupTask task)throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;byte[] buffer=new byte[64*1024];int n;
    while((n=in.read(buffer))!=-1){task.check();count+=n;if(count>length)throw new IOException("備份驗證失敗");digest.update(buffer,0,n);task.progress(count);}if(count!=length)throw new IOException("備份驗證失敗");task.check();return digest.digest();
  }
  static byte[] expected(Session session,BackupTask task)throws Exception{
    task.check();if(session.sha256!=null){if(session.ready.length()!=session.readyBytes)throw new IOException("備份驗證失敗");return session.sha256.clone();}
    try(InputStream in=new FileInputStream(session.ready)){return digest(in,session.ready.length(),task);}
  }
  static void save(Context c,Session session,Uri uri,BackupTask task)throws Exception {
    long size=session.ready.length();byte[] expected=expected(session,task);
    task.phase(BackupTask.SAVE,size);
    try(InputStream in=new BufferedInputStream(new FileInputStream(session.ready));OutputStream out=c.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();copy(in,out,BackupStreamCrypto.MAX_FILE_BYTES,task);}
    task.phase(BackupTask.VERIFY,size);
    try(InputStream in=c.getContentResolver().openInputStream(uri)){if(in==null||!MessageDigest.isEqual(expected,digest(in,size,task)))throw new IOException("備份驗證失敗");}
  }
  static Failure failure(Throwable error){
    Failure result=Failure.OTHER;
    for(Throwable cause=error;cause!=null;cause=cause.getCause()){
      if(cause instanceof BackupProblem && ((BackupProblem)cause).kind!=Failure.OTHER)return ((BackupProblem)cause).kind;
      if(cause instanceof SQLiteFullException || (cause instanceof ErrnoException && ((ErrnoException)cause).errno==OsConstants.ENOSPC)
          || (cause instanceof IOException && cause.getMessage()!=null && (cause.getMessage().contains("ENOSPC") || cause.getMessage().contains("No space"))))return Failure.SPACE;
      if(cause instanceof InterruptedIOException && !(cause instanceof java.net.SocketTimeoutException))return Failure.CANCELLED;
      if(cause instanceof SecurityException || cause instanceof FileNotFoundException)result=Failure.LOCATION;
    }
    return result;
  }
  static String error(Throwable error){
    while(error instanceof ExecutionException&&error.getCause()!=null)error=error.getCause();
    switch(failure(error)){
      case CANCELLED:return "作業已取消，原有紀錄與備份保留";
      case SPACE:return NO_SPACE;
      case LOCATION:return LOCATION_ERROR;
      default:break;
    }
    if(error instanceof BackupProblem)return error.getMessage();
    if(error instanceof javax.crypto.BadPaddingException)return "密碼錯誤或備份檔已損壞";
    if(error instanceof IOException)return "檔案無法讀寫、未完整儲存或驗證失敗，請檢查位置後重試";
    return "備份格式不正確、已損壞或含衝突資料；原有紀錄未變更";
  }
}
