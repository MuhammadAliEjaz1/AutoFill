package com.autofiller.app;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

// report.csv: one line for EVERY row that was processed, with its result.
public class Report {

    public static File file(Context c) {
        return new File(c.getFilesDir(), "report.csv");
    }

    public static void clear(Context c) {
        file(c).delete();
    }

    public static void add(Context c, int rowNumber, List<String> header, List<String> row,
                           String status, String reason) {
        File f = file(c);
        boolean isNew = !f.exists();
        try (FileWriter w = new FileWriter(f, true)) {
            if (isNew) w.write("\"row_no\"," + FailedLogger.join(header) + ",\"status\",\"reason\"\n");
            w.write(rowNumber + "," + FailedLogger.join(row) + ","
                    + FailedLogger.quote(status) + "," + FailedLogger.quote(reason) + "\n");
        } catch (IOException e) {
            // nothing more we can do
        }
    }
}
