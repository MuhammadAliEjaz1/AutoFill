package com.autofiller.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

// The engine. It reads the screen of the Govt app and types into it, like a very patient person.
public class FillerService extends AccessibilityService {

    // ------------------------------------------------------------------
    // Words used by the Govt app. If the app uses different words, change them here.
    // ------------------------------------------------------------------
    static final String FORM_TITLE = "Selling Details";
    static final String DASHBOARD_MARKER = "Stock Available";
    static final String LABEL_NAME = "Citizen Name";
    static final String LABEL_CNIC = "CNIC No";
    static final String LABEL_CONTACT = "Contact No";
    static final String LABEL_ADDRESS = "Address";
    static final String LABEL_BARDANA = "Bardana Type";
    static final String LABEL_BAGS = "No of Bags";
    static final String BUTTON_SAVE = "Save";
    static final String RADIO_OTHER = "Other";
    static final String[] ERROR_WORDS = {"error", "failed", "invalid", "already", "exceed",
            "unable", "try again", "not allowed", "not found"};
    static final String[] SUCCESS_WORDS = {"success", "saved", "submitted", "recorded"};
    static final String[] POPUP_BUTTONS = {"OK", "Okay", "Close", "Dismiss"};

    // Which card on the dashboard opens the form (0 = the first one, top left).
    static final int CARD_NUMBER = 0;

    // Type CNIC as 12345-1234567-1 and contact as 0300 1234567 (the way the app shows them).
    static final boolean CNIC_WITH_DASHES = true;
    static final boolean CONTACT_WITH_SPACE = true;

    // ------------------------------------------------------------------

    public static FillerService instance;

    private RowStore store;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile boolean stopRequested = false;
    private PowerManager.WakeLock wakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        store = new RowStore(this);
        toast("AutoFiller service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopRequested = true;
        instance = null;
        return super.onUnbind(intent);
    }

    // ------------------------------------------------------------------
    // Controls used by the buttons on the main screen
    // ------------------------------------------------------------------

    public boolean isRunning() {
        return running;
    }

    public void startRun(final boolean resume) {
        if (running) return;
        running = true;
        paused = false;
        stopRequested = false;
        new Thread(() -> runAll(resume)).start();
    }

    public void pause() {
        if (running) {
            paused = true;
            store.setStatus("Paused");
        }
    }

    public void unpause() {
        if (running) {
            paused = false;
            store.setStatus("Running");
        }
    }

    public void stop() {
        stopRequested = true;
        paused = false;
    }

    // Reads the screen that is showing in 'delayMs' milliseconds and saves it in the log.
    public void dumpLater(final long delayMs) {
        new Thread(() -> {
            sleep(delayMs);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                AppLog.add(this, "DUMP: no active window");
                toast("Could not read the screen");
                return;
            }
            StringBuilder sb = new StringBuilder("DUMP of app " + root.getPackageName() + "\n");
            dumpNode(root, 0, sb);
            AppLog.add(this, sb.toString());
            toast("Screen saved in the log");
        }).start();
    }

    // ------------------------------------------------------------------
    // The main loop: one CSV row after another
    // ------------------------------------------------------------------

    private void runAll(boolean resume) {
        try {
            String uriText = store.getCsvUri();
            String pkg = store.getGovtPackage();
            if (uriText.isEmpty() || pkg.isEmpty()) {
                store.setStatus("Select the CSV and the Govt app first");
                return;
            }

            CsvReader.Data data = CsvReader.load(getContentResolver(), Uri.parse(uriText));
            if (!data.columnsOk) {
                store.setStatus("The CSV has errors");
                return;
            }

            int start = 0;
            if (resume) {
                start = store.getNext();
            } else {
                store.resetProgress(data.rows.size());
                FailedLogger.clear(this);
            }
            store.setTotal(data.rows.size());
            if (start >= data.rows.size()) {
                store.setStatus("Finished");
                return;
            }

            keepAwake(true);
            store.setStatus("Running");
            AppLog.add(this, "Run started at row " + (start + 1) + " of " + data.rows.size()
                    + (store.isDryRun() ? " (test mode)" : ""));

            for (int i = start; i < data.rows.size(); i++) {
                if (!waitIfPaused()) break;
                store.setCurrent(i + 1);

                String result;
                String rowProblem = data.rowError.get(i);
                if (rowProblem != null) {
                    result = rowProblem;
                } else {
                    try {
                        result = fillOneRow(data, i, pkg);
                    } catch (Exception e) {
                        result = "Crash: " + e;
                    }
                }

                if (stopRequested) break;   // a half-finished row is not counted

                if (result == null) {
                    store.setSuccess(store.getSuccess() + 1);
                    store.setMessage("Row " + (i + 1) + " done");
                } else {
                    store.setFailed(store.getFailed() + 1);
                    store.setMessage("Row " + (i + 1) + " failed: " + result);
                    FailedLogger.add(this, data.header, data.rows.get(i), result);
                    AppLog.add(this, "Row " + (i + 1) + " FAILED: " + result);
                }
                store.setNext(i + 1);
            }

            store.setStatus(stopRequested ? "Stopped" : "Finished");
            AppLog.add(this, "Run ended: " + store.getStatus());
        } catch (Exception e) {
            store.setStatus("Error: " + e.getMessage());
            AppLog.add(this, "Run error: " + e);
        } finally {
            keepAwake(false);
            running = false;
        }
    }

    // Fills one row. Returns null if it worked, otherwise the reason it failed.
    private String fillOneRow(CsvReader.Data d, int i, String pkg) {
        String name = d.get(i, d.idxName);
        String cnic = formatCnic(d.get(i, d.idxCnic));
        String contact = formatContact(d.get(i, d.idxContact));
        String address = d.get(i, d.idxAddress);
        String bardana = CsvReader.digits(d.get(i, d.idxBardana));
        String bags = CsvReader.digits(d.get(i, d.idxBags));
        boolean isOther = d.get(i, d.idxType).equalsIgnoreCase("other");

        if (!goToForm(pkg)) return stopRequested ? "Stopped" : "Could not open the form";

        String e;
        e = fillText(LABEL_NAME, name);
        if (e != null) return e;
        if (!waitIfPaused()) return "Stopped";

        e = fillText(LABEL_CNIC, cnic);
        if (e != null) return e;
        if (!waitIfPaused()) return "Stopped";

        e = fillText(LABEL_CONTACT, contact);
        if (e != null) return e;
        if (!waitIfPaused()) return "Stopped";

        e = fillText(LABEL_ADDRESS, address);
        if (e != null) return e;
        if (!waitIfPaused()) return "Stopped";

        if (isOther) {
            e = tapOther();
            if (e != null) return e;
        }

        e = chooseBardana(bardana);
        if (e != null) return e;
        if (!waitIfPaused()) return "Stopped";

        e = fillText(LABEL_BAGS, bags);
        if (e != null) return e;
        if (!waitIfPaused()) return "Stopped";

        if (store.isDryRun()) {
            AppLog.add(this, "Row " + (i + 1) + ": test mode, Save NOT pressed");
            sleep(1500);
            return null;
        }
        return saveAndCheck();
    }

    // ------------------------------------------------------------------
    // Getting to the form
    // ------------------------------------------------------------------

    private boolean goToForm(String pkg) {
        long end = System.currentTimeMillis() + 30000;
        long lastBack = System.currentTimeMillis();
        while (System.currentTimeMillis() < end) {
            if (!waitIfPaused()) return false;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            String rootPkg = root == null ? "" : String.valueOf(root.getPackageName());
            if (root == null || !rootPkg.equals(pkg)) {
                openGovtApp(pkg);
                sleep(2500);
                continue;
            }

            List<AccessibilityNodeInfo> nodes = allNodes(root);
            if (formVisible(nodes)) return true;

            if (screenText(nodes).toLowerCase().contains(DASHBOARD_MARKER.toLowerCase())) {
                AccessibilityNodeInfo card = findCard(nodes);
                if (card != null) {
                    click(card);
                    sleep(2000);
                    continue;
                }
            }

            // Some other screen: wait, and after 8 seconds press Back once.
            if (System.currentTimeMillis() - lastBack > 8000) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastBack = System.currentTimeMillis();
            }
            sleep(700);
        }
        AppLog.add(this, "goToForm timed out. Screen was:");
        logScreen();
        return false;
    }

    private void openGovtApp(String pkg) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
            } catch (Exception e) {
                AppLog.add(this, "Could not open the Govt app: " + e);
            }
        }
    }

    // The cards have only icons, so we find them by size and position:
    // clickable, not too small, not as wide as the screen. Sorted top to bottom, left to right.
    private AccessibilityNodeInfo findCard(List<AccessibilityNodeInfo> nodes) {
        Rect screen = new Rect();
        nodes.get(0).getBoundsInScreen(screen);
        int minSide = screen.width() / 5;
        int maxWidth = screen.width() * 6 / 10;

        List<AccessibilityNodeInfo> cards = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isClickable()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() < minSide || r.height() < minSide) continue;
            if (r.width() > maxWidth) continue;
            cards.add(n);
        }
        cards.sort((a, b) -> Long.compare(position(a), position(b)));
        if (cards.size() > CARD_NUMBER) return cards.get(CARD_NUMBER);
        return null;
    }

    private long position(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return (long) r.top * 10000L + r.left;
    }

    private boolean formVisible(List<AccessibilityNodeInfo> nodes) {
        return findByText(nodes, FORM_TITLE) != null || findByText(nodes, LABEL_NAME) != null;
    }

    // ------------------------------------------------------------------
    // Filling the fields
    // ------------------------------------------------------------------

    private String fillText(String label, String value) {
        for
