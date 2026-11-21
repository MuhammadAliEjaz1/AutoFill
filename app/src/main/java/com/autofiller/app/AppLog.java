package com.autofiller.app;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// A simple text log, plus a helper that copies a file to a place the user picks.
public class AppLog {

    public static File file(Context c) {
        return new File(c.getFilesDir(), "log.txt");
    }

    public static synchronized void add(Context c, String line) {
        File f = file(c);
        if (f.length() > 1000000) f.delete();   // keep the log from growing forever
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(time + "  " + line + "\n");
        } catch (IOException e) {
            // nothing more we can do
        }
    }

    public static boolean copyToUri(Context c, File src, Uri dest) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = c.getContentResolver().openOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
