package net.jamesjennison.klippercompanion;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
/** Test APK only: one immutable synthetic document. No user-file access. */
public class GcodeFixtureProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 private void validate(Uri uri){if(!"/sample".equals(uri.getPath()) && !"/wrong".equals(uri.getPath()) && !"/bambu".equals(uri.getPath()))throw new IllegalArgumentException("Unknown fixture");}
 public String getType(Uri uri){validate(uri);return "application/octet-stream";}
 public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){validate(uri);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});c.addRow(new Object[]{"/wrong".equals(uri.getPath())?"wrong.pdf":"/bambu".equals(uri.getPath())?"fixture.gcode.3mf":"fixture.gcode"});return c;}
 public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
  validate(uri);if(!"r".equals(mode)||"/wrong".equals(uri.getPath()))throw new FileNotFoundException("Synthetic document is read-only; wrong type must not open");
  File file=new File(getContext().getCacheDir(),"synthetic-document.gcode");
  synchronized(this){if(!file.exists())try(FileOutputStream out=new FileOutputStream(file)){out.write("G90\nM83\nG0 X0 Y0 Z0.2\nG1 X10 E1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(IOException e){throw new FileNotFoundException("Fixture unavailable");}}
  return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
 }
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
 public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
