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
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// The main screen: pick the CSV, pick the Govt app, and control the run.
public class MainActivity extends Activity {

    // The Govt app is found automatically if it is installed.
    private static final String GOVT_PACKAGE = "com.ictapp.kp.foodapp";

    private static final int REQ_CSV = 1;
    private static final int REQ_SAVE_FAILED = 2;
    private static final int REQ_SAVE_LOG = 3;
    private static final int REQ_SAVE_REPORT = 4;

    private RowStore store;
    private TextView tvCsv, tvGovt, tvStatus, tvCurrent, tvSuccess, tvFailed, tvMessage, tvModeNote;
    private Button btnStart, btnPause, btnResume, btnStop;
    private ProgressBar progressBar;
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
        tvModeNote = findViewById(R.id.tvModeNote);
        progressBar = findViewById(R.id.progressBar);
        btnStart = findViewById(R.id.btnStart);
        btnPause = findViewById(R.id.btnPause);
        btnResume = findViewById(R.id.btnResume);
        btnStop = findViewById(R.id.btnStop);

        Switch cbDry = findViewById(R.id.cbDry);
        cbDry.setChecked(store.isDryRun());
        cbDry.setOnCheckedChangeListener((button, checked) -> {
            store.setDryRun(checked);
            showModeNote();
        });
        showModeNote();

        findViewById(R.id.btnSetup).setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class)));
        findViewById(R.id.btnCsv).setOnClickListener(v -> pickCsv());
        findViewById(R.id.btnGovt).setOnClickListener(v -> pickGovtApp());
        btnStart.setOnClickListener(v -> startRun(false));
        btnResume.setOnClickListener(v -> startRun(true));
        btnPause.setOnClickListener(v -> {
            if (FillerService.instance != null) FillerService.instance.pause();
        });
        btnStop.setOnClickListener(v -> {
            if (FillerService.instance != null) FillerService.instance.stop();
        });
        findViewById(R.id.btnSaveReport).setOnClickListener(v -> saveFile(REQ_SAVE_REPORT));
        findViewById(R.id.btnSaveFailed).setOnClickListener(v -> saveFile(REQ_SAVE_FAILED));
        findViewById(R.id.btnSaveLog).setOnClickListener(v -> saveFile(REQ_SAVE_LOG));
        findViewById(R.id.btnDump).setOnClickListener(v -> {
            if (FillerService.instance == null) {
                toast("Turn ON AutoFiller in Accessibility settings first");
                return;
            }
            toast("Open the Govt app now. The screen is read in 15 seconds.");
            FillerService.instance.dumpLater(15000);
        });

        autoDetectGovtApp();
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

    // If the Govt app is installed and nothing is chosen yet, choose it automatically.
    private void autoDetectGovtApp() {
        if (!store.getGovtPackage().isEmpty()) return;
        PackageManager pm = getPackageManager();
        if (pm.getLaunchIntentForPackage(GOVT_PACKAGE) == null) return;
        try {
            String label = pm.getApplicationLabel(pm.getApplicationInfo(GOVT_PACKAGE, 0)).toString();
            store.setGovt(GOVT_PACKAGE, label);
        } catch (Exception e) {
            store.setGovt(GOVT_PACKAGE, "Govt app");
        }
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
            Report.clear(this);
            showCsvInfo(true);
        } else if (requestCode == REQ_SAVE_FAILED
                || requestCode == REQ_SAVE_LOG
                || requestCode == REQ_SAVE_REPORT) {
            boolean ok = AppLog.copyToUri(this, sourceFor(requestCode), uri);
            toast(ok ? "Saved" : "Could not save the file");
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

    private File sourceFor(int request) {
        if (request == REQ_SAVE_FAILED) return FailedLogger.file(this);
        if (request == REQ_SAVE_REPORT) return Report.file(this);
        return AppLog.file(this);
    }

    private void saveFile(int request) {
        if (!sourceFor(request).exists()) {
            toast("Nothing to save yet");
            return;
        }
        String name = "autofiller_log.txt";
        String mime = "text/plain";
        if (request == REQ_SAVE_FAILED) {
            name = "failed.csv";
            mime = "text/csv";
        } else if (request == REQ_SAVE_REPORT) {
            name = "report.csv";
            mime = "text/csv";
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

    private void showModeNote() {
        if (store.isDryRun()) {
            tvModeNote.setText("ON: the form is filled but Save is NOT pressed (practice run).");
            tvModeNote.setTextColor(getColor(R.color.orange));
        } else {
            tvModeNote.setText("OFF: Save WILL be pressed for every row (real run).");
            tvModeNote.setTextColor(getColor(R.color.red));
        }
    }

    // Reads the CSV and shows the row count or the problems. If 'reset', also clears the progress.
    private void showCsvInfo(boolean reset) {
        String uriText = store.getCsvUri();
        if (uriText.isEmpty()) {
            tvCsv.setText("No file selected");
            tvCsv.setTextColor(getColor(R.color.text_soft));
            return;
        }
        StringBuilder sb = new StringBuilder(store.getCsvName());
        boolean error = false;
        try {
            CsvReader.Data d = CsvReader.load(getContentResolver(), Uri.parse(uriText));
            if (!d.columnsOk) {
                error = true;
                sb.append("\nERROR:");
                for (String p : d.problems) sb.append("\n- ").append(p);
            } else {
                sb.append("\n").append(d.rows.size()).append(" rows found");
                if (!d.problems.isEmpty()) {
                    sb.append("\n").append(d.problems.size())
                            .append(" row(s) have problems and will be skipped:");
                    for (int i = 0; i < d.problems.size() && i < 3; i++) {
                        sb.append("\n- ").append(d.problems.get(i));
                    }
                    if (d.problems.size() > 3) sb.append("\n- ...");
                }
                if (reset) store.resetProgress(d.rows.size());
            }
        } catch (Exception e) {
            error = true;
            sb.append("\nERROR: could not read the file (").append(e.getMessage()).append(")");
        }
        tvCsv.setText(sb.toString());
        tvCsv.setTextColor(getColor(error ? R.color.red : R.color.text_dark));
    }

    private void showGovtInfo() {
        if (store.getGovtPackage().isEmpty()) {
            tvGovt.setText("No app selected");
            tvGovt.setTextColor(getColor(R.color.red));
        } else {
            tvGovt.setText(store.getGovtLabel());
            tvGovt.setTextColor(getColor(R.color.text_dark));
        }
    }

    private void updateProgress() {
        String status = store.getStatus();
        FillerService s = FillerService.instance;
        boolean busy = s != null && s.isRunning();
        boolean paused = busy && status.equals("Paused");
        if ((status.equals("Running") || status.equals("Paused")) && !busy) {
            status = "Stopped";   // the phone restarted or the app crashed: RESUME continues
        }

        int total = store.getTotal();
        int done = store.getSuccess() + store.getFailed();

        tvStatus.setText(status);
        tvStatus.getBackground().mutate().setTint(getColor(chipColor(status)));
        progressBar.setMax(Math.max(total, 1));
        progressBar.setProgress(done);
        tvCurrent.setText("Row " + store.getCurrent() + " of " + total);
        tvSuccess.setText(String.valueOf(store.getSuccess()));
        tvFailed.setText(String.valueOf(store.getFailed()));
        tvMessage.setText(store.getMessage());

        boolean canResume = busy ? paused : (store.getNext() > 0 && store.getNext() < total);
        look(btnStart, !busy);
        look(btnPause, busy && !paused);
        look(btnResume, canResume);
        look(btnStop, busy);
    }

    private int chipColor(String status) {
        if (status.equals("Running")) return R.color.green;
        if (status.equals("Paused")) return R.color.orange;
        if (status.equals("Finished")) return R.color.blue;
        if (status.equals("Ready")) return R.color.text_soft;
        if (status.equals("Starting...")) return R.color.blue;
        return R.color.red;   // Stopped, errors
    }

    // Faded buttons are the ones that do nothing right now.
    private void look(Button b, boolean enabled) {
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.35f);
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

        if (store.isDryRun()) {
            launchRun(resume);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Real run")
                    .setMessage("Test mode is OFF. AutoFiller will press Save in the Govt app for each row of "
                            + store.getCsvName() + ".\n\nMake sure the CSV has real, correct data.")
                    .setPositiveButton("Yes, start", (d, w) -> launchRun(resume))
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private void launchRun(final boolean resume) {
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
