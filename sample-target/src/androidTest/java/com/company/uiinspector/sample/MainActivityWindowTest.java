package com.company.uiinspector.sample;

import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class MainActivityWindowTest {
    @Test
    public void createsRequestedWindowFixtures() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.showDialogFixture();
                activity.showPopupFixture();
                activity.addDirectWindowFixture();
                activity.showSecureWindowFixture();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            scenario.onActivity(activity -> {
                assertTrue(activity.isDialogShowing());
                assertTrue(activity.isPopupShowing());
                assertTrue(activity.isDirectWindowAttached());
                assertTrue(activity.isSecureWindowShowing());
                assertTrue((activity.secureWindowFlags() & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertNotNull(activity.findViewById(R.id.large_image));
                assertNotNull(activity.findViewById(R.id.outside_layout));
            });
        }
    }
}
