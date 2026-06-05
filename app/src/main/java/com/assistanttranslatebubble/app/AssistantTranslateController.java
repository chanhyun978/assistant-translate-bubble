package com.assistanttranslatebubble.app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicInteger;

final class AssistantTranslateController {
    private static final long REQUEST_TIMEOUT_MS = 9000L;
    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger();

    private static volatile int currentRequestId;
    private static volatile long requestStartedAt;
    private static volatile boolean pendingRequest;
    private static volatile boolean translationActive;
    private static volatile boolean waitingForAssistantSettle;
    private static volatile boolean showFeedbackForCurrentRequest;

    private AssistantTranslateController() {
    }

    static void requestTranslation(Context context) {
        requestTranslation(context, true);
    }

    static void requestTranslationSilently(Context context) {
        requestTranslation(context, false);
    }

    static synchronized boolean handleBubbleTap(Context context) {
        if (waitingForAssistantSettle) {
            return false;
        }
        PermissionUtils.setAutomationActive(context, true);

        AssistantTranslateAccessibilityService service =
                AssistantTranslateAccessibilityService.getConnectedService();
        if (translationActive) {
            if (service != null && service.isGoogleTranslationWindowVisible()) {
                if (service.closeTranslationWindow()) {
                    waitForAssistantToSettle(service);
                    return true;
                }
            }
            markTranslationClosed(false);
            requestTranslation(context, false);
            return true;
        }

        if (service != null && service.isGoogleTranslationWindowVisible()) {
            if (service.clickVisibleTranslateButton()) {
                markTranslationActive();
                return true;
            }
            if (service.closeTranslationWindow()) {
                waitForAssistantToSettle(service);
                return true;
            }
            markTranslationClosed(false);
            return true;
        }

        requestTranslation(context, false);
        return true;
    }

    static synchronized void closeTranslationBecauseBubbleMoved() {
        if (!translationActive) {
            return;
        }

        AssistantTranslateAccessibilityService service =
                AssistantTranslateAccessibilityService.getConnectedService();
        if (service != null && service.isGoogleTranslationWindowVisible()) {
            if (service.closeTranslationWindow()) {
                waitForAssistantToSettle(service);
                return;
            }
        }

        markTranslationClosed(true);
    }

    static synchronized void onAssistantWindowSettled(boolean confirmed) {
        markTranslationClosed(confirmed);
    }

    static boolean isTranslationActive() {
        return translationActive;
    }

    static synchronized void onAssistantWindowDismissedExternally() {
        if (translationActive && !waitingForAssistantSettle) {
            markTranslationClosed(true);
        }
    }

    static synchronized void resetAll() {
        currentRequestId = 0;
        requestStartedAt = 0L;
        pendingRequest = false;
        translationActive = false;
        waitingForAssistantSettle = false;
        showFeedbackForCurrentRequest = false;
    }

    private static void requestTranslation(Context context, boolean showFeedback) {
        if (!BubbleService.isRunning()) {
            resetAll();
            showFeedbackForCurrentRequest = showFeedback;
            showToast(context, "버블을 먼저 시작해 주세요.", Toast.LENGTH_SHORT);
            return;
        }
        PermissionUtils.setAutomationActive(context, true);

        pendingRequest = true;
        translationActive = false;
        waitingForAssistantSettle = false;
        showFeedbackForCurrentRequest = showFeedback;
        requestStartedAt = SystemClock.uptimeMillis();
        currentRequestId = REQUEST_COUNTER.incrementAndGet();

        AssistantTranslateAccessibilityService service =
                AssistantTranslateAccessibilityService.getConnectedService();
        if (PermissionUtils.useHomeLongPress(context) && service != null) {
            service.openAssistantWithHomeLongPress();
            showToast(context, "홈 버튼 길게 누르기로 실행합니다.", Toast.LENGTH_SHORT);
            return;
        }

        if (launchAssistant(context)) {
            showToast(context, "번역 버튼을 찾는 중입니다.", Toast.LENGTH_SHORT);
            return;
        }

        if (service != null) {
            service.openAssistantWithHomeLongPress();
            showToast(context, "Assistant 실행을 홈 버튼 방식으로 재시도합니다.", Toast.LENGTH_SHORT);
            return;
        }

        clearPendingRequest();
        showToast(context, "접근성 서비스를 켠 뒤 다시 시도해 주세요.", Toast.LENGTH_LONG);
    }

    static boolean hasPendingRequest() {
        if (!pendingRequest) {
            return false;
        }
        if (SystemClock.uptimeMillis() - requestStartedAt > REQUEST_TIMEOUT_MS) {
            clearPendingRequest();
            return false;
        }
        return true;
    }

    static int currentRequestId() {
        return currentRequestId;
    }

    static boolean shouldShowFeedback() {
        return showFeedbackForCurrentRequest;
    }

    static void markTranslationActive() {
        pendingRequest = false;
        waitingForAssistantSettle = false;
        translationActive = true;
    }

    static void clearPendingRequest() {
        pendingRequest = false;
    }

    private static void waitForAssistantToSettle(AssistantTranslateAccessibilityService service) {
        pendingRequest = false;
        waitingForAssistantSettle = true;
        service.watchAssistantCloseAfterBack();
    }

    private static void markTranslationClosed(boolean pulseWhenReady) {
        translationActive = false;
        pendingRequest = false;
        waitingForAssistantSettle = false;
        if (pulseWhenReady) {
            BubbleService.notifyAssistantSettled();
        }
    }

    private static void showToast(Context context, String message, int duration) {
        if (showFeedbackForCurrentRequest) {
            Toast.makeText(context, message, duration).show();
        }
    }

    private static boolean launchAssistant(Context context) {
        Intent googleAssist = new Intent(Intent.ACTION_ASSIST)
                .setPackage("com.google.android.googlequicksearchbox");
        if (startActivity(context, googleAssist)) {
            return true;
        }

        Intent defaultAssist = new Intent(Intent.ACTION_ASSIST);
        if (startActivity(context, defaultAssist)) {
            return true;
        }

        Intent voiceCommand = new Intent(Intent.ACTION_VOICE_COMMAND);
        if (startActivity(context, voiceCommand)) {
            return true;
        }

        Intent googleApp = context.getPackageManager()
                .getLaunchIntentForPackage("com.google.android.googlequicksearchbox");
        return googleApp != null && startActivity(context, googleApp);
    }

    private static boolean startActivity(Context context, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException | IllegalStateException error) {
            return false;
        }
    }
}
