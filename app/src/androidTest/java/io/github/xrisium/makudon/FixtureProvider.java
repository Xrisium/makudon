package io.github.xrisium.makudon;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Runs in the separate test APK process: platform Java only, no target-app Kotlin classloader assumption. */
public class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private byte[] data(Uri uri) {
        if ("invalid".equals(uri.getLastPathSegment())) return new byte[] {(byte) 0xff, (byte) 0xfe, 0};
        return "\uFEFF# 模拟器导入\r\n\r\n独立副本 😀\r\n  保留空格\r\n".getBytes(StandardCharsets.UTF_8);
    }
    @Override public String getType(Uri uri) { return "markdown".equals(uri.getLastPathSegment()) ? "text/markdown" : "text/plain"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) values[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? ("markdown".equals(uri.getLastPathSegment()) ? "模拟器导入.md" : "测试文件.txt") : data(uri).length;
        cursor.addRow(values); return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getLastPathSegment();
        if (!"markdown".equals(path) && !"invalid".equals(path) && !"export".equals(path)) throw new FileNotFoundException();
        if (getContext() == null) throw new FileNotFoundException();
        File directory = getContext().getCacheDir();
        if ("export".equals(uri.getLastPathSegment())) return ParcelFileDescriptor.open(new File(directory, "workspace-fixture-export.txt"), "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY : ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only source");
        File file = new File(directory, "workspace-fixture-" + uri.getLastPathSegment() + ".txt");
        if (!file.exists()) try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(data(uri)); }
        catch (IOException e) { throw new FileNotFoundException("Fixture initialization failed"); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
