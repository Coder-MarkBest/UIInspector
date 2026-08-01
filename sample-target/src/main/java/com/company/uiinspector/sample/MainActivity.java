package com.company.uiinspector.sample;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private Dialog dialogFixture;
    private Dialog secureWindowFixture;
    private PopupWindow popupFixture;
    private View directWindowFixture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        installLargeImageFixture();
        findViewById(R.id.show_dialog).setOnClickListener(view -> showDialogFixture());
        findViewById(R.id.show_popup).setOnClickListener(view -> showPopupFixture());
        findViewById(R.id.add_direct_window).setOnClickListener(view -> addDirectWindowFixture());
        findViewById(R.id.show_secure_window).setOnClickListener(view -> showSecureWindowFixture());
        if (getIntent().getBooleanExtra("showAllFixtures", false)) {
            findViewById(R.id.root_scroll).post(this::showAllFixtures);
        }
    }

    private void showAllFixtures() {
        showDialogFixture();
        showPopupFixture();
        addDirectWindowFixture();
        showSecureWindowFixture();
    }

    private void installLargeImageFixture() {
        Bitmap bitmap = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(49, 88, 255));
        ImageView image = findViewById(R.id.large_image);
        image.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
    }

    public void showDialogFixture() {
        if (dialogFixture == null) {
            dialogFixture = textDialog("Dialog Window", "A separate Dialog View root");
        }
        if (!dialogFixture.isShowing()) dialogFixture.show();
    }

    public void showPopupFixture() {
        if (popupFixture == null) {
            TextView content = fixtureLabel("PopupWindow View root", 240, 72, Color.rgb(255, 239, 196));
            popupFixture = new PopupWindow(
                    content, dp(240), dp(72), false);
            popupFixture.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
            popupFixture.setOutsideTouchable(false);
        }
        if (!popupFixture.isShowing()) {
            popupFixture.showAtLocation(findViewById(R.id.root_scroll), Gravity.TOP | Gravity.END, dp(24), dp(96));
        }
    }

    public void addDirectWindowFixture() {
        if (directWindowFixture != null && directWindowFixture.isAttachedToWindow()) return;
        directWindowFixture = fixtureLabel(
                "WindowManager.addView root", 260, 64, Color.rgb(209, 232, 255));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(260), dp(64), WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = dp(20);
        params.y = dp(20);
        params.setTitle("Direct WindowManager View");
        params.token = getWindow().getDecorView().getWindowToken();
        getWindowManager().addView(directWindowFixture, params);
    }

    public void showSecureWindowFixture() {
        if (secureWindowFixture == null) {
            secureWindowFixture = textDialog("Secure Window", "Screenshot is intentionally protected");
            secureWindowFixture.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        if (!secureWindowFixture.isShowing()) secureWindowFixture.show();
    }

    private Dialog textDialog(String title, String message) {
        Dialog value = new Dialog(this);
        value.setTitle(title);
        value.setContentView(fixtureLabel(message, 280, 120, Color.WHITE));
        return value;
    }

    private TextView fixtureLabel(String text, int widthDp, int heightDp, int color) {
        TextView value = new TextView(this);
        value.setText(text);
        value.setContentDescription(text);
        value.setGravity(Gravity.CENTER);
        value.setPadding(dp(12), dp(12), dp(12), dp(12));
        value.setBackgroundColor(color);
        value.setLayoutParams(new ViewGroup.LayoutParams(dp(widthDp), dp(heightDp)));
        return value;
    }

    public boolean isDialogShowing() {
        return dialogFixture != null && dialogFixture.isShowing();
    }

    public boolean isPopupShowing() {
        return popupFixture != null && popupFixture.isShowing();
    }

    public boolean isDirectWindowAttached() {
        return directWindowFixture != null && directWindowFixture.isAttachedToWindow();
    }

    public boolean isSecureWindowShowing() {
        return secureWindowFixture != null && secureWindowFixture.isShowing();
    }

    public int secureWindowFlags() {
        return secureWindowFixture == null ? 0 : secureWindowFixture.getWindow().getAttributes().flags;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (popupFixture != null) popupFixture.dismiss();
        if (dialogFixture != null) dialogFixture.dismiss();
        if (secureWindowFixture != null) secureWindowFixture.dismiss();
        if (directWindowFixture != null && directWindowFixture.isAttachedToWindow()) {
            getWindowManager().removeViewImmediate(directWindowFixture);
        }
        super.onDestroy();
    }
}
