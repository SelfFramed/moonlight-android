package com.limelight.selfframed.mic;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

/**
 * Debug builds only: sends the microphone to a PC receiver for a few seconds without a stream, to test the
 * sending side in the emulator (the emulator's microphone is silent unless host audio input is switched on).
 * adb shell pm grant com.selfframed.moonlight.debug android.permission.RECORD_AUDIO
 * adb shell am start -n com.selfframed.moonlight.debug/com.limelight.selfframed.mic.MicSendTestActivity
 *     --es host 10.0.2.2 --es code ABCD-EFGH-JKMN-PQRS-TVWX --ei seconds 5
 */
public class MicSendTestActivity extends Activity {
    private static final String TAG = "SfMicTest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setTextSize(18);
        text.setPadding(32, 32, 32, 32);
        setContentView(text);

        String host = getIntent().getStringExtra("host");
        String code = MicCode.normalize(getIntent().getStringExtra("code"));
        int seconds = getIntent().getIntExtra("seconds", 5);
        if (host == null) {
            host = "10.0.2.2";
        }
        if (code == null) {
            show(text, "invalid or missing code");
            return;
        }
        MicSender sender = new MicSender(host, MicCode.key(code), reason -> Log.i(TAG, "failed: " + reason));
        show(text, "sending to " + host + ":" + MicPacket.PORT + " for " + seconds + " s");
        sender.start();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            sender.stop();
            show(text, "done");
        }, seconds * 1000L);
    }

    private static void show(TextView text, String line) {
        Log.i(TAG, line);
        text.setText(line);
    }
}
