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
    private volatile boolean savePressed = false;   // true once Save was pressed for the current row
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
                Report.clear(this);
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
                    result = tryRow(data, i, pkg);
                }

                if (stopRequested) break;   // a half-finished row is not counted

                if (result == null) {
                    store.setSuccess(store.getSuccess() + 1);
                    store.setMessage("Row " + (i + 1) + (store.isDryRun() ? " filled (test mode)" : " saved"));
                    Report.add(this, i + 1, data.header, data.rows.get(i),
                            store.isDryRun() ? "TEST - not saved" : "SAVED", "");
                } else {
                    store.setFailed(store.getFailed() + 1);
                    store.setMessage("Row " + (i + 1) + " failed: " + result);
                    FailedLogger.add(this, data.header, data.rows.get(i), result);
                    Report.add(this, i + 1, data.header, data.rows.get(i), "FAILED", result);
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

    // Tries a row. If it fails BEFORE Save was pressed, it tries once more.
    // After Save was pressed it never tries again, so nothing is saved twice.
    private String tryRow(CsvReader.Data data, int i, String pkg) {
        String result = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            savePressed = false;
            try {
                result = fillOneRow(data, i, pkg);
            } catch (Exception e) {
                result = "Crash: " + e;
            }
            if (result == null || stopRequested || savePressed) return result;
            AppLog.add(this, "Row " + (i + 1) + " attempt " + attempt + " failed: " + result
                    + (attempt == 1 ? " - trying once more" : ""));
        }
        return result;
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
        for (int attempt = 0; attempt < 3; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                sleep(500);
                continue;
            }
            List<AccessibilityNodeInfo> nodes = allNodes(root);
            AccessibilityNodeInfo field = findField(nodes, label);
            if (field == null) {
                scrollDown(nodes);   // maybe the field is below the visible part
                sleep(600);
                continue;
            }

            showOnScreen(field);
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            boolean ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            sleep(500);

            // Read it back to be sure the text really arrived.
            AccessibilityNodeInfo root2 = getRootInActiveWindow();
            if (ok && root2 != null) {
                AccessibilityNodeInfo again = findField(allNodes(root2), label);
                if (again != null && sameValue(value, again.getText())) return null;
            }
        }
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null) {
            AppLog.add(this, "Could not fill " + label + ". Typed: " + value
                    + ". Screen text: " + shorten(screenText(allNodes(r)), 400));
        }
        return "Could not fill: " + label + " (field not found, or the app refused the value)";
    }

    // Finds the box that belongs to a label such as "Address".
    private AccessibilityNodeInfo findField(List<AccessibilityNodeInfo> nodes, String label) {
        AccessibilityNodeInfo labelNode = findByText(nodes, label);
        if (labelNode == null) return null;
        if (labelNode.isEditable()) return labelNode;   // the label is the box's own hint

        Rect lr = new Rect();
        labelNode.getBoundsInScreen(lr);
        AccessibilityNodeInfo best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isEditable()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            int distance;
            if (r.top <= lr.top + 5 && r.bottom >= lr.bottom - 5) {
                distance = 0;                       // the label is drawn inside the box
            } else if (r.top >= lr.bottom - 5) {
                distance = r.top - lr.bottom;       // the box is below the label
            } else {
                continue;                           // the box is above the label
            }
            if (distance < bestDistance) {
                best = n;
                bestDistance = distance;
            }
        }
        return best;
    }

    // Typed text and what the box now shows may differ by dashes or spaces.
    private boolean sameValue(String typed, CharSequence actual) {
        if (actual == null) return false;
        return simple(typed).equals(simple(actual.toString()));
    }

    private String simple(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String formatCnic(String raw) {
        String d = CsvReader.digits(raw);
        if (d.length() != 13) return raw;
        if (!CNIC_WITH_DASHES) return d;
        return d.substring(0, 5) + "-" + d.substring(5, 12) + "-" + d.substring(12);
    }

    private String formatContact(String raw) {
        String d = CsvReader.digits(raw);
        if (d.length() == 10) d = "0" + d;   // Excel often drops the first zero
        if (d.length() != 11) return raw;
        if (!CONTACT_WITH_SPACE) return d;
        return d.substring(0, 4) + " " + d.substring(4);
    }

    // ------------------------------------------------------------------
    // Radio button and dropdown
    // ------------------------------------------------------------------

    private String tapOther() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "No screen to read";
        AccessibilityNodeInfo other = findByText(allNodes(root), RADIO_OTHER);
        if (other == null) return "Could not find the Other option";
        click(other);
        sleep(800);
        return null;
    }

    // kg is "10" or "20".
    private String chooseBardana(String kg) {
        String wanted = kg + " Kg";
        String notWanted = kg.equals("10") ? "20 Kg" : "10 Kg";

        for (int attempt = 0; attempt < 3; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                sleep(500);
                continue;
            }
            List<AccessibilityNodeInfo> nodes = allNodes(root);
            AccessibilityNodeInfo label = findByText(nodes, LABEL_BARDANA);
            if (label == null) {
                scrollDown(nodes);
                sleep(600);
                continue;
            }

            // Exact match only, so "Total Quantity: 20 KG" is not mistaken for the dropdown.
            if (findByText(nodes, wanted) != null) return null;   // already selected

            AccessibilityNodeInfo current = findByText(nodes, notWanted);
            click(current != null ? current : label);   // open the dropdown
            sleep(1000);

            AccessibilityNodeInfo root2 = getRootInActiveWindow();
            if (root2 == null) continue;
            AccessibilityNodeInfo option = findByText(allNodes(root2), wanted);
            if (option != null) {
                click(option);
                sleep(800);
                AccessibilityNodeInfo root3 = getRootInActiveWindow();
                if (root3 != null && findByText(allNodes(root3), wanted) != null) return null;
            }
        }
        return "Could not choose Bardana " + wanted;
    }

    // ------------------------------------------------------------------
    // Save, then find out what happened
    // ------------------------------------------------------------------

    private String saveAndCheck() {
        AccessibilityNodeInfo save = null;
        for (int attempt = 0; attempt < 3 && save == null; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                sleep(500);
                continue;
            }
            List<AccessibilityNodeInfo> nodes = allNodes(root);
            save = findByText(nodes, BUTTON_SAVE);
            if (save == null) {
                scrollDown(nodes);
                sleep(600);
            }
        }
        if (save == null) return "Save button not found";

        showOnScreen(save);
        savePressed = true;
        click(save);
        sleep(1500);

        AccessibilityNodeInfo afterSave = getRootInActiveWindow();
        if (afterSave != null) {
            AppLog.add(this, "After Save the screen shows: " + shorten(screenText(allNodes(afterSave)), 300));
        }

        long end = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                sleep(500);
                continue;
            }
            List<AccessibilityNodeInfo> nodes = allNodes(root);
            String text = screenText(nodes).toLowerCase();

            String errorWord = firstWordFound(text, ERROR_WORDS);
            if (errorWord != null) {
                AppLog.add(this, "After Save the app showed an error. Screen was:");
                logScreen();
                dismissPopup(nodes);
                sleep(800);
                return "The app showed a message containing '" + errorWord + "' (see log)";
            }

            boolean successWord = firstWordFound(text, SUCCESS_WORDS) != null;
            boolean formGone = !formVisible(nodes);
            if (successWord || formGone || nameBoxIsEmpty(nodes)) {
                dismissPopup(nodes);
                sleep(800);
                return null;
            }
            sleep(700);
        }
        AppLog.add(this, "No response after Save. Screen was:");
        logScreen();
        return "No response after Save (timeout)";
    }

    private boolean nameBoxIsEmpty(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo box = findField(nodes, LABEL_NAME);
        if (box == null) return false;
        CharSequence t = box.getText();
        return t == null || t.toString().trim().isEmpty();
    }

    private void dismissPopup(List<AccessibilityNodeInfo> nodes) {
        for (String button : POPUP_BUTTONS) {
            AccessibilityNodeInfo b = findByText(nodes, button);
            if (b != null) {
                click(b);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers for reading the screen and tapping
    // ------------------------------------------------------------------

    private List<AccessibilityNodeInfo> allNodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collect(root, out, 0);
        return out;
    }

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), out, depth + 1);
        }
    }

    // All the words a node carries: its text, its description and its hint.
    private List<String> textsOf(AccessibilityNodeInfo n) {
        List<String> t = new ArrayList<>();
        if (n.getText() != null) t.add(n.getText().toString());
        if (n.getContentDescription() != null) t.add(n.getContentDescription().toString());
        if (n.getHintText() != null) t.add(n.getHintText().toString());
        return t;
    }

    private String norm(String s) {
        return s.toLowerCase().replaceAll("[*:]", " ").replaceAll("\\s+", " ").trim();
    }

    // First node whose text is exactly 'text' (ignoring capitals, stars and colons).
    private AccessibilityNodeInfo findByText(List<AccessibilityNodeInfo> nodes, String text) {
        String wanted = norm(text);
        for (AccessibilityNodeInfo n : nodes) {
            for (String t : textsOf(n)) {
                if (norm(t).equals(wanted)) return n;
            }
        }
        return null;
    }

    private String screenText(List<AccessibilityNodeInfo> nodes) {
        StringBuilder sb = new StringBuilder();
        for (AccessibilityNodeInfo n : nodes) {
            for (String t : textsOf(n)) sb.append(t).append(" | ");
        }
        return sb.toString();
    }

    private String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String firstWordFound(String lowerText, String[] words) {
        for (String w : words) {
            if (lowerText.contains(w)) return w;
        }
        return null;
    }

    private boolean scrollDown(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            if (n.isScrollable()) return n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }
        return false;
    }

    private void showOnScreen(AccessibilityNodeInfo n) {
        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
    }

    // Clicks the node, or the nearest clickable parent. If that fails, taps its centre.
    private boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int up = 0; n != null && up < 6; up++) {
            if (n.isClickable() && n.isEnabled()) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            n = n.getParent();
        }
        return tapCentre(node);
    }

    private boolean tapCentre(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 80);
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        sleep(300);
        return ok;
    }

    private boolean waitIfPaused() {
        while (paused && !stopRequested) sleep(300);
        return !stopRequested;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private void toast(final String text) {
        mainHandler.post(() -> Toast.makeText(FillerService.this, text, Toast.LENGTH_SHORT).show());
    }

    @SuppressWarnings("deprecation")
    private void keepAwake(boolean on) {
        try {
            if (on) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "autofiller:run");
                wakeLock.acquire(6 * 60 * 60 * 1000L);
            } else if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            AppLog.add(this, "Wake lock problem: " + e);
        }
    }

    // ------------------------------------------------------------------
    // Writing the screen into the log (used for tests and for failures)
    // ------------------------------------------------------------------

    private void logScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            AppLog.add(this, "(no active window)");
            return;
        }
        StringBuilder sb = new StringBuilder("SCREEN of app " + root.getPackageName() + "\n");
        dumpNode(root, 0, sb);
        AppLog.add(this, sb.toString());
    }

    private void dumpNode(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || depth > 40) return;
        String cls = shortName(n.getClassName());
        if (cls.equals("PathView") || cls.equals("GroupView") || cls.equals("SvgView")) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(shortName(n.getClassName()))
                .append(" text=").append(n.getText())
                .append(" desc=").append(n.getContentDescription())
                .append(" hint=").append(n.getHintText())
                .append(" id=").append(n.getViewIdResourceName())
                .append(n.isClickable() ? " [click]" : "")
                .append(n.isEditable() ? " [edit]" : "")
                .append(n.isScrollable() ? " [scroll]" : "")
                .append(" ").append(r.toShortString())
                .append("\n");
        for (int i = 0; i < n.getChildCount(); i++) {
            dumpNode(n.getChild(i), depth + 1, sb);
        }
    }

    private String shortName(CharSequence cls) {
        String s = String.valueOf(cls);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}
