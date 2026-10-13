package com.limelight.selfframed.mic;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

/**
 * Debug builds only: pairs with a PC receiver and sends the microphone for a few seconds, without a stream, to
 * test the phone side in the emulator (its microphone is silent unless host audio input is switched on). Against
 * a test receiver with --auto-accept no one has to click; against the real one the PC asks.
 * adb shell pm grant com.selfframed.moonlight.debug android.permission.RECORD_AUDIO
 * adb shell am start -n com.selfframed.moonlight.debug/com.limelight.selfframed.mic.MicSendTestActivity
 *     --es host 10.0.2.2 --ei seconds 5
 */
public class MicSendTestActivity extends Activity {
    private static final String TAG = "SfMicTest";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        text = new TextView(this);
        text.setTextSize(18);
        text.setPadding(32, 32, 32, 32);
        setContentView(text);
        String extraHost = getIntent().getStringExtra("host");
        final String host = extraHost != null ? extraHost : "10.0.2.2";
        final int seconds = getIntent().getIntExtra("seconds", 5);
        show("pairing with " + host);
        new MicPairer(host, "Emulator test", new MicPairer.Listener() {
            @Override
            public void onCode(String code) {
                show("code " + code);
            }

            @Override
            public void onPaired(int device, byte[] audioKey) {
                show(String.format("paired as device %08x, sending for %d s", device, seconds));
                MicSender sender = new MicSender(host, device, audioKey, new MicSender.Listener() {
                    @Override
                    public void onMicFailed(String reason) {
                        show("failed: " + reason);
                    }

                    @Override
                    public void onUnpaired() {
                        show("PC does not know this device");
                    }
                });
                sender.start();
                ui.postDelayed(() -> {
                    sender.stop();
                    show("done");
                }, seconds * 1000L);
            }

            @Override
            public void onFailed(String reason, boolean rejected) {
                show("pairing failed: " + reason);
            }
        }).start();
    }

    private void show(String line) {
        Log.i(TAG, line);
        ui.post(() -> text.setText(line));
    }
}
