package com.android.launcher3.moments;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MomentsTriggerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        switch (action) {
            case MomentsScheduler.ACTION_TIMER:
                MomentsScheduler.onTimer(context);
                break;
            case MomentsScheduler.ACTION_SCHEDULE:
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
                MomentsScheduler.onSchedule(context);
                break;
            case BluetoothDevice.ACTION_ACL_CONNECTED:
            case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                MomentsScheduler.onBluetooth(context,
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice.class),
                        BluetoothDevice.ACTION_ACL_CONNECTED.equals(action));
                break;
            default:
                break;
        }
    }
}
