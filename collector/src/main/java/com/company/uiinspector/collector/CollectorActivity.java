package com.company.uiinspector.collector;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class CollectorActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int spacing = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(spacing, spacing, spacing, spacing);
        content.setBackgroundColor(Color.rgb(244, 247, 249));

        TextView title = new TextView(this);
        title.setText("UI Inspector Collector");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(25, 35, 43));
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView explanation = new TextView(this);
        explanation.setText("Enable the Collector accessibility service, then return to Android Studio. The tool reads public UI metadata only while you request a capture.");
        explanation.setTextSize(16);
        explanation.setTextColor(Color.rgb(65, 78, 88));
        LinearLayout.LayoutParams explanationParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        explanationParams.topMargin = spacing;
        content.addView(explanation, explanationParams);

        Button settings = new Button(this);
        settings.setText("Open Accessibility settings");
        settings.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = spacing;
        content.addView(settings, buttonParams);
        setContentView(content);
    }
}
