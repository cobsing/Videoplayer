package com.cobs.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_VIDEO = 1, REQ_SUB = 2, REQ_PERM = 3;

    private static final MediaPlayer.ScaleType[] SCALES = {
            MediaPlayer.ScaleType.SURFACE_BEST_FIT,
            MediaPlayer.ScaleType.SURFACE_FILL,
            MediaPlayer.ScaleType.SURFACE_16_9,
            MediaPlayer.ScaleType.SURFACE_4_3,
            MediaPlayer.ScaleType.SURFACE_ORIGINAL};
    private static final String[] SCALE_NAMES = {"Sığdır", "Doldur", "16:9", "4:3", "Orijinal"};
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private LibVLC libVlc;
    private MediaPlayer mp;
    private VLCVideoLayout videoLayout;
    private Uri currentUri, pendingUri;
    private ParcelFileDescriptor pfd;
    private boolean attached = false;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private TextView titleView, indicator, tvTime, tvLen;
    private LinearLayout controls;
    private SeekBar seek;
    private Button btnPlay, btnSpeed;

    private float brightness = 0.5f, volume = 100f;
    private int scaleIdx = 0, speedIdx = 2;
    private long spuDelay = 0, audioDelay = 0;
    private int gMode = 0; // 1 parlaklık, 2 ses, 3 atlama
    private long seekTarget;
    private boolean userSeeking = false;

    private final Runnable hideControls = new Runnable() {
        @Override public void run() { setControlsVisible(false); }
    };
    private final Runnable hideIndicator = new Runnable() {
        @Override public void run() { indicator.setVisibility(View.GONE); }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------------------ yaşam döngüsü

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float br = getWindow().getAttributes().screenBrightness;
        if (br < 0) {
            try {
                br = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
            } catch (Exception e) {
                br = 0.5f;
            }
        }
        brightness = clamp(br, 0.01f, 1f);

        buildUi();
        setImmersive();
        initPlayer();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mp != null && !attached) {
            mp.attachViews(videoLayout, null, true, false);
            attached = true;
        }
        handler.post(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveResume();
        handler.removeCallbacks(ticker);
        if (mp != null) {
            mp.pause();
            if (attached) {
                mp.detachViews();
                attached = false;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setImmersive();
    }

    private void setImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------ oynatıcı

    private void initPlayer() {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--freetype-rel-fontsize=" + prefs.getInt("subsize", 16));
        opts.add("--subsdec-encoding=Windows-1254"); // UTF-8 değilse Türkçe altyazı için
        opts.add("--audio-time-stretch");
        libVlc = new LibVLC(this, opts);
        mp = new MediaPlayer(libVlc);
        mp.attachViews(videoLayout, null, true, false);
        attached = true;
        mp.setVideoScale(SCALES[scaleIdx]);
        mp.setVolume((int) volume);
        mp.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event e) {
                switch (e.type) {
                    case MediaPlayer.Event.Playing:
                    case MediaPlayer.Event.Paused:
                    case MediaPlayer.Event.Stopped:
                        updatePlayButton();
                        break;
                    case MediaPlayer.Event.EndReached:
                        if (currentUri != null) prefs.edit().remove("pos_" + currentUri).apply();
                        updatePlayButton();
                        setControlsVisible(true);
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        toast("Video oynatılamadı");
                        break;
                }
            }
        });
    }

    private void releasePlayer() {
        if (mp != null) {
            mp.setEventListener(null);
            mp.stop();
            if (attached) {
                mp.detachViews();
                attached = false;
            }
            mp.release();
            mp = null;
        }
        if (libVlc != null) {
            libVlc.release();
            libVlc = null;
        }
        closeFd();
    }

    private void closeFd() {
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) { }
            pfd = null;
        }
    }

    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri u = intent.getData();
            if ("file".equals(u.getScheme()) && Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingUri = u;
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
                return;
            }
            open(u, -1);
        } else if (currentUri == null) {
            titleView.setText("Video açmak için 'Aç' düğmesine dokun");
            setControlsVisible(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERM && pendingUri != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) open(pendingUri, -1);
            else toast("Dosya izni verilmedi");
            pendingUri = null;
        }
    }

    /** startMs < 0 ise kayıtlı kaldığı yerden devam eder. */
    private void open(Uri uri, long startMs) {
        if (mp == null) return;
        closeFd();
        Media media;
        try {
            if ("content".equals(uri.getScheme())) {
                pfd = getContentResolver().openFileDescriptor(uri, "r");
                media = new Media(libVlc, pfd.getFileDescriptor());
            } else {
                media = new Media(libVlc, uri);
            }
        } catch (Exception e) {
            toast("Dosya açılamadı");
            return;
        }
        currentUri = uri;
        spuDelay = 0;
        audioDelay = 0;
        media.setHWDecoderEnabled(true, false);
        long start = startMs >= 0 ? startMs : prefs.getLong("pos_" + uri, 0);
        if (start > 5000) media.addOption(":start-time=" + (start / 1000.0));
        mp.setMedia(media);
        media.release();
        mp.play();
        mp.setRate(SPEEDS[speedIdx]);
        titleView.setText(queryName(uri));
        updatePlayButton();
        showControls();
    }

    private void saveResume() {
        if (mp == null || currentUri == null) return;
        long len = mp.getLength(), t = mp.getTime();
        SharedPreferences.Editor ed = prefs.edit();
        if (len > 0 && t > 5000 && t < len - 10000) ed.putLong("pos_" + currentUri, t);
        else ed.remove("pos_" + currentUri);
        ed.apply();
    }

    private String queryName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) { }
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "Video" : name;
    }

    // ------------------------------------------------------------ arayüz

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button mkBtn(String text, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                action.run();
                showControls();
            }
        });
        return b;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoLayout = new VLCVideoLayout(this);
        root.addView(videoLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16);
        titleView.setSingleLine(true);
        titleView.setBackgroundColor(0x99000000);
        titleView.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.addView(titleView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        indicator = new TextView(this);
        indicator.setTextColor(Color.WHITE);
        indicator.setTextSize(24);
        indicator.setBackgroundColor(0xAA000000);
        indicator.setPadding(dp(20), dp(12), dp(20), dp(12));
        indicator.setVisibility(View.GONE);
        root.addView(indicator, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(0x99000000);
        controls.setClickable(true);
        controls.setPadding(dp(8), dp(4), dp(8), dp(4));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        tvTime = new TextView(this);
        tvTime.setTextColor(Color.WHITE);
        tvTime.setText("00:00");
        tvLen = new TextView(this);
        tvLen.setTextColor(Color.WHITE);
        tvLen.setText("00:00");
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && mp != null) tvTime.setText(fmt(mp.getLength() * p / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
                handler.removeCallbacks(hideControls);
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (mp != null) mp.setTime(mp.getLength() * sb.getProgress() / 1000);
                userSeeking = false;
                showControls();
            }
        });
        row.addView(tvTime);
        row.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLen);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        btns.addView(mkBtn("Aç", new Runnable() { public void run() { pick(REQ_VIDEO); } }));
        btns.addView(mkBtn("-10 sn", new Runnable() { public void run() { seekRel(-10000); } }));
        btnPlay = mkBtn("Oynat", new Runnable() { public void run() { togglePlay(); } });
        btns.addView(btnPlay);
        btns.addView(mkBtn("+10 sn", new Runnable() { public void run() { seekRel(10000); } }));
        btns.addView(mkBtn("Altyazı", new Runnable() { public void run() { showSubMenu(); } }));
        btns.addView(mkBtn("Ses", new Runnable() { public void run() { showTracks(true); } }));
        btnSpeed = mkBtn("Hız 1x", new Runnable() { public void run() { cycleSpeed(); } });
        btns.addView(btnSpeed);
        btns.addView(mkBtn("Ekran", new Runnable() { public void run() { cycleScale(); } }));
        btns.addView(mkBtn("Ayar", new Runnable() { public void run() { showSettings(); } }));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(btns);

        controls.addView(row);
        controls.addView(hs);
        root.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                setControlsVisible(controls.getVisibility() != View.VISIBLE);
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                togglePlay();
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null || mp == null) return false;
                float w = root.getWidth(), h = root.getHeight();
                if (gMode == 0) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (mp.getLength() <= 0) return false;
                        gMode = 3;
                        seekTarget = mp.getTime();
                    } else {
                        gMode = e1.getX() < w / 2f ? 1 : 2;
                    }
                }
                if (gMode == 1) {
                    brightness = clamp(brightness + dy / h * 1.2f, 0.01f, 1f);
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = brightness;
                    getWindow().setAttributes(lp);
                    showIndicator("Parlaklık: " + (int) (brightness * 100) + "%");
                } else if (gMode == 2) {
                    volume = clamp(volume + dy / h * 250f, 0f, 200f);
                    mp.setVolume((int) volume);
                    showIndicator("Ses: " + (int) volume + "%");
                } else if (gMode == 3) {
                    long len = mp.getLength();
                    seekTarget = (long) clamp(seekTarget - dx / w * 90000f, 0f, (float) len);
                    showIndicator(fmt(seekTarget) + " / " + fmt(len));
                }
                return true;
            }
        });
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                gd.onTouchEvent(ev);
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    if (gMode == 3 && mp != null) mp.setTime(seekTarget);
                    gMode = 0;
                    handler.postDelayed(hideIndicator, 800);
                }
                return true;
            }
        });
    }

    private void showIndicator(String s) {
        handler.removeCallbacks(hideIndicator);
        indicator.setText(s);
        indicator.setVisibility(View.VISIBLE);
    }

    private void setControlsVisible(boolean v) {
        handler.removeCallbacks(hideControls);
        int vis = v ? View.VISIBLE : View.GONE;
        controls.setVisibility(vis);
        titleView.setVisibility(vis);
        if (v && mp != null && mp.isPlaying()) handler.postDelayed(hideControls, 4000);
    }

    private void showControls() {
        setControlsVisible(true);
    }

    private void updatePlayButton() {
        if (btnPlay != null && mp != null) btnPlay.setText(mp.isPlaying() ? "Duraklat" : "Oynat");
    }

    private void updateProgress() {
        if (mp == null || userSeeking) return;
        long len = mp.getLength(), t = Math.max(0, mp.getTime());
        tvTime.setText(fmt(t));
        tvLen.setText(fmt(Math.max(0, len)));
        seek.setProgress(len > 0 ? (int) (t * 1000 / len) : 0);
    }

    // ------------------------------------------------------------ kontroller

    private void togglePlay() {
        if (mp == null) return;
        if (mp.isPlaying()) mp.pause(); else mp.play();
        updatePlayButton();
    }

    private void seekRel(long delta) {
        if (mp == null) return;
        long len = mp.getLength();
        long t = mp.getTime() + delta;
        t = Math.max(0, len > 0 ? Math.min(t, len) : t);
        mp.setTime(t);
        showIndicator(fmt(t));
        handler.postDelayed(hideIndicator, 800);
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % SPEEDS.length;
        if (mp != null) mp.setRate(SPEEDS[speedIdx]);
        btnSpeed.setText("Hız " + SPEEDS[speedIdx] + "x");
    }

    private void cycleScale() {
        scaleIdx = (scaleIdx + 1) % SCALES.length;
        if (mp != null) mp.setVideoScale(SCALES[scaleIdx]);
        toast("Ekran: " + SCALE_NAMES[scaleIdx]);
    }

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCat
