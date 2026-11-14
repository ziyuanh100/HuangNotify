package dev.huang.notify;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.*;
import javax.crypto.spec.*;

/** HNBK2: bounded AES-GCM frames, with an authenticated terminal frame and strict EOF. */
public final class BackupStreamCrypto {
  static final int MAX_MIB=250;
  public static final long MAX_BYTES=MAX_MIB*1024L*1024L;
  static final String TOO_LARGE="單份備份超過 "+MAX_MIB+" MiB，請先整理通知或圖片";
  static final int CHUNK=64*1024, ITERATIONS=210000, HEADER=33;
  public static final long MAX_FILE_BYTES=MAX_BYTES+((MAX_BYTES+CHUNK-1)/CHUNK+1)*20+HEADER;
  static final byte[] MAGIC={'H','N','B','K',2};
  public interface Progress { void update(long bytes); }
  private BackupStreamCrypto() {}
  static void check(AtomicBoolean cancelled) throws InterruptedIOException {
    if(cancelled.get() || Thread.currentThread().isInterrupted())throw new InterruptedIOException("作業已取消");
  }
  public static boolean encrypted(byte[] prefix) {return prefix.length>=5 && Arrays.equals(Arrays.copyOf(prefix,5),MAGIC);}
  private static byte[] key(char[] password,byte[] salt) throws Exception {
    PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);
    try {return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
    finally {spec.clearPassword();}
  }
  private static Cipher frame(Cipher cipher,int mode,byte[] key,byte[] header,int index,int size,long total) throws Exception {
    byte[] nonce=ByteBuffer.allocate(12).put(header,25,8).putInt(index).array();
    cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
    cipher.updateAAD(ByteBuffer.allocate(HEADER+16).put(header).putInt(index).putInt(size).putLong(total).array());return cipher;
  }
  /** Push-based HNBK2 writer. flush never creates short frames; finish authenticates the terminal. */
  static final class Encoder extends OutputStream {
    final DataOutputStream out;final AtomicBoolean cancelled;final Progress progress;
    final byte[] header,key,buffer=new byte[CHUNK], encrypted=new byte[CHUNK+16];final Cipher cipher;
    long total;int used,index;boolean closed;
    Encoder(OutputStream destination,char[] password,AtomicBoolean cancelled,Progress progress)throws Exception{
      if(password.length<8)throw new BackupProblem("備份密碼至少需要 8 個字元");check(cancelled);
      this.out=new DataOutputStream(destination);this.cancelled=cancelled;this.progress=progress;
      SecureRandom random=new SecureRandom();byte[] salt=new byte[16],prefix=new byte[8];random.nextBytes(salt);random.nextBytes(prefix);
      header=ByteBuffer.allocate(HEADER).put(MAGIC).putInt(ITERATIONS).put(salt).put(prefix).array();key=key(password,salt);
      try{cipher=Cipher.getInstance("AES/GCM/NoPadding");out.write(header);}catch(Exception e){Arrays.fill(key,(byte)0);throw e;}
    }
    public void write(int b)throws IOException{byte[] one={(byte)b};write(one,0,1);}
    public void write(byte[] bytes,int offset,int length)throws IOException{
      if(closed)throw new IOException("備份輸出已關閉");if(offset<0||length<0||offset>bytes.length-length)throw new IndexOutOfBoundsException();
      check(cancelled);if(total+used+length>MAX_BYTES)throw new BackupProblem(TOO_LARGE);
      while(length>0){
        if(used==0&&length>=CHUNK){
          emit(bytes,offset,CHUNK);
          offset+=CHUNK;
          length-=CHUNK;
        }else{
          int n=Math.min(CHUNK-used,length);
          System.arraycopy(bytes,offset,buffer,used,n);
          used+=n;offset+=n;length-=n;
          if(used==CHUNK){emit(buffer,0,used);used=0;}
        }
      }
    }
    private void emit(byte[] input,int offset,int size)throws IOException{
      try{
        check(cancelled);
        int written=frame(cipher,Cipher.ENCRYPT_MODE,key,header,index++,size,total)
            .doFinal(input,offset,size,encrypted,0);
        out.writeInt(size);
        out.write(encrypted,0,written);
        total+=size;
        progress.update(total);
      }
      catch(IOException e){throw e;}catch(Exception e){throw new IOException("無法加密備份",e);}
    }
    public void flush()throws IOException{check(cancelled);out.flush();}
    public void close()throws IOException{if(closed)return;try{check(cancelled);if(used>0)emit(buffer,0,used);emit(buffer,0,0);out.flush();}finally{closed=true;Arrays.fill(key,(byte)0);Arrays.fill(buffer,(byte)0);}}
  }
  public static void encrypt(InputStream in,OutputStream output,char[] password,AtomicBoolean cancelled,Progress progress) throws Exception {
    try(Encoder writer=new Encoder(output,password,cancelled,progress)){
      byte[] buffer=new byte[CHUNK];try{int n;while((n=in.read(buffer))!=-1){check(cancelled);writer.write(buffer,0,n);}}finally{Arrays.fill(buffer,(byte)0);}
    }
  }
  /** The caller writes only into an app-private temporary file until this method fully succeeds. */
  public static void decrypt(InputStream input,OutputStream out,char[] password,AtomicBoolean cancelled,Progress progress) throws Exception {
    DataInputStream in=new DataInputStream(input);byte[] header=new byte[HEADER];in.readFully(header);
    if(!encrypted(header)||ByteBuffer.wrap(header,5,4).getInt()!=ITERATIONS)throw new BackupProblem("不支援的加密版本或參數");
    check(cancelled);byte[] key=key(password,Arrays.copyOfRange(header,9,25));long total=0;int index=0;
    byte[] encrypted=new byte[CHUNK+16], clear=new byte[CHUNK];
    try {
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      while(true) {
        check(cancelled);int size=in.readInt();
        if(total+size>MAX_BYTES)throw new BackupProblem(TOO_LARGE);
        if(size<0 || size>CHUNK || index>MAX_BYTES/CHUNK+1)throw new BackupProblem("加密備份區塊格式錯誤");
        in.readFully(encrypted,0,size+16);
        int written=frame(cipher,Cipher.DECRYPT_MODE,key,header,index++,size,total)
            .doFinal(encrypted,0,size+16,clear,0);
        if(size==0) {if(in.read()!=-1)throw new BackupProblem("備份末尾含多餘資料");check(cancelled);out.flush();return;}
        out.write(clear,0,written);total+=size;progress.update(total);
      }
    } finally {Arrays.fill(key,(byte)0);Arrays.fill(clear,(byte)0);}
  }
}
