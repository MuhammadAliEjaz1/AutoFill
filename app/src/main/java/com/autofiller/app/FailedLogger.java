package com.autofiller.app;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

// Keeps failed.csv: the rows that could not be filled, with the reason.
public class FailedLogger {

    public static File file(Context c) {
        return new File(c.getFilesDir(), "failed.csv");
    }

    public static void clear(Context c) {
        file(c).delete();
    }

    public static void add(Context c, List<String> header, List<String> row, String reason) {
        File f = file(c);
        boolean isNew = !f.exists();
        try (FileWriter w = new FileWriter(f, true)) {
            if (isNew) w.write(join(header) + ",reason\n");
            w.write(join(row) + "," + quote(reason) + "\n");
        } catch (IOException e) {
            // nothing more we can do
        }
    }

    // Puts every cell in quotes so commas inside the address stay safe.
    private static String join(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(cells.get(i)));
        }
        return sb.toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
