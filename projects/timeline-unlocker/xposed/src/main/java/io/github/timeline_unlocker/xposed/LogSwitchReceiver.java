package io.github.timeline_unlocker.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs inside Maps. The module sends SET_LOG; this process flips the in-memory switch. */
public class LogSwitchReceiver extends BroadcastReceiver {

    static final String ACTION = "io.github.timeline_unlocker.xposed.SET_LOG";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        DiagLog.applySwitch(intent.getBooleanExtra("on", false));
    }
}
