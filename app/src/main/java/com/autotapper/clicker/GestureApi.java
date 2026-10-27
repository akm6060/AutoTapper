package com.autotapper.clicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.graphics.Path;

/** Kept in its own class so Android 6 (no gesture API) never loads it. */
@TargetApi(24)
final class GestureApi {
    private GestureApi() { }

    static boolean dispatch(AccessibilityService svc, int x1, int y1, int x2, int y2, long dur,
                            final Runnable next) {
        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, dur)).build();
        return svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gd) { next.run(); }
            @Override public void onCancelled(GestureDescription gd) { next.run(); }
        }, null);
    }
}
