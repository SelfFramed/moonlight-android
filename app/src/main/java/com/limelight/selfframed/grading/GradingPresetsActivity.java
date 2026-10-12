package com.limelight.selfframed.grading;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Backup, restore and reset of the picture presets (Storage Access Framework, JSON).
 * Editing itself happens live in the stream panel. Styled for e-ink: black on white, borders.
 */
public class GradingPresetsActivity extends Activity {
    private static final int REQUEST_EXPORT = 1;
    private static final int REQUEST_IMPORT = 2;
    private static final int MAX_IMPORT_BYTES = 1024 * 1024;
    /** Single presets go into the active slot; while neutral is active, into the first free slot. */
    private static final int DEFAULT_IMPORT_SLOT = BuiltinPresets.DEFINED + 1;

    private PresetStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.sf_presets_title);
        store = new PresetStore(this);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.WHITE);
        int pad = dp(24);
        column.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(this);
        info.setText(R.string.sf_presets_info);
        info.setTextColor(Color.BLACK);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        column.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(button(R.string.sf_presets_test_pattern, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(GradingPresetsActivity.this, GradingTestPatternActivity.class));
            }
        }));
        column.addView(button(R.string.sf_presets_export, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "moonlight-eink-presets.json");
                startForResult(intent, REQUEST_EXPORT);
            }
        }));
        column.addView(button(R.string.sf_presets_import, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // JSON files often arrive as octet-stream, so accept anything and validate the content
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*");
                startForResult(intent, REQUEST_IMPORT);
            }
        }));
        column.addView(button(R.string.sf_presets_reset, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(GradingPresetsActivity.this)
                        .setMessage(R.string.sf_presets_reset_confirm)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                store.resetToBuiltins();
                                toast(getString(R.string.sf_presets_reset_done));
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.addView(column);
        setContentView(scroll);
    }

    private void startForResult(Intent intent, int request) {
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.sf_presets_import_failed, "no file picker on this device"));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) {
                    throw new IOException("cannot open " + uri);
                }
                out.write(store.exportJson().getBytes(StandardCharsets.UTF_8));
                toast(getString(R.string.sf_presets_exported));
            } catch (IOException | RuntimeException e) {
                LimeLog.warning("SelfFramed presets: export failed: " + e);
                toast(getString(R.string.sf_presets_import_failed, e.getMessage()));
            }
        } else if (requestCode == REQUEST_IMPORT) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("cannot open " + uri);
                }
                String text = readLimited(in);
                int active = store.bundle().getActiveSlot();
                int target = PresetBundle.isPresetSlot(active) ? active : DEFAULT_IMPORT_SLOT;
                int count = store.importJson(text, target);
                toast(getString(R.string.sf_presets_imported, count));
            } catch (IOException | IllegalArgumentException e) {
                LimeLog.warning("SelfFramed presets: import failed: " + e);
                toast(getString(R.string.sf_presets_import_failed, e.getMessage()));
            }
        }
    }

    private static String readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
            if (buffer.size() > MAX_IMPORT_BYTES) {
                throw new IOException("file larger than 1 MB");
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private Button button(int textRes, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(textRes);
        b.setAllCaps(false);
        b.setTextColor(Color.BLACK);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Color.WHITE);
        frame.setStroke(dp(3), Color.BLACK);
        b.setBackground(frame);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        b.setLayoutParams(lp);
        return b;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
