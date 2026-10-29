package com.music2nes;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1, REQ_SAVE = 2;

    private Uri source;
    private String baseName = "song";
    private byte[] rom;
    private boolean busy;

    private TextView fileLabel, status;
    private EditText artistField, titleField;
    private Button pickButton, convert512, convert1024, saveButton;
    private ProgressBar bar;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);

        TextView heading = text("Music → NES ROM", 24);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(heading);
        col.addView(text("Turns a song into an NES ROM (mapper 2) with the title, a timer and a "
                + "progress bar on screen; A plays/pauses. For songs over about 2 minutes, a 1 MB "
                + "ROM gets twice the sample rate of a 512 KB one. Up to about "
                + Math.round(RomBuilder.maxSeconds(512) / 60)
                + " minutes fit in 512 KB and " + Math.round(RomBuilder.maxSeconds(1024) / 60)
                + " in 1 MB; longer songs get a lower sample rate.", 14));

        pickButton = button("Choose audio file", v -> pick());
        col.addView(pickButton);
        fileLabel = text("No file chosen", 14);
        col.addView(fileLabel);

        col.addView(text("Line 1 (artist)", 14));
        artistField = field();
        col.addView(artistField);
        col.addView(text("Line 2 (title)", 14));
        titleField = field();
        col.addView(titleField);

        convert512 = button("Convert to 512 KB ROM", v -> convert(512));
        convert1024 = button("Convert to 1 MB ROM", v -> convert(1024));
        setConvertEnabled(false);
        col.addView(convert512);
        col.addView(convert1024);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.INVISIBLE);
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        status = text("", 14);
        col.addView(status);

        saveButton = button("Save ROM again", v -> save());
        saveButton.setVisibility(View.GONE);
        col.addView(saveButton);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                    in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
            return in.consumeSystemWindowInsets();
        });
        setContentView(scroll);
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*", "application/ogg"});
        startActivityForResult(i, REQ_OPEN);
    }

    private void save() {
        if (rom == null) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, baseName + ".nes");
        startActivityForResult(i, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_OPEN) {
            source = data.getData();
            rom = null;
            saveButton.setVisibility(View.GONE);
            fillFromFile(source);
            setConvertEnabled(!busy);
            status.setText("");
        } else if (req == REQ_SAVE) {
            try (OutputStream os = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (os == null) throw new java.io.IOException("Could not open the destination.");
                os.write(rom);
                status.setText("Saved " + baseName + ".nes (" + rom.length / 1024 + " KB).");
            } catch (Exception e) {
                status.setText("Save failed: " + e.getMessage());
            }
        }
    }

    /** Pre-fills the two text lines from tags, falling back to "Artist - Title" file names. */
    private void fillFromFile(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        if (name == null) name = "song";
        fileLabel.setText(name);
        String stem = name.replaceAll("\\.[^.]*$", "");
        baseName = stem.replaceAll("[\\\\/:*?\"<>|]", "_");

        String artist = null, title = null;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, uri);
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
        if (title == null || title.trim().isEmpty()) {
            int dash = stem.indexOf(" - ");
            if (dash > 0) {
                if (artist == null || artist.trim().isEmpty()) artist = stem.substring(0, dash);
                title = stem.substring(dash + 3);
            } else {
                title = stem;
            }
        }
        artistField.setText(artist == null ? "" : RomBuilder.sanitize(artist));
        titleField.setText(RomBuilder.sanitize(title));
    }

    private void setConvertEnabled(boolean on) {
        convert512.setEnabled(on);
        convert1024.setEnabled(on);
    }

    private void convert(final int prgKb) {
        if (source == null || busy) return;
        busy = true;
        rom = null;
        pickButton.setEnabled(false);
        setConvertEnabled(false);
        saveButton.setVisibility(View.GONE);
        bar.setProgress(0);
        bar.setVisibility(View.VISIBLE);
        final Uri uri = source;
        final String artist = artistField.getText().toString();
        final String title = titleField.getText().toString();
        new Thread(() -> {
            try {
                AudioDecoder.Pcm pcm = AudioDecoder.decode(this, uri, (s, f) -> progress(s, f * 0.3f));
                RomBuilder.Result r = RomBuilder.build(pcm.samples, pcm.length, pcm.rate, artist, title,
                        prgKb, (s, f) -> progress(s, s.equals("Encoding") ? 0.45f + f * 0.55f
                                : 0.3f + f * 0.15f));
                runOnUiThread(() -> {
                    rom = r.rom;
                    status.setText(String.format("Done: %d:%02d of audio at %.0f Hz. Choose where to save it.",
                            (int) r.seconds / 60, (int) r.seconds % 60, r.sampleRate));
                    saveButton.setVisibility(View.VISIBLE);
                    finishWork();
                    save();
                });
            } catch (Throwable e) {
                String msg = e instanceof OutOfMemoryError ? "Not enough memory for this file."
                        : e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    status.setText("Conversion failed: " + msg);
                    finishWork();
                });
            }
        }).start();
    }

    private void finishWork() {
        busy = false;
        bar.setVisibility(View.INVISIBLE);
        pickButton.setEnabled(true);
        setConvertEnabled(source != null);
    }

    private long lastUi;

    private void progress(String stage, float frac) {
        long now = System.currentTimeMillis();
        if (now - lastUi < 100 && frac < 1f) return;
        lastUi = now;
        runOnUiThread(() -> {
            bar.setProgress((int) (frac * 1000));
            status.setText(stage + "… " + Math.round(frac * 100) + "%");
        });
    }

    // ------------------------------------------------------------ view helpers

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText field() {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2 * RomBuilder.MAX_LINE)});
        return e;
    }
}
