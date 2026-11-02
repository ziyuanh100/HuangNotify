package dev.huang.notify;

import android.content.Context;
import android.database.sqlite.*;
import android.net.Uri;
import android.os.StatFs;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutionException;

/** Operation-owned private files; neither a 100-MiB backup nor its JSON tree lives in the heap. */
final class BackupFiles {
  static final class Session implements AutoCloseable {
    final File dir,input,plain,output,database;File ready;BackupArchive.Stage stage;boolean encrypted;
    Session(Context c)throws IOException {
      File root=new File(c.getCacheDir(),"backup-work");if(!root.isDirectory()&&!root.mkdirs())throw new IOException();
      dir=new File(root,"op_"+UUID.randomUUID());if(!dir.mkdir())throw new IOException();
      input=new File(dir,"input.bin");plain=new File(dir,"plain.json");output=new File(dir,"output.hnbk");database=new File(dir,"stage.db");
    }
    public void close(){try{if(stage!=null){stage.close();stage=null;}}finally{erase(dir);}}
  }
  static void erase(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)erase(child);file.delete();}
  static void cleanStale(Context c){File[] dirs=new File(c.getCacheDir(),"backup-work").listFiles();if(dirs!=null)for(File dir:dirs)if(dir.getName().matches("op_[0-9a-f-]{36}")&&System.currentTimeMillis()-dir.lastModified()>86400000L)erase(dir);}
  static void space(Context c,long needed){if(new StatFs(c.getCacheDir().getAbsolutePath()).getAvailableBytes()<needed+4*1024*1024L)throw new BackupProblem("儲存空間不足，請先清理手機空間");}
  static Session export(Context context,char[] password,BackupTask task)throws Exception {
    Session session=new Session(context);boolean success=false;File snapshot=new File(session.dir,"snapshot.db");
    try {
      task.check();File live=context.getDatabasePath("notifications.db");space(context,live.length()+new File(live.getPath()+"-wal").length());task.phase("準備備份",0);
      Store.IO.submit(()->{Store.get(context).snapshot(snapshot,task);return null;}).get();task.check();
      try(SQLiteDatabase db=SQLiteDatabase.openDatabase(snapshot.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS);OutputStream out=new BufferedOutputStream(new FileOutputStream(session.plain))) {BackupArchive.write(db,out,task);}
      SQLiteDatabase.deleteDatabase(snapshot);task.check();session.ready=session.plain;session.encrypted=password!=null;
      if(password!=null){space(context,session.plain.length());task.phase("加密備份",session.plain.length());try(InputStream in=new BufferedInputStream(new FileInputStream(session.plain));OutputStream out=new BufferedOutputStream(new FileOutputStream(session.output))){BackupStreamCrypto.encrypt(in,out,password,task.cancelled,task::progress);}session.ready=session.output;session.plain.delete();}
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
      if(!session.encrypted)session.stage=BackupArchive.read(session.input,session.database,task);
      success=true;return session;
    } finally {if(!success)session.close();}
  }
  static void unlock(Session session,char[] password,BackupTask task)throws Exception {
    task.phase("解鎖備份",session.input.length());byte[] prefix=new byte[5];try(DataInputStream in=new DataInputStream(new FileInputStream(session.input))){in.readFully(prefix);}
    boolean success=false;
    try {
      if(BackupStreamCrypto.encrypted(prefix)) {
        try(InputStream in=new BufferedInputStream(new FileInputStream(session.input));OutputStream out=new BufferedOutputStream(new FileOutputStream(session.plain))){BackupStreamCrypto.decrypt(in,out,password,task.cancelled,task::progress);}
      } else {
        // Legacy HNBK1 is deliberately kept at its original 20-MiB bound.
        if(session.input.length()>BackupCrypto.MAX_BYTES+53)throw new BackupProblem("舊版加密備份超過 20 MiB 上限");
        byte[] bytes=new byte[(int)session.input.length()],clear=null;
        try {try(DataInputStream in=new DataInputStream(new FileInputStream(session.input))){in.readFully(bytes);}task.check();clear=BackupCrypto.decrypt(bytes,password);try(OutputStream out=new FileOutputStream(session.plain)){out.write(clear);}}
        finally {Arrays.fill(bytes,(byte)0);if(clear!=null)Arrays.fill(clear,(byte)0);}
      }
      task.check();session.stage=BackupArchive.read(session.plain,session.database,task);success=true;
    } finally {if(!success)session.plain.delete();}
  }
  static long copy(InputStream in,OutputStream out,long maximum,BackupTask task)throws IOException {
    byte[] buffer=new byte[64*1024];long count=0;int n;
    while((n=in.read(buffer))!=-1){task.check();if(count+n>maximum)throw new BackupProblem("備份超過 100 MiB 上限");out.write(buffer,0,n);count+=n;task.progress(count);}task.check();out.flush();return count;
  }
  static byte[] digest(InputStream in,long length,BackupTask task)throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;byte[] buffer=new byte[64*1024];int n;
    while((n=in.read(buffer))!=-1){task.check();count+=n;if(count>length)throw new IOException("備份驗證失敗");digest.update(buffer,0,n);task.progress(count);}if(count!=length)throw new IOException("備份驗證失敗");task.check();return digest.digest();
  }
  static void save(Context c,Session session,Uri uri,BackupTask task)throws Exception {
    long size=session.ready.length();byte[] expected;
    try(InputStream in=new FileInputStream(session.ready)){expected=digest(in,size,task);}
    task.phase("儲存備份",size);
    try(InputStream in=new BufferedInputStream(new FileInputStream(session.ready));OutputStream out=c.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();copy(in,out,BackupStreamCrypto.MAX_FILE_BYTES,task);}
    task.phase("驗證已儲存的備份",size);
    try(InputStream in=c.getContentResolver().openInputStream(uri)){if(in==null||!MessageDigest.isEqual(expected,digest(in,size,task)))throw new IOException("備份驗證失敗");}
  }
  static String error(Throwable error){
    while(error instanceof ExecutionException&&error.getCause()!=null)error=error.getCause();
    if(error instanceof InterruptedIOException)return "作業已取消，原有紀錄與備份保留";
    if(error instanceof BackupProblem)return error.getMessage();
    if(error instanceof SecurityException)return "檔案或資料夾權限已失效，請重新選擇位置";
    if(error instanceof SQLiteFullException || (error instanceof IOException && error.getMessage()!=null&&(error.getMessage().contains("ENOSPC")||error.getMessage().contains("No space"))))return "儲存空間不足，請先清理手機空間";
    if(error instanceof javax.crypto.BadPaddingException)return "密碼錯誤或備份檔已損壞";
    if(error instanceof IOException)return "檔案無法讀寫、未完整儲存或驗證失敗，請檢查位置後重試";
    return "備份格式不正確、已損壞或含衝突資料；原有紀錄未變更";
  }
}
