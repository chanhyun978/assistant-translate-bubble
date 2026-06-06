package com.assistanttranslatebubble.app;

final class ScreenshotController {
    private ScreenshotController() {
    }

    static boolean requestImmediateScreenshot() {
        AssistantTranslateAccessibilityService service =
                AssistantTranslateAccessibilityService.getConnectedService();
        return service != null && service.takeSystemScreenshot();
    }
}
