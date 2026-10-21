package app.omymoney;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

// The standalone test-provider process intentionally uses only platform classes.
public final class OcrFixtureProvider extends ContentProvider {
    private volatile ParcelFileDescriptor writer;
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if ("blocked".equals(name)) {
            try { ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); writer = pipe[1]; return pipe[0]; }
            catch (IOException error) { throw new FileNotFoundException("pipe"); }
        }
        if (!Arrays.asList("receipt.png", "blank.png", "large.png", "rotated.jpg").contains(name)) throw new FileNotFoundException();
        int flags = mode.contains("w") ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(new File(getContext().getCacheDir(), name), flags);
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("close".equals(method)) { try { if (writer != null) writer.close(); } catch (IOException ignored) {} writer = null; }
        Bundle result = new Bundle(); result.putBoolean("opened", writer != null); return result;
    }
    @Override public String getType(Uri uri) { return uri.toString().endsWith(".jpg") ? "image/jpeg" : "image/png"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
