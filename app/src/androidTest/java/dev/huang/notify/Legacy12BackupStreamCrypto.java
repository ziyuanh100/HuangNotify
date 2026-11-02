package dev.huang.notify;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.*;
import javax.crypto.spec.*;

/** HNBK2: bounded AES-GCM frames, with an authenticated terminal frame and strict EOF. */
final class Legacy12BackupStreamCrypto {
  public static final long MAX_BYTES=100L*1024*1024;
  static final int CHUNK=64*1024, ITERATIONS=210000, HEADER=33;
  public static final long MAX_FILE_BYTES=MAX_BYTES+((MAX_BYTES+CHUNK-1)/CHUNK+1)*20+HEADER;
  static final byte[] MAGIC={'H','N','B','K',2};
  public interface Progress { void update(long bytes); }
  private Legacy12BackupStreamCrypto() {}
  static void check(AtomicBoolean cancelled) throws InterruptedIOException {
    if(cancelled.get() || Thread.currentThread().isInterrupted())throw new InterruptedIOException("作業已取消");
  }
  public static boolean encrypted(byte[] prefix) {return prefix.length>=5 && Arrays.equals(Arrays.copyOf(prefix,5),MAGIC);}
  private static byte[] key(char[] password,byte[] salt) throws Exception {
    PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);
    try {return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
    finally {spec.clearPassword();}
  }
  private static Cipher frame(int mode,byte[] key,byte[] header,int index,int size,long total) throws Exception {
    byte[] nonce=ByteBuffer.allocate(12).put(header,25,8).putInt(index).array();
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
    cipher.updateAAD(ByteBuffer.allocate(HEADER+16).put(header).putInt(index).putInt(size).putLong(total).array());
    return cipher;
  }
  public static void encrypt(InputStream in,OutputStream output,char[] password,AtomicBoolean cancelled,Progress progress) throws Exception {
    if(password.length<8)throw new BackupProblem("備份密碼至少需要 8 個字元");
    check(cancelled); SecureRandom random=new SecureRandom(); byte[] salt=new byte[16],prefix=new byte[8]; random.nextBytes(salt);random.nextBytes(prefix);
    byte[] header=ByteBuffer.allocate(HEADER).put(MAGIC).putInt(ITERATIONS).put(salt).put(prefix).array(),key=key(password,salt),buffer=new byte[CHUNK];
    DataOutputStream out=new DataOutputStream(output); long total=0;int index=0;
    try {
      out.write(header);
      while(true) {
        check(cancelled);int size=0,n;
        while(size<CHUNK && (n=in.read(buffer,size,CHUNK-size))!=-1) {check(cancelled);if(n==0)continue;size+=n;}
        if(size==0)break;
        if(total+size>MAX_BYTES)throw new BackupProblem("備份超過 100 MiB，請先整理紀錄");
        out.writeInt(size);out.write(frame(Cipher.ENCRYPT_MODE,key,header,index++,size,total).doFinal(buffer,0,size));total+=size;progress.update(total);
      }
      check(cancelled);out.writeInt(0);out.write(frame(Cipher.ENCRYPT_MODE,key,header,index,0,total).doFinal());out.flush();
    } finally {Arrays.fill(key,(byte)0);Arrays.fill(buffer,(byte)0);}
  }
  /** The caller writes only into an app-private temporary file until this method fully succeeds. */
  public static void decrypt(InputStream input,OutputStream out,char[] password,AtomicBoolean cancelled,Progress progress) throws Exception {
    DataInputStream in=new DataInputStream(input);byte[] header=new byte[HEADER];in.readFully(header);
    if(!encrypted(header)||ByteBuffer.wrap(header,5,4).getInt()!=ITERATIONS)throw new BackupProblem("不支援的加密版本或參數");
    check(cancelled);byte[] key=key(password,Arrays.copyOfRange(header,9,25));long total=0;int index=0;
    try {
      while(true) {
        check(cancelled);int size=in.readInt();
        if(size<0 || size>CHUNK || total+size>MAX_BYTES || index>MAX_BYTES/CHUNK+1)throw new BackupProblem("加密備份大小或區塊格式錯誤");
        byte[] encrypted=new byte[size+16];in.readFully(encrypted);byte[] clear=frame(Cipher.DECRYPT_MODE,key,header,index++,size,total).doFinal(encrypted);
        try {
          if(size==0) {if(in.read()!=-1)throw new BackupProblem("備份末尾含多餘資料");check(cancelled);out.flush();return;}
          out.write(clear);total+=size;progress.update(total);
        } finally {Arrays.fill(clear,(byte)0);}
      }
    } finally {Arrays.fill(key,(byte)0);}
  }
}
