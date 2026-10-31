package dev.huang.notify;

import android.database.*;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;

/** Debug-only private documents provider, never packaged in release APKs. */
public final class BackupTestDocuments extends DocumentsProvider {
  private File root() { File dir=new File(getContext().getCacheDir(),"test-documents"); dir.mkdirs(); return dir; }
  private File file(String id) throws FileNotFoundException {
    if (id.equals("root")) return root();
    if (!id.matches("[A-Za-z0-9._-]+")) throw new FileNotFoundException();
    return new File(root(),id);
  }
  @Override public boolean isChildDocument(String parent,String child) { return parent.equals("root") && !child.equals("root") && child.matches("[A-Za-z0-9._-]+"); }
  @Override public boolean onCreate() { return true; }
  private static final String[] COLS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE};
  private void row(MatrixCursor c,File f) { Object[] row=new Object[c.getColumnCount()];
    for(int i=0;i<row.length;i++) switch(c.getColumnNames()[i]) {
      case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row[i]=f.equals(root())?"root":f.getName(); break;
      case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row[i]=f.getName(); break;
      case DocumentsContract.Document.COLUMN_MIME_TYPE: row[i]=f.isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream"; break;
      case DocumentsContract.Document.COLUMN_FLAGS: row[i]=DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE|DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE; break;
      case DocumentsContract.Document.COLUMN_SIZE: row[i]=f.length(); break;
    } c.addRow(row);
  }
  @Override public Cursor queryRoots(String[] p) { return new MatrixCursor(p==null?new String[]{DocumentsContract.Root.COLUMN_ROOT_ID}:p); }
  @Override public Cursor queryDocument(String id,String[] p) throws FileNotFoundException { MatrixCursor c=new MatrixCursor(p==null?COLS:p); row(c,file(id)); return c; }
  @Override public Cursor queryChildDocuments(String id,String[] p,String sort) throws FileNotFoundException { MatrixCursor c=new MatrixCursor(p==null?COLS:p); File[] files=file(id).listFiles(); if(files!=null) for(File f:files) row(c,f); return c; }
  @Override public String createDocument(String parent,String mime,String name) throws FileNotFoundException { File f=file(name); try { if(!f.createNewFile()) throw new IOException(); return name; } catch(IOException e) { throw new FileNotFoundException(); } }
  @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException { return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode)); }
  @Override public void deleteDocument(String id) throws FileNotFoundException { if(id.equals("root") || !file(id).delete()) throw new FileNotFoundException(); }
}
