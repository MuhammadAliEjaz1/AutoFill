package com.autofiller.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

// The permission guide for the client.
public class SetupActivity extends Activity {

    private TextView tvState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        tvState = findViewById(R.id.tvServiceState);
        Button btnAccessibility = findViewById(R.id.btnAccessibility);
        Button btnAppInfo = findViewById(R.id.btnAppInfo);

        btnAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        btnAppInfo.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (FillerService.instance != null) {
            tvState.setText("Service is ON - you are ready");
        } else {
            tvState.setText("Service is OFF - follow Step 1");
        }
    }
}
