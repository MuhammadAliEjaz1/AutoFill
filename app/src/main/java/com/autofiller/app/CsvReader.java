package com.autofiller.app;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

// Reads the CSV file, handles commas inside quotes, and checks the data.
public class CsvReader {

    // Everything we learn from one CSV file.
    public static class Data {
        public List<String> header = new ArrayList<>();
        public List<List<String>> rows = new ArrayList<>();
        public List<String> rowError = new ArrayList<>();   // null means the row is fine
        public List<String> problems = new ArrayList<>();   // messages shown to the user
        public boolean columnsOk = true;
        public int idxName = -1, idxCnic = -1, idxContact = -1, idxAddress = -1;
        public int idxBardana = -1, idxBags = -1, idxType = -1;

        // Returns the trimmed value of one cell, or "" if it does not exist.
        public String get(int row, int col) {
            if (col < 0) return "";
            List<String> r = rows.get(row);
            if (col >= r.size()) return "";
            return r.get(col).trim();
        }
    }

    public static Data load(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("Cannot open the file");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        }
        String text = sb.toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);   // Excel adds an invisible mark
        return parseText(text);
    }

    public static Data parseText(String text) {
        Data d = new Data();
        List<List<String>> all = splitRows(text);
        if (all.isEmpty()) {
            d.columnsOk = false;
            d.problems.add("The file is empty");
            return d;
        }

        for (String h : all.get(0)) d.header.add(h.trim());

        d.idxName = find(d.header, "name", "citizen name", "citizenname");
        d.idxCnic = find(d.header, "cnic", "cnic no", "cnicno");
        d.idxContact = find(d.header, "contact", "contact no", "contactno", "phone", "mobile");
        d.idxAddress = find(d.header, "address");
        d.idxBardana = find(d.header, "bardana", "bardana type", "bardanatype");
        d.idxBags = find(d.header, "bags", "no of bags", "noofbags");
        d.idxType = find(d.header, "citizentype", "citizen type", "type");   // optional

        require(d, d.idxName, "Name");
        require(d, d.idxCnic, "CNIC");
        require(d, d.idxContact, "Contact");
        require(d, d.idxAddress, "Address");
        require(d, d.idxBardana, "Bardana");
        require(d, d.idxBags, "Bags");
        if (!d.columnsOk) return d;

        for (int i = 1; i < all.size(); i++) {
            d.rows.add(all.get(i));
            int idx = d.rows.size() - 1;
            String e = checkRow(d, idx);
            if (e == null) {
                d.rowError.add(null);
            } else {
                String msg = "Row " + (idx + 1) + ": " + e;
                d.rowError.add(msg);
                d.problems.add(msg);
            }
        }
        if (d.rows.isEmpty()) {
            d.columnsOk = false;
            d.problems.add("The file has a header but no data rows");
        }
        return d;
    }

    // Returns a short problem text, or null if the row is fine.
    private static String checkRow(Data d, int idx) {
        if (d.get(idx, d.idxName).isEmpty()) return "Name is empty";
        int cnicDigits = digits(d.get(idx, d.idxCnic)).length();
        if (cnicDigits != 13) return "CNIC must have 13 digits";
        int contactDigits = digits(d.get(idx, d.idxContact)).length();
        if (contactDigits != 10 && contactDigits != 11) return "Contact must have 11 digits";
        if (d.get(idx, d.idxAddress).isEmpty()) return "Address is empty";
        String bardana = digits(d.get(idx, d.idxBardana));
        if (!bardana.equals("10") && !bardana.equals("20")) return "Bardana must be 10 or 20";
        String bags = digits(d.get(idx, d.idxBags));
        if (bags.isEmpty() || Integer.parseInt(bags) < 1) return "Bags must be a number (1 or more)";
        return null;
    }

    public static String digits(String s) {
        return s.replaceAll("\\D", "");
    }

    private static void require(Data d, int index, String columnName) {
        if (index < 0) {
            d.columnsOk = false;
            d.problems.add("Missing column: " + columnName);
        }
    }

    private static int find(List<String> header, String... names) {
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).toLowerCase().replace("_", " ").trim();
            for (String n : names) {
                if (h.equals(n)) return i;
            }
        }
        return -1;
    }

    // Splits the whole text into rows and cells. Commas inside "quotes" stay inside the cell.
    static List<List<String>> splitRows(String text) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');   // two quotes in a row mean one quote character
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString());
                    cell.setLength(0);
                    addIfNotBlank(out, row);
                    row = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            addIfNotBlank(out, row);
        }
        return out;
    }

    private static void addIfNotBlank(List<List<String>> out, List<String> row) {
        boolean blank = true;
        for (String c : row) {
            if (!c.trim().isEmpty()) blank = false;
        }
        if (!blank) out.add(row);
    }
}
