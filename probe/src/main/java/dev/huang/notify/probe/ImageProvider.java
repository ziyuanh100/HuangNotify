package dev.huang.notify.probe;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;

/** Test-only provider. Read access is granted through the OS notification URI grants. */
public final class ImageProvider extends ContentProvider {
  @Override public boolean onCreate(){return true;}
  @Override public String getType(Uri uri){return "image/png";}
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
    if(!"r".equals(mode) || !"/picture".equals(uri.getPath()))throw new FileNotFoundException();
    return ParcelFileDescriptor.open(new File(getContext().getCacheDir(),"notification-picture.png"),ParcelFileDescriptor.MODE_READ_ONLY);
  }
  @Override public Cursor query(Uri uri,String[] p,String s,String[] a,String o){return null;}
  @Override public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
  @Override public int update(Uri uri,ContentValues v,String s,String[] a){return 0;}
  @Override public int delete(Uri uri,String s,String[] a){return 0;}
}
