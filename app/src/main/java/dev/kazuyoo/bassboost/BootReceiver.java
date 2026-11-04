package dev.kazuyoo.bassboost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Intent svc = new Intent(ctx, BassService.class);
        ctx.startForegroundService(svc);
    }
}
