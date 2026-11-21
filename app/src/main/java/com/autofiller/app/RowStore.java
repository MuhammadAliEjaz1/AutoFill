package com.autofiller.app;

import android.content.Context;
import android.content.SharedPreferences;

// Small notebook that remembers settings and progress, even after a crash or restart.
public class RowStore {

    private final SharedPreferences sp;

    public RowStore(Context context) {
        sp = context.getApplicationContext().getSharedPreferences("autofiller", Context.MODE_PRIVATE);
    }

    // Chosen CSV file and Govt app
    public String getCsvUri() { return sp.getString("csvUri", ""); }
    public void setCsvUri(String v) { sp.edit().putString("csvUri", v).apply(); }
    public String getCsvName() { return sp.getString("csvName", ""); }
    public void setCsvName(String v) { sp.edit().putString("csvName", v).apply(); }
    public String getGovtPackage() { return sp.getString("govtPkg", ""); }
    public String getGovtLabel() { return sp.getString("govtLabel", ""); }
    public void setGovt(String pkg, String label) {
        sp.edit().putString("govtPkg", pkg).putString("govtLabel", label).apply();
    }

    // Test mode: fill the form but never press Save. ON by default.
    public boolean isDryRun() { return sp.getBoolean("dryRun", true); }
    public void setDryRun(boolean v) { sp.edit().putBoolean("dryRun", v).apply(); }

    // Progress
    public int getNext() { return sp.getInt("next", 0); }        // next row to process (0 = first)
    public void setNext(int v) { sp.edit().putInt("next", v).apply(); }
    public int getCurrent() { return sp.getInt("current", 0); }  // row being processed (1 = first)
    public void setCurrent(int v) { sp.edit().putInt("current", v).apply(); }
    public int getTotal() { return sp.getInt("total", 0); }
    public void setTotal(int v) { sp.edit().putInt("total", v).apply(); }
    public int getSuccess() { return sp.getInt("success", 0); }
    public void setSuccess(int v) { sp.edit().putInt("success", v).apply(); }
    public int getFailed() { return sp.getInt("failed", 0); }
    public void setFailed(int v) { sp.edit().putInt("failed", v).apply(); }
    public String getStatus() { return sp.getString("status", "Ready"); }
    public void setStatus(String v) { sp.edit().putString("status", v).apply(); }
    public String getMessage() { return sp.getString("message", ""); }
    public void setMessage(String v) { sp.edit().putString("message", v).apply(); }

    public void resetProgress(int total) {
        sp.edit()
                .putInt("next", 0)
                .putInt("current", 0)
                .putInt("total", total)
                .putInt("success", 0)
                .putInt("failed", 0)
                .putString("status", "Ready")
                .putString("message", "")
                .apply();
    }
}
