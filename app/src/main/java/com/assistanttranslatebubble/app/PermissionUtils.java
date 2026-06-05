package com.assistanttranslatebubble.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

final class PermissionUtils {
    private static final String PREFS_NAME = "translate_bubble";
    private static final String PREF_USE_HOME_LONG_PRESS = "use_home_long_press";
    private static final String PREF_AUTOMATION_ACTIVE = "automation_active";

    private PermissionUtils() {
    }

    static boolean canDrawOverlays(Context context) {
        return Settings.canDrawOverlays(context);
    }

    static boolean canPostNotifications(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isAccessibilityEnabled(Context context) {
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null || enabledServices.isEmpty()) {
            return false;
        }

        ComponentName expected = new ComponentName(
                context,
                AssistantTranslateAccessibilityService.class
        );
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            ComponentName enabled = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(enabled)) {
                return true;
            }
        }
        return false;
    }

    static boolean useHomeLongPress(Context context) {
        return prefs(context).getBoolean(PREF_USE_HOME_LONG_PRESS, true);
    }

    static void setUseHomeLongPress(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_USE_HOME_LONG_PRESS, enabled).apply();
    }

    static boolean isAutomationActive(Context context) {
        return prefs(context).getBoolean(PREF_AUTOMATION_ACTIVE, false);
    }

    static void setAutomationActive(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_AUTOMATION_ACTIVE, enabled).apply();
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
