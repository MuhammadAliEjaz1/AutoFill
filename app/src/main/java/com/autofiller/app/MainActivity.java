package com.autofiller.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// The main screen: pick the CSV, pick the Govt app, and control the run.
public class MainActivity extends Activity {

    private static final int REQ_CSV = 1;
    private static final int REQ_SAVE_FAILED = 2;
    private static final int REQ_SAVE_LOG = 3;

    private RowStore store;
    private TextView tvCsv, tvGovt, tvStatus, tvCurrent, tvSuccess, tvFailed, tvMessage;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Every 0.7 seconds, copy the progress from the notebook onto the screen.
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new RowStore(this);

        tvCsv = findViewById(R.id.tvCsv);
        tvGovt = findViewById(R.id.tvGovt);
        tvStatus = findViewById(R.id.tvStatus);
        tvCurrent = findViewById(R.id.tvCurrent);
        tvSuccess = findViewById(R.id.tvSuccess);
        tvFailed = findViewById(R.id.tvFailed);
        tvMessage = findViewById(R.id.tvMessage);

        CheckBox cbDry = findViewById(R.id.cbDry);
        cbDry.setChecked(store.isDryRun());
        cbDry.setOnCheckedChangeListener((button, checked) -> store.setDryRun(checked));

        ((Button) findViewById(R.id.btnSetup)).setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class)));
        ((Button) findViewById(R.id.btnCsv)).setOnClickListener(v -> pickCsv());
        ((Button) findViewById(R.id.btnGovt)).setOnClickListener(v -> pickGovtApp());
        ((Button) findViewById(R.id.btnStart)).setOnClickListener(v -> startRun(false));
        ((Button) findViewById(R.id.btnResume)).setOnClickListener(v -> startRun(true));
        ((Button) findViewById(R.id.btnPause)).setOnClickListener(v -> {
            if (FillerService.instance != null) FillerService.instance.pause();
        });
        ((Button) findViewById(R.id.btnStop)).setOnClickListener(v -> {
            if (FillerService.instance != null) FillerService.instance.stop();
        });
        ((Button) findViewById(R.id.btnSaveFailed)).setOnClickListener(v ->
                saveFile(REQ_SAVE_FAILED, "failed.csv", "text/csv"));
        ((Button) findViewById(R.id.btnSaveLog)).setOnClickListener(v ->
                saveFile(REQ_SAVE_LOG, "autofiller_log.txt", "text/plain"));
        ((Button) findViewById(R.id.btnDump)).setOnClickListener(v -> {
            if (FillerService.instance == null) {
                toast("Turn ON AutoFiller in Accessibility settings first");
                return;
            }
                        toast("Open the Govt app now. The screen is read in 15 seconds.");
            FillerService.instance.dumpLater(15000);
        });

        showCsvInfo(false);
        showGovtInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    // ------------------------------------------------------------------
    // Choosing the files and the app
    // ------------------------------------------------------------------

    private void pickCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/csv", "text/comma-separated-values", "text/plain",
                "application/csv", "application/vnd.ms-excel"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_CSV);
    }

    private void pickGovtApp() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        Collections.sort(apps, new ResolveInfo.DisplayNameComparator(pm));

        final List<String> labels = new ArrayList<>();
        final List<String> packages = new ArrayList<>();
        for (ResolveInfo info : apps) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;   // not AutoFiller itself
            labels.add(info.loadLabel(pm).toString());
            packages.add(pkg);
        }

        new AlertDialog.Builder(this)
                .setTitle("Select the Govt app")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    store.setGovt(packages.get(which), labels.get(which));
                    showGovtInfo();
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        if (requestCode == REQ_CSV) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                // some pickers do not offer this; the file still works for now
            }
            store.setCsvUri(uri.toString());
            store.setCsvName(fileNameOf(uri));
            FailedLogger.clear(this);
            showCsvInfo(true);
        } else if (requestCode == REQ_SAVE_FAILED) {
            boolean ok = AppLog.copyToUri(this, FailedLogger.file(this), uri);
            toast(ok ? "failed.csv saved" : "Could not save failed.csv");
        } else if (requestCode == REQ_SAVE_LOG) {
            boolean ok = AppLog.copyToUri(this, AppLog.file(this), uri);
            toast(ok ? "Log saved" : "Could not save the log");
        }
    }

    private String fileNameOf(Uri uri) {
        String name = "file.csv";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception e) {
            // keep the default name
        }
        return name;
    }

    private void saveFile(int request, String name, String mime) {
        File source = request == REQ_SAVE_FAILED ? FailedLogger.file(this) : AppLog.file(this);
        if (!source.exists()) {
            toast(request == REQ_SAVE_FAILED ? "No failed rows yet" : "The log is empty");
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, request);
    }

    // ------------------------------------------------------------------
    // Showing information
    // ------------------------------------------------------------------

    // Reads the CSV and shows the row count or the problems. If 'reset', also clears the progress.
    private void showCsvInfo(boolean reset) {
        String uriText = store.getCsvUri();
        if (uriText.isEmpty()) {
            tvCsv.setText("No file selected");
            return;
        }
        StringBuilder sb = new StringBuilder(store.getCsvName());
        try {
            CsvReader.Data d = CsvReader.load(getContentResolver(), Uri.parse(uriText));
            if (!d.columnsOk) {
                sb.append("\nERROR:");
                for (String p : d.problems) sb.append("\n- ").append(p);
            } else {
                sb.append("\n").append(d.rows.size()).append(" rows found");
                if (!d.problems.isEmpty()) {
                    sb.append("\n").append(d.problems.size())
                            .append(" row(s) have problems and will be skipped:");
                    for (int i = 0; i < d.problems.size() && i < 5; i++) {
                        sb.append("\n- ").append(d.problems.get(i));
                    }
                    if (d.problems.size() > 5) sb.append("\n- ...");
                }
                if (reset) store.resetProgress(d.rows.size());
            }
        } catch (Exception e) {
            sb.append("\nERROR: could not read the file (").append(e.getMessage()).append(")");
        }
        tvCsv.setText(sb.toString());
    }

    private void showGovtInfo() {
        if (store.getGovtPackage().isEmpty()) {
            tvGovt.setText("No app selected");
        } else {
            tvGovt.setText(store.getGovtLabel());
        }
    }

    private void updateProgress() {
        String status = store.getStatus();
        FillerService s = FillerService.instance;
        boolean engineBusy = s != null && s.isRunning();
        if ((status.equals("Running") || status.equals("Paused")) && !engineBusy) {
            status = "Stopped (press RESUME to continue)";   // the phone restarted or the app crashed
        }
        tvStatus.setText("Status: " + status);
        tvCurrent.setText("Current Row: " + store.getCurrent() + " / " + store.getTotal());
        tvSuccess.setText("Success: " + store.getSuccess());
        tvFailed.setText("Failed: " + store.getFailed());
        tvMessage.setText(store.getMessage());
    }

    // ------------------------------------------------------------------
    // START and RESUME
    // ------------------------------------------------------------------

    private void startRun(final boolean resume) {
        final FillerService s = FillerService.instance;
        if (s == null) {
            toast("Turn ON AutoFiller in Accessibility settings first");
            startActivity(new Intent(this, SetupActivity.class));
            return;
        }
        if (store.getCsvUri().isEmpty()) {
            toast("Select a CSV file first");
            return;
        }
        if (store.getGovtPackage().isEmpty()) {
            toast("Select the Govt app first");
            return;
        }
        if (s.isRunning()) {
            if (resume) s.unpause();
            else toast("Already running. Press STOP first.");
            return;
        }

        // We are on screen right now, so Android lets us open the Govt app from here.
        Intent launch = getPackageManager().getLaunchIntentForPackage(store.getGovtPackage());
        if (launch != null) startActivity(launch);
        store.setStatus("Starting...");

        handler.postDelayed(() -> {
            FillerService x = FillerService.instance;
            if (x != null) x.startRun(resume);
        }, 2000);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
