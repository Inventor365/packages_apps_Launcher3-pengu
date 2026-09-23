package com.android.launcher3.outdoor;

import android.media.AudioManager;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import java.util.HashSet;
import java.util.Set;

/**
 * Bound by Telecom as a non-UI in-call service (the launcher holds CONTROL_INCALL_EXPERIENCE)
 * only while Enhanced call sound is on. Moves calls to the speaker at full volume unless a
 * headset is connected.
 */
public class OutdoorInCallService extends InCallService {

    private static final int HEADSET_ROUTES =
            CallAudioState.ROUTE_BLUETOOTH | CallAudioState.ROUTE_WIRED_HEADSET;

    private final Set<Call> mBoosted = new HashSet<>();
    private int mSavedVolume = -1;

    private final Call.Callback mCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            if (state == Call.STATE_ACTIVE || state == Call.STATE_DIALING) {
                boostIfNoHeadset(call);
            }
        }
    };

    @Override
    public void onCallAdded(Call call) {
        call.registerCallback(mCallback);
        int state = call.getDetails().getState();
        if (state == Call.STATE_ACTIVE || state == Call.STATE_DIALING) {
            boostIfNoHeadset(call);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        call.unregisterCallback(mCallback);
        mBoosted.remove(call);
        if (getCalls().isEmpty() && mSavedVolume >= 0) {
            getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                    mSavedVolume, 0);
            mSavedVolume = -1;
        }
    }

    /** Once per call, so switching back to the earpiece mid-call sticks. */
    private void boostIfNoHeadset(Call call) {
        if (!mBoosted.add(call)) {
            return;
        }
        CallAudioState audio = getCallAudioState();
        if (audio == null || (audio.getSupportedRouteMask() & HEADSET_ROUTES) != 0) {
            return;
        }
        if (audio.getRoute() != CallAudioState.ROUTE_SPEAKER) {
            setAudioRoute(CallAudioState.ROUTE_SPEAKER);
        }
        AudioManager manager = getSystemService(AudioManager.class);
        if (mSavedVolume < 0) {
            mSavedVolume = manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        }
        manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0);
    }
}
