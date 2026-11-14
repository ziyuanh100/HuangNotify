package dev.huang.notify;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class BackupStreamCryptoTest {
  private static final char[] PASSWORD="streaming-password-中文".toCharArray();
  private static final OutputStream DISCARD=new OutputStream(){public void write(int b){}public void write(byte[] b,int o,int n){}};
  static final class Generated extends InputStream {
    long remaining,position;Generated(long size){remaining=size;}
    public int read(){if(remaining--<=0)return -1;return (int)(position++*31&255);}
    public int read(byte[] b,int o,int n){if(remaining==0)return -1;n=(int)Math.min(remaining,n);for(int i=0;i<n;i++)b[o+i]=(byte)(position++*31);remaining-=n;return n;}
  }
  private byte[] encrypt(byte[] raw)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();BackupStreamCrypto.encrypt(new ByteArrayInputStream(raw),out,PASSWORD,new AtomicBoolean(),n->{});return out.toByteArray();}
  private void decrypt(byte[] encrypted,char[] password)throws Exception{BackupStreamCrypto.decrypt(new ByteArrayInputStream(encrypted),DISCARD,password,new AtomicBoolean(),n->{});}
  @Test public void maximumBackupRoundTripUnder64MiBHeap()throws Exception{
    assertTrue("Gradle test heap must stay bounded",Runtime.getRuntime().maxMemory()<=70L*1024*1024);
    File file=File.createTempFile("hnbk2-maximum",".tmp");
    try{
      MessageDigest expected=MessageDigest.getInstance("SHA-256"),actual=MessageDigest.getInstance("SHA-256");
      try(InputStream in=new java.security.DigestInputStream(new Generated(BackupStreamCrypto.MAX_BYTES),expected);OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){BackupStreamCrypto.encrypt(in,out,PASSWORD,new AtomicBoolean(),n->{});}
      assertEquals(BackupStreamCrypto.MAX_FILE_BYTES,file.length());final long[] count={0};
      OutputStream checking=new OutputStream(){public void write(int b){actual.update((byte)b);count[0]++;}public void write(byte[] b,int o,int n){actual.update(b,o,n);count[0]+=n;}};
      try(InputStream in=new BufferedInputStream(new FileInputStream(file))){BackupStreamCrypto.decrypt(in,checking,PASSWORD,new AtomicBoolean(),n->{});}
      assertEquals(BackupStreamCrypto.MAX_BYTES,count[0]);assertArrayEquals(expected.digest(),actual.digest());
    }finally{assertTrue(file.delete());}
  }
  @Test public void rejectsOneByteOverLimitAndCancellation()throws Exception{
    assertThrows(BackupProblem.class,()->BackupStreamCrypto.encrypt(new Generated(BackupStreamCrypto.MAX_BYTES+1),DISCARD,PASSWORD,new AtomicBoolean(),n->{}));
    AtomicBoolean cancel=new AtomicBoolean();assertThrows(InterruptedIOException.class,()->BackupStreamCrypto.encrypt(new Generated(500000),DISCARD,PASSWORD,cancel,n->cancel.set(true)));
    byte[] encrypted=encrypt(new byte[140000]);cancel.set(false);assertThrows(InterruptedIOException.class,()->BackupStreamCrypto.decrypt(new ByteArrayInputStream(encrypted),DISCARD,PASSWORD,cancel,n->cancel.set(true)));
  }
  @Test public void authenticationRejectsWrongPasswordTamperingTruncationAndTrailing()throws Exception{
    byte[] raw=new byte[140000];new Random(2).nextBytes(raw);byte[] encrypted=encrypt(raw);
    assertThrows(Exception.class,()->decrypt(encrypted,"wrong-password".toCharArray()));
    for(int offset:new int[]{9,25,40,encrypted.length-1}){byte[] bad=encrypted.clone();bad[offset]^=1;assertThrows(Exception.class,()->decrypt(bad,PASSWORD));}
    for(int length:new int[]{0,32,45,encrypted.length-1,encrypted.length-20})assertThrows(Exception.class,()->decrypt(Arrays.copyOf(encrypted,length),PASSWORD));
    assertThrows(Exception.class,()->decrypt(Arrays.copyOf(encrypted,encrypted.length+1),PASSWORD));
    byte[] swapped=encrypted.clone();int frame=BackupStreamCrypto.CHUNK+20;System.arraycopy(encrypted,33+frame,swapped,33,frame);System.arraycopy(encrypted,33,swapped,33+frame,frame);
    assertThrows(Exception.class,()->decrypt(swapped,PASSWORD));
  }
  @Test public void rejectsUnboundedHeadersBeforeKdfAndUsesRandomSalt()throws Exception{
    byte[] first=encrypt(new byte[0]),second=encrypt(new byte[0]);assertFalse(Arrays.equals(first,second));decrypt(first,PASSWORD);
    byte[] bad=first.clone();bad[5]=0x7f;assertThrows(BackupProblem.class,()->decrypt(bad,PASSWORD));
    byte[] length=first.clone();ByteBuffer.wrap(length,33,4).putInt(Integer.MAX_VALUE);assertThrows(BackupProblem.class,()->decrypt(length,PASSWORD));
    assertThrows(BackupProblem.class,()->BackupStreamCrypto.encrypt(new Generated(1),DISCARD,"short".toCharArray(),new AtomicBoolean(),n->{}));
  }
}
