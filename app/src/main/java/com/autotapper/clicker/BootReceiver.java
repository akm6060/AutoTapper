package com.autotapper.clicker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After boot / app update: restart the background keep-alive. (The accessibility service itself is restarted by Android.) */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("keepalive", true)) {
            KeepAliveService.start(c);
        }
    }
}
