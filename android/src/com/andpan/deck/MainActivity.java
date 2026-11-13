package com.andpan.deck;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class MainActivity extends Activity {
    private static final int REQUEST_AUDIO_PERMISSION = 1001;
    private static final int BG = Color.rgb(9, 14, 20);
    private static final int CARD = Color.rgb(18, 28, 36);
    private static final int CARD_ALT = Color.rgb(24, 37, 48);
    private static final int BORDER = Color.rgb(34, 52, 62);
    private static final int TRACK = Color.rgb(30, 46, 54);
    private static final int GREEN = Color.rgb(154, 238, 199);
    private static final int GREEN_DIM = Color.rgb(21, 56, 48);
    private static final int GREEN_BORDER = Color.rgb(46, 109, 90);
    private static final int AMBER = Color.rgb(240, 198, 116);
    private static final int CORAL = Color.rgb(240, 113, 120);
    private static final int MUTED = Color.rgb(135, 157, 166);
    private static final int TEXT = Color.rgb(235, 244, 241);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final ExecutorService microphone = Executors.newSingleThreadExecutor();
    private TextView status;
    private LinearLayout statusPill;
    private TextView hint;
    private TextView meterState;
    private TextView inputName;
    private TextView cpuValue;
    private TextView memoryValue;
    private BarView cpuBar;
    private BarView memBar;
    private TextView targetName;
    private TextView[] processNames = new TextView[3];
    private TextView[] processValues = new TextView[3];
    private LevelView levelView;
    private LinearLayout micButton;
    private TextView micBadge;
    private TextView micTitle;
    private TextView micSubtitle;
    private View micIconView;
    private Button continuousButton;
    private Button sendButton;
    private Button micYouButton;
    private Button settingsButton;
    private volatile String token;
    private volatile int port = 43125;
    private volatile String wifiHost = "";
    private volatile String wifiFingerprint = "";
    private volatile int wifiPort = 43126;
    private volatile String installedApkSha256 = "";
    private String serverApkSha256 = "";
    private boolean serverApkAvailable;
    private boolean visible;
    private boolean commandBusy;
    private boolean statusPending;
    private boolean holding;
    private boolean continuous;
    private boolean micStarting;
    private boolean micStopping;
    private boolean connected;
    private boolean micAvailable;
    private boolean accessibilityReady;
    private Boolean macMuted;
    private int targetPid;
    private int cpuPercent = -1;
    private int memPercent = -1;
    private String meterStatus = "off";
    private float currentLevel;
    private float displayLevel;
    private boolean discovering;
    private long lastDiscovery;
    private int micGeneration;
    private boolean requestedAudioOnLaunch;
    private int audioPort = 8554;
    private String micYouServiceState = "";
    private String micYouServiceError = "";
    private String macInputError = "";
    private MicYouClient audioClient;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private static final class ServerRejection extends Exception {
        ServerRejection(String message) { super(message); }
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (!commandBusy) loadStatus();
            ui.postDelayed(this, 2500);
        }
    };

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!holding) return;
            final int generation = micGeneration;
            microphone.execute(() -> {
                try {
                    JSONObject result = request("POST", "/mic/hold", new JSONObject());
                    ui.post(() -> {
                        if (holding && generation == micGeneration) {
                            applyMeter(result.optJSONObject("meter"));
                            ui.postDelayed(heartbeat, 300);
                        }
                    });
                }
                catch (Exception e) {
                    audioClient.stopStreaming();
                    try { request("POST", "/mic/stop", new JSONObject()); }
                    catch (Exception ignored) {}
                    ui.post(() -> {
                        if (holding && generation == micGeneration) {
                            holding = false;
                            continuous = false;
                            micStarting = false;
                            micGeneration++;
                            ui.removeCallbacks(heartbeat);
                            updateButtons();
                            showError(message(e));
                        }
                    });
                }
            });
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndPan:ContinuousAudio");
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AndPan:ContinuousWifi");
            }
        } catch (Throwable ignored) {}
        receiveConnection(getIntent());
        audioClient = new MicYouClient(() -> ui.post(this::updateAudioConnectionPill));
        buildScreen();
        commands.execute(() -> {
            String hash = computeFileSha256(new File(getApplicationInfo().sourceDir));
            installedApkSha256 = hash;
            ui.post(this::updateButtons);
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveConnection(intent);
        if (visible) loadStatus();
    }

    private void receiveConnection(Intent intent) {
        SharedPreferences prefs = getSharedPreferences("connection", MODE_PRIVATE);
        String incoming = intent.getStringExtra("token");
        int incomingPort = intent.getIntExtra("port", 43125);
        if (incoming != null && incoming.matches("[A-Za-z0-9_-]{32,128}")
                && incomingPort >= 1024 && incomingPort <= 65535) {
            SharedPreferences.Editor editor = prefs.edit().putString("token", incoming).putInt("port", incomingPort);
            String host = intent.getStringExtra("wifiHost");
            String fingerprint = intent.getStringExtra("wifiFingerprint");
            int tlsPort = intent.getIntExtra("wifiPort", 0);
            if (host != null && host.matches("(\\d{1,3}\\.){3}\\d{1,3}")
                    && fingerprint != null && fingerprint.matches("[a-f0-9]{64}")
                    && tlsPort >= 1024 && tlsPort <= 65535) {
                editor.putString("wifiHost", host).putString("wifiFingerprint", fingerprint).putInt("wifiPort", tlsPort);
            } else {
                editor.remove("wifiHost").remove("wifiFingerprint").remove("wifiPort");
            }
            editor.apply();
        }
        token = prefs.getString("token", "");
        port = prefs.getInt("port", 43125);
        wifiHost = prefs.getString("wifiHost", "");
        wifiFingerprint = prefs.getString("wifiFingerprint", "");
        wifiPort = prefs.getInt("wifiPort", 43126);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private GradientDrawable surface(int color, int radius, int border) {
        GradientDrawable shape = background(color, radius);
        shape.setStroke(dp(1), border);
        return shape;
    }

    private GradientDrawable hero(boolean active) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                active ? new int[]{Color.rgb(43, 122, 98), Color.rgb(18, 68, 59)}
                       : new int[]{Color.rgb(22, 51, 53), Color.rgb(15, 33, 38)});
        shape.setCornerRadius(dp(18));
        shape.setStroke(dp(active ? 1.5f : 1f), active ? GREEN : Color.rgb(42, 78, 75));
        return shape;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private Button button(String label, int color, int fontSize) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setTextSize(fontSize);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(7), dp(4), dp(7), dp(4));
        b.setBackground(background(color, 12));
        return b;
    }

    private final class MicrophoneIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean active;
        MicrophoneIcon(boolean active) { this.active = active; }
        @Override public int getIntrinsicWidth() { return dp(84); }
        @Override public int getIntrinsicHeight() { return dp(84); }
        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 100f, getBounds().height() / 100f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f);
            paint.setColor(active ? Color.argb(110, 154, 238, 199) : Color.argb(45, 154, 238, 199));
            canvas.drawCircle(50, 50, 47, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(active ? GREEN : Color.rgb(34, 83, 72));
            canvas.drawCircle(50, 50, 39, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(active ? Color.rgb(13, 50, 43) : GREEN);
            paint.setStrokeWidth(3.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawRoundRect(new RectF(41, 25, 59, 57), 9, 9, paint);
            canvas.drawArc(new RectF(32, 36, 68, 68), 0, 180, false, paint);
            canvas.drawLine(50, 68, 50, 76, paint);
            canvas.drawLine(41, 76, 59, 76, paint);
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private final class LevelView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LevelView() { super(MainActivity.this); setContentDescription("Mac 输入音量"); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int count = 36;
            float gap = dp(2.5f);
            float width = (getWidth() - gap * (count - 1)) / count;
            float barH = dp(12);
            float top = (getHeight() - barH) / 2f;
            boolean live = holding && !micStarting && !micStopping && meterStatus.equals("live");
            float target = live ? currentLevel : 0f;
            if (!live) {
                displayLevel = 0f;
            } else if (target >= displayLevel) {
                displayLevel = target;
            } else {
                displayLevel = Math.max(target, displayLevel - 0.055f);
                if (Math.abs(displayLevel - target) > 0.008f) postInvalidateOnAnimation();
            }
            int active = live ? Math.round(displayLevel * count) : 0;
            for (int i = 0; i < count; i++) {
                if (i < active) {
                    paint.setColor(i >= 31 ? CORAL : i >= 23 ? AMBER : GREEN);
                } else {
                    paint.setColor(TRACK);
                }
                float left = i * (width + gap);
                canvas.drawRoundRect(left, top, left + width, top + barH, dp(2.5f), dp(2.5f), paint);
            }
        }
    }

    private final class BarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int percent = -1;
        BarView() { super(MainActivity.this); }
        void setPercent(int value) {
            percent = value;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float h = getHeight();
            float r = h / 2f;
            paint.setColor(TRACK);
            canvas.drawRoundRect(0, 0, getWidth(), h, r, r, paint);
            if (percent >= 0) {
                float clamped = Math.max(0f, Math.min(100f, percent));
                float fillW = Math.max(clamped > 0 ? h : 0, getWidth() * (clamped / 100f));
                paint.setColor(clamped >= 90 ? CORAL : clamped >= 70 ? AMBER : GREEN);
                canvas.drawRoundRect(0, 0, fillW, h, r, r, paint);
            }
        }
    }

    private LinearLayout.LayoutParams weighted(float weight, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.leftMargin = dp(margin);
        return params;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(10), dp(13), dp(10));
        card.setBackground(surface(CARD, 15, BORDER));
        return card;
    }

    private void buildScreen() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(7), dp(12), dp(5));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(8, 13, 19), Color.rgb(13, 22, 31)}));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int iconId = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (iconId != 0) {
            ImageView logo = new ImageView(this);
            logo.setImageResource(iconId);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            header.addView(logo, new LinearLayout.LayoutParams(dp(28), dp(28)));
        } else {
            TextView mark = text("A", 14, BG, true);
            mark.setGravity(Gravity.CENTER);
            mark.setBackground(background(GREEN, 8));
            header.addView(mark, new LinearLayout.LayoutParams(dp(28), dp(28)));
        }
        LinearLayout brandBlock = new LinearLayout(this);
        brandBlock.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("ANDPAN", 13, TEXT, true);
        brand.setLetterSpacing(0.10f);
        brandBlock.addView(brand);
        TextView subtitle = text("VOICE DECK", 7, MUTED, true);
        subtitle.setLetterSpacing(0.14f);
        brandBlock.addView(subtitle);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(-2, -2);
        brandParams.leftMargin = dp(8);
        header.addView(brandBlock, brandParams);

        LinearLayout statusSlot = new LinearLayout(this);
        statusSlot.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(0, -2, 1);
        slotParams.leftMargin = dp(12);
        statusPill = new LinearLayout(this);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        statusPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        statusPill.setBackground(surface(CARD_ALT, 14, BORDER));
        status = text("●  等待 Mac", 10, MUTED, true);
        status.setSingleLine(true);
        statusPill.addView(status);
        statusSlot.addView(statusPill);
        header.addView(statusSlot, slotParams);

        micYouButton = button("语音 · 等待连接", CARD_ALT, 10);
        micYouButton.setSingleLine(true);
        micYouButton.setBackground(surface(CARD_ALT, 12, BORDER));
        header.addView(micYouButton, new LinearLayout.LayoutParams(dp(106), dp(30)));
        micYouButton.setOnClickListener(v -> {
            if (!hasAudioPermission()) {
                requestAudioPermission();
            } else if (!connected) {
                Toast.makeText(this, "请先连接 Mac", Toast.LENGTH_SHORT).show();
            } else if (!audioClient.isConnected()) {
                audioClient.reconnectNow();
                Toast.makeText(this, "正在重连语音通道", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "语音已直连 Mac，无需切换应用", Toast.LENGTH_SHORT).show();
            }
        });
        settingsButton = button("设置", CARD_ALT, 11);
        settingsButton.setSingleLine(true);
        settingsButton.setBackground(surface(CARD_ALT, 10, BORDER));
        header.addView(settingsButton, weighted(0, 6));
        settingsButton.getLayoutParams().width = dp(46);
        settingsButton.getLayoutParams().height = dp(30);
        settingsButton.setOnClickListener(v -> showConnectionSettings());
        Button exit = button("退出", CARD_ALT, 11);
        exit.setBackground(surface(CARD_ALT, 10, BORDER));
        header.addView(exit, weighted(0, 6));
        exit.getLayoutParams().width = dp(46);
        exit.getLayoutParams().height = dp(30);
        exit.setOnClickListener(v -> finish());
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(34)));

        LinearLayout body = new LinearLayout(this);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1);
        bodyParams.topMargin = dp(8);
        root.addView(body, bodyParams);

        micButton = new LinearLayout(this);
        micButton.setOrientation(LinearLayout.VERTICAL);
        micButton.setGravity(Gravity.CENTER);
        micButton.setPadding(dp(14), dp(14), dp(14), dp(14));
        micButton.setClickable(true);
        micButton.setContentDescription("按住说话，松手结束听写");
        micBadge = text("●  待命", 10, MUTED, true);
        micBadge.setPadding(dp(10), dp(3), dp(10), dp(3));
        micBadge.setBackground(surface(CARD_ALT, 12, BORDER));
        micButton.addView(micBadge, new LinearLayout.LayoutParams(-2, -2));
        View topGap = new View(this);
        micButton.addView(topGap, new LinearLayout.LayoutParams(1, 0, 1f));
        micIconView = new View(this);
        micButton.addView(micIconView, new LinearLayout.LayoutParams(dp(84), dp(84)));
        View bottomGap = new View(this);
        micButton.addView(bottomGap, new LinearLayout.LayoutParams(1, 0, 0.8f));
        micTitle = text("按住说话", 20, TEXT, true);
        micTitle.setGravity(Gravity.CENTER);
        micButton.addView(micTitle);
        micSubtitle = text("按住说话，松手结束", 11, Color.rgb(162, 207, 188), false);
        micSubtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-2, -2);
        subParams.topMargin = dp(4);
        micButton.addView(micSubtitle, subParams);
        micButton.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: startMic(false); return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!continuous) releaseMic();
                    v.performClick();
                    return true;
                default: return true;
            }
        });
        body.addView(micButton, new LinearLayout.LayoutParams(0, -1, 0.85f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        body.addView(right, weighted(1.60f, 8));

        LinearLayout audio = card();
        right.addView(audio, new LinearLayout.LayoutParams(-1, dp(88)));
        LinearLayout audioHeader = new LinearLayout(this);
        audioHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView audioTitle = text("声音输入", 13, TEXT, true);
        audioHeader.addView(audioTitle, new LinearLayout.LayoutParams(0, -2, 1));
        meterState = text("●  已静音", 10, MUTED, true);
        meterState.setPadding(dp(8), dp(2), dp(8), dp(2));
        meterState.setBackground(surface(CARD_ALT, 10, BORDER));
        audioHeader.addView(meterState);
        audio.addView(audioHeader);
        levelView = new LevelView();
        LinearLayout.LayoutParams levelParams = new LinearLayout.LayoutParams(-1, dp(24));
        levelParams.topMargin = dp(5);
        audio.addView(levelView, levelParams);
        inputName = text("Mac 输入 · 等待连接", 10, MUTED, false);
        inputName.setSingleLine(true);
        inputName.setEllipsize(TextUtils.TruncateAt.END);
        audio.addView(inputName);

        LinearLayout overview = new LinearLayout(this);
        LinearLayout.LayoutParams overviewParams = new LinearLayout.LayoutParams(-1, 0, 1);
        overviewParams.topMargin = dp(8);
        right.addView(overview, overviewParams);
        LinearLayout metrics = card();
        overview.addView(metrics, weighted(0.95f, 0));
        TextView metricsTitle = text("MAC 状态", 10, MUTED, true);
        metricsTitle.setLetterSpacing(0.06f);
        metrics.addView(metricsTitle);
        LinearLayout metricNumbers = new LinearLayout(this);
        metricNumbers.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metricNumberParams = new LinearLayout.LayoutParams(-1, 0, 1);
        metricNumberParams.topMargin = dp(4);
        metrics.addView(metricNumbers, metricNumberParams);
        LinearLayout cpu = new LinearLayout(this);
        cpu.setOrientation(LinearLayout.VERTICAL);
        cpu.setPadding(0, 0, dp(8), 0);
        cpu.addView(text("CPU", 9, MUTED, true));
        cpuValue = text("—", 21, TEXT, true);
        cpu.addView(cpuValue);
        cpuBar = new BarView();
        LinearLayout.LayoutParams cpuBarParams = new LinearLayout.LayoutParams(-1, dp(5));
        cpuBarParams.topMargin = dp(6);
        cpu.addView(cpuBar, cpuBarParams);
        metricNumbers.addView(cpu, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout memory = new LinearLayout(this);
        memory.setOrientation(LinearLayout.VERTICAL);
        memory.setPadding(dp(8), 0, 0, 0);
        memory.addView(text("内存", 9, MUTED, true));
        memoryValue = text("—", 21, TEXT, true);
        memory.addView(memoryValue);
        memBar = new BarView();
        LinearLayout.LayoutParams memBarParams = new LinearLayout.LayoutParams(-1, dp(5));
        memBarParams.topMargin = dp(6);
        memory.addView(memBar, memBarParams);
        metricNumbers.addView(memory, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout processes = card();
        overview.addView(processes, weighted(1.35f, 8));
        LinearLayout processHeader = new LinearLayout(this);
        TextView procTitle = text("资源占用", 10, MUTED, true);
        procTitle.setLetterSpacing(0.06f);
        processHeader.addView(procTitle, new LinearLayout.LayoutParams(0, -2, 1));
        processHeader.addView(text("CPU / 内存", 9, MUTED, false));
        processes.addView(processHeader);
        int[] rankColors = new int[]{GREEN, Color.rgb(110, 200, 168), Color.rgb(76, 154, 132)};
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(TRACK);
                processes.addView(sep, new LinearLayout.LayoutParams(-1, 1));
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(this);
            dot.setBackground(background(rankColors[i], 3));
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(5), dp(5));
            dotParams.rightMargin = dp(7);
            row.addView(dot, dotParams);
            processNames[i] = text("—", 10, TEXT, true);
            processNames[i].setSingleLine(true);
            processNames[i].setEllipsize(TextUtils.TruncateAt.END);
            row.addView(processNames[i], new LinearLayout.LayoutParams(0, -2, 1));
            processValues[i] = text("", 9, Color.rgb(168, 189, 196), false);
            processValues[i].setSingleLine(true);
            row.addView(processValues[i]);
            processes.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        }

        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(48));
        actionParams.topMargin = dp(8);
        right.addView(actions, actionParams);
        LinearLayout target = new LinearLayout(this);
        target.setOrientation(LinearLayout.VERTICAL);
        target.setGravity(Gravity.CENTER_VERTICAL);
        target.setPadding(dp(11), dp(4), dp(7), dp(4));
        target.setBackground(surface(CARD, 12, BORDER));
        target.addView(text("当前应用", 9, MUTED, false));
        targetName = text("等待连接", 12, TEXT, true);
        targetName.setSingleLine(true);
        targetName.setEllipsize(TextUtils.TruncateAt.END);
        target.addView(targetName);
        actions.addView(target, weighted(1.05f, 0));
        continuousButton = button("○  持续听写 · 关", CARD_ALT, 12);
        continuousButton.setBackground(surface(CARD_ALT, 12, BORDER));
        continuousButton.setOnClickListener(v -> {
            if (continuous) releaseMic(); else startMic(true);
        });
        actions.addView(continuousButton, weighted(1.05f, 6));
        sendButton = button("发送  ↑", GREEN, 13);
        sendButton.setTextColor(BG);
        sendButton.setOnClickListener(v -> execute("send"));
        actions.addView(sendButton, weighted(0.65f, 6));

        hint = text("Mac 输入 · 发送前请选中目标应用的输入框", 10, MUTED, false);
        hint.setSingleLine(true);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, dp(18));
        hintParams.topMargin = dp(5);
        root.addView(hint, hintParams);
        setContentView(root);
        updateButtons();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        if (!requestedAudioOnLaunch && !hasAudioPermission()) {
            requestedAudioOnLaunch = true;
            requestAudioPermission();
        }
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override protected void onPause() {
        visible = false;
        ui.removeCallbacks(poll);
        releaseMic();
        audioClient.disconnect();
        releaseLocks();
        super.onPause();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(heartbeat);
        audioClient.shutdown();
        commands.shutdown();
        microphone.shutdown();
        releaseLocks();
        super.onDestroy();
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            updateAudioConnectionPill();
            updateButtons();
        }
    }

    private void acquireLocks() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {}
    }

    private void startMic(boolean keepOpen) {
        if (!connected || (!keepOpen && !accessibilityReady) || !micAvailable || holding || micStopping) return;
        if (!hasAudioPermission()) {
            requestAudioPermission();
            hint.setText("请允许 AndPan 使用手机麦克风权限后再按住说话");
            return;
        }
        holding = true;
        continuous = keepOpen;
        micStarting = true;
        final int generation = ++micGeneration;
        if (keepOpen) acquireLocks();
        updateButtons();
        microphone.execute(() -> {
            try {
                audioClient.startStreaming();
                JSONObject payload = new JSONObject();
                payload.put("fn", !keepOpen);
                payload.put("continuous", keepOpen);
                JSONObject result = request("POST", "/mic/start", payload);
                ui.post(() -> {
                    if (holding && generation == micGeneration) {
                        micStarting = false;
                        macMuted = false;
                        applyMeter(result.optJSONObject("meter"));
                        updateButtons();
                        ui.postDelayed(heartbeat, 300);
                    }
                });
            } catch (Exception e) {
                audioClient.stopStreaming();
                ui.post(() -> {
                    if (holding && generation == micGeneration) {
                        holding = false;
                        continuous = false;
                        micStarting = false;
                        micGeneration++;
                        ui.removeCallbacks(heartbeat);
                        updateButtons();
                        showError(message(e));
                    }
                });
            }
        });
    }

    private void releaseMic() {
        if (!holding) return;
        holding = false;
        continuous = false;
        micStarting = false;
        micStopping = true;
        final int generation = ++micGeneration;
        releaseLocks();
        ui.removeCallbacks(heartbeat);
        updateButtons();
        microphone.execute(() -> {
            try {
                audioClient.stopStreaming();
                request("POST", "/mic/stop", new JSONObject());
                ui.post(() -> {
                    if (generation == micGeneration) {
                        micStopping = false;
                        macMuted = true;
                        updateButtons();
                        loadStatus();
                    }
                });
            }
            catch (Exception e) {
                ui.post(() -> {
                    if (generation == micGeneration) {
                        micStopping = false;
                        macMuted = null;
                        updateButtons();
                        showError(message(e));
                        loadStatus();
                    }
                });
            }
        });
    }

    private void loadStatus() {
        if (commandBusy || statusPending) return;
        statusPending = true;
        commands.execute(() -> {
            try {
                JSONObject result = request("GET", "/status", null);
                ui.post(() -> {
                    connected = true;
                    applyStatus(result);
                    statusPending = false;
                    updateButtons();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    connected = false;
                    targetPid = 0;
                    micAvailable = false;
                    accessibilityReady = false;
                    macMuted = null;
                    micYouServiceState = "";
                    micYouServiceError = "";
                    macInputError = "";
                    serverApkAvailable = false;
                    serverApkSha256 = "";
                    statusPending = false;
                    if (holding) releaseMic();
                    audioClient.disconnect();
                    status.setText("●  Mac 未连接");
                    status.setTextColor(MUTED);
                    statusPill.setBackground(surface(CARD_ALT, 14, BORDER));
                    updateAudioConnectionPill();
                    targetName.setText("等待连接");
                    inputName.setText("Mac 输入 · 等待连接");
                    cpuValue.setText("—");
                    memoryValue.setText("—");
                    cpuPercent = -1;
                    memPercent = -1;
                    cpuBar.setPercent(-1);
                    memBar.setPercent(-1);
                    for (int i = 0; i < 3; i++) {
                        processNames[i].setText("—");
                        processValues[i].setText("");
                    }
                    hint.setText("请在 Mac 打开 AndPan；点「设置」检查连接地址。");
                    updateButtons();
                    // 绝不在后台自动发射 UDP 全网广播，避免劣质路由器因空口广播风暴导致 Wi-Fi 假死
                    // 已保存 Mac 地址时直接走定向 TCP 重试
                });
            }
        });
    }

    private void execute(String action) {
        if (commandBusy || targetPid <= 0) return;
        try {
            JSONObject body = new JSONObject();
            body.put("action", action);
            body.put("pid", targetPid);
            body.put("eventId", UUID.randomUUID().toString());
            runCommand("/command", body);
        } catch (Exception e) { showError(message(e)); }
    }

    private void runCommand(String path, JSONObject body) {
        commandBusy = true;
        updateButtons();
        commands.execute(() -> {
            try {
                request("POST", path, body);
                JSONObject fresh = null;
                try { fresh = request("GET", "/status", null); } catch (Exception ignored) {}
                final JSONObject state = fresh;
                ui.post(() -> {
                    connected = true;
                    if (state != null) applyStatus(state);
                    commandBusy = false;
                    updateButtons();
                    Toast.makeText(this, "已发送回车", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    commandBusy = false;
                    showError(e instanceof ServerRejection ? message(e) : "发送结果未确认，请先检查电脑，避免重复发送");
                    updateButtons();
                    loadStatus();
                });
            }
        });
    }

    private void applyMeter(JSONObject data) {
        meterStatus = data == null ? "unavailable" : data.optString("state", "unavailable");
        currentLevel = data != null && !data.isNull("level") ? (float) data.optDouble("level", 0) : 0;
        updateAudio();
    }

    private void updateAudio() {
        if (meterState == null) return;
        String label;
        if (!connected) label = "●  等待连接";
        else if (micStopping) label = "●  正在静音";
        else if (micStarting) label = "●  正在开启";
        else if (holding) label = meterStatus.equals("live") ? "●  实时输入" :
                meterStatus.equals("starting") ? "●  正在读取" : "●  无法读取音量";
        else label = macMuted == null ? "●  状态未知" : macMuted ? "●  已静音" : "●  未静音";
        meterState.setText(label);
        boolean live = holding && meterStatus.equals("live");
        meterState.setTextColor(live ? GREEN : MUTED);
        meterState.setBackground(surface(live ? GREEN_DIM : CARD_ALT, 10, live ? GREEN_BORDER : BORDER));
        levelView.invalidate();
    }

    private void applyStatus(JSONObject data) {
        status.setText("●  Mac 已连接");
        status.setTextColor(GREEN);
        statusPill.setBackground(surface(GREEN_DIM, 14, GREEN_BORDER));
        JSONObject system = data.optJSONObject("system");
        accessibilityReady = system != null && system.optBoolean("accessibility", false);
        JSONObject target = system == null ? null : system.optJSONObject("target");
        targetPid = target == null ? 0 : target.optInt("pid", 0);
        String currentApp = target == null ? "" : target.optString("name", "");
        targetName.setText(targetPid > 0 && !currentApp.isEmpty() ? currentApp : "无法获取");
        if (system != null) {
            cpuPercent = system.isNull("cpu") ? -1 : system.optInt("cpu");
            memPercent = system.isNull("memory") ? -1 : system.optInt("memory");
            cpuValue.setText(cpuPercent < 0 ? "—" : cpuPercent + "%");
            memoryValue.setText(memPercent < 0 ? "—" : memPercent + "%");
            cpuBar.setPercent(cpuPercent);
            memBar.setPercent(memPercent);
            org.json.JSONArray top = system.optJSONArray("processes");
            for (int i = 0; i < 3; i++) {
                JSONObject process = top == null ? null : top.optJSONObject(i);
                processNames[i].setText(process == null ? "—" : process.optString("name", "—"));
                processValues[i].setText(process == null ? "" : String.format(java.util.Locale.US,
                        "%.1f%%  %dM", process.optDouble("cpu", 0), process.optInt("memoryMB", 0)));
            }
        }
        JSONObject mic = data.optJSONObject("microphone");
        JSONObject micYou = data.optJSONObject("micYou");
        audioPort = micYou == null ? 8554 : micYou.optInt("port", 8554);
        micYouServiceState = micYou == null ? "" : micYou.optString("service", "");
        micYouServiceError = micYou == null ? "" : micYou.optString("error", "");
        if (visible && audioPort > 0) {
            audioClient.ensureConnected(wifiHost.isEmpty() ? "127.0.0.1" : wifiHost, audioPort);
        }
        if (mic != null) {
            micAvailable = mic.optBoolean("available", false);
            if (!micStarting && !micStopping) macMuted = mic.isNull("muted") ? null : mic.optBoolean("muted");
        }
        macInputError = mic == null ? "" : mic.optString("error", "");
        String device = system == null ? "" : system.optString("input", "");
        inputName.setText("Mac 输入  ·  " + (device.isEmpty() ? "无法读取" : device) +
                (micAvailable ? "  ·  正常" : "  ·  不可用"));
        JSONObject apk = data.optJSONObject("apk");
        serverApkAvailable = apk != null && apk.optBoolean("available", false);
        serverApkSha256 = apk == null ? "" : apk.optString("sha256", "");
        updateAudioConnectionPill();
        updateAudio();
    }

    private void updateAudioConnectionPill() {
        if (micYouButton == null || audioClient == null) return;
        if (!hasAudioPermission()) {
            micYouButton.setText("需录音权限");
            micYouButton.setTextColor(AMBER);
            micYouButton.setBackground(surface(Color.rgb(48, 38, 22), 12, Color.rgb(120, 95, 48)));
        } else if (!connected) {
            micYouButton.setText("语音 · 未连接");
            micYouButton.setTextColor(MUTED);
            micYouButton.setBackground(surface(CARD_ALT, 12, BORDER));
        } else if (audioClient.isConnected()) {
            micYouButton.setText("语音 · 已直连");
            micYouButton.setTextColor(GREEN);
            micYouButton.setBackground(surface(GREEN_DIM, 12, GREEN_BORDER));
        } else if (audioClient.isConnecting()) {
            micYouButton.setText("语音 · 连接中");
            micYouButton.setTextColor(MUTED);
            micYouButton.setBackground(surface(CARD_ALT, 12, BORDER));
        } else {
            micYouButton.setText("语音 · 重连中");
            micYouButton.setTextColor(MUTED);
            micYouButton.setBackground(surface(CARD_ALT, 12, BORDER));
        }
        if (!connected) return;
        if (!hasAudioPermission()) {
            hint.setText("请允许 AndPan 使用手机麦克风权限后再按住说话");
        } else if (!accessibilityReady) {
            hint.setText("Mac 设备控制权限：请移除并重新添加 AndPan Mac");
        } else if (!micYouServiceError.isEmpty()) {
            hint.setText("语音服务：" + micYouServiceError);
        } else if (!macInputError.isEmpty()) {
            hint.setText("麦克风：" + macInputError);
        } else if (!audioClient.isConnected()) {
            String error = audioClient.getLastError();
            hint.setText(error.isEmpty() ? "正在连接 Mac 语音通道…" : "语音通道：" + error);
        } else {
            hint.setText("语音已直连 Mac · 发送前请选中目标应用的输入框");
        }
    }

    private void updateButtons() {
        if (sendButton == null) return;
        sendButton.setEnabled(connected && accessibilityReady && targetPid > 0 && !commandBusy && (!holding || continuous) && !micStopping);
        sendButton.setAlpha(sendButton.isEnabled() ? 1f : 0.4f);
        GradientDrawable sendBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(164, 242, 205), Color.rgb(101, 217, 165)});
        sendBg.setCornerRadius(dp(12));
        sendButton.setBackground(sendBg);
        micButton.setEnabled(connected && accessibilityReady && micAvailable && !micStopping);
        micButton.setAlpha(micButton.isEnabled() ? 1f : 0.5f);
        boolean active = holding && !micStarting;
        micBadge.setText(micStarting ? "●  正在开启" : active ? (continuous ? "●  常开收音" : "●  正在收音") : "●  待命");
        micBadge.setTextColor(active ? GREEN : micStarting ? AMBER : MUTED);
        micBadge.setBackground(surface(active ? GREEN_DIM : CARD_ALT, 12, active ? GREEN_BORDER : BORDER));
        micTitle.setText(micStarting ? "正在开启" : active ? (continuous ? "麦克风常开" : "正在听写") : "按住说话");
        micSubtitle.setText(continuous ? "仅保持收音 · 点击右侧关闭" : holding ? "松手结束" : "按住说话，松手结束");
        micIconView.setBackground(new MicrophoneIcon(active));
        micButton.setBackground(hero(active));
        continuousButton.setEnabled(connected && micAvailable && !micStopping && (!holding || continuous));
        continuousButton.setAlpha(continuousButton.isEnabled() ? 1f : 0.4f);
        continuousButton.setText(continuous ? "●  持续听写 · 开" : "○  持续听写 · 关");
        continuousButton.setTextColor(continuous ? BG : TEXT);
        continuousButton.setBackground(continuous ? background(GREEN, 12) : surface(CARD_ALT, 12, BORDER));
        if (settingsButton != null) {
            boolean updateReady = hasApkUpdate();
            String targetLabel = updateReady ? "设置 · 新版" : "设置";
            if (!targetLabel.contentEquals(settingsButton.getText())) {
                settingsButton.setText(targetLabel);
                settingsButton.setTextColor(updateReady ? AMBER : TEXT);
                settingsButton.setBackground(updateReady
                        ? surface(Color.rgb(48, 38, 22), 10, Color.rgb(120, 95, 48))
                        : surface(CARD_ALT, 10, BORDER));
                settingsButton.getLayoutParams().width = dp(updateReady ? 78 : 46);
                settingsButton.requestLayout();
            }
        }
        updateAudio();
    }

    private boolean hasApkUpdate() {
        return connected && serverApkAvailable && !serverApkSha256.isEmpty()
                && !installedApkSha256.isEmpty() && !serverApkSha256.equals(installedApkSha256);
    }

    private String message(Exception e) {
        if (e instanceof SSLException) return "Mac 与已配对的电脑不一致，请通过 USB 重新连接";
        if (e instanceof ConnectException || e instanceof SocketTimeoutException)
            return "请检查 Mac 地址、Wi-Fi 和 AndPan 服务";
        return e.getMessage() == null ? "连接失败，请检查 Mac" : e.getMessage();
    }

    private void showError(String error) {
        hint.setText(error);
        Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }

    private void showConnectionSettings() {
        releaseMic();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), dp(8));
        TextView instructions = new TextView(this);
        instructions.setText("手机会自动查找已配对的 Mac，也可以手动填写 Mac 上显示的地址。首次使用请通过 USB 安装手机端。");
        instructions.setTextSize(14);
        form.addView(instructions);
        EditText host = new EditText(this);
        host.setSingleLine(true);
        host.setHint("Mac 局域网地址，例如 192.168.0.98");
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        host.setText(wifiHost);
        form.addView(host);
        TextView error = new TextView(this);
        error.setTextColor(Color.rgb(255, 160, 150));
        form.addView(error);
        Button wifiUpdate = button(
                hasApkUpdate() ? "发现新版 · 通过 Wi-Fi 更新手机端" : "通过 Wi-Fi 重新安装 / 更新手机端",
                CARD_ALT, 13);
        wifiUpdate.setBackground(surface(hasApkUpdate() ? GREEN_DIM : CARD_ALT, 10, hasApkUpdate() ? GREEN_BORDER : BORDER));
        wifiUpdate.setTextColor(hasApkUpdate() ? GREEN : TEXT);
        wifiUpdate.setEnabled(connected && serverApkAvailable);
        wifiUpdate.setAlpha(wifiUpdate.isEnabled() ? 1f : 0.45f);
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(-1, dp(38));
        updateParams.topMargin = dp(10);
        form.addView(wifiUpdate, updateParams);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("连接设置")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setNeutralButton("自动查找", null)
                .setPositiveButton("测试并保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button scan = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            wifiUpdate.setOnClickListener(v -> {
                save.setEnabled(false);
                scan.setEnabled(false);
                wifiUpdate.setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                dialog.setCancelable(false);
                error.setTextColor(MUTED);
                error.setText("正在通过 Wi-Fi 下载新版安装包…");
                commands.execute(() -> {
                    try {
                        File apkFile = downloadApkToCache();
                        ui.post(() -> {
                            dialog.dismiss();
                            installDownloadedApk(apkFile);
                        });
                    } catch (Exception e) {
                        ui.post(() -> {
                            save.setEnabled(true);
                            scan.setEnabled(true);
                            wifiUpdate.setEnabled(connected && serverApkAvailable);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                            dialog.setCancelable(true);
                            error.setTextColor(Color.rgb(255, 160, 150));
                            error.setText("更新失败：" + message(e));
                        });
                    }
                });
            });
            scan.setOnClickListener(v -> {
                scan.setEnabled(false);
                error.setText("正在查找 Mac…");
                discoverMac((found, text) -> {
                    scan.setEnabled(true);
                    error.setTextColor(found ? GREEN : Color.rgb(255, 160, 150));
                    error.setText(text);
                    if (found) host.setText(wifiHost);
                });
            });
            save.setOnClickListener(v -> {
                final String address = host.getText().toString().trim();
                final int newPort = wifiPort;
                if (!validAddress(address)) {
                    error.setText("请输入正确的 Mac 地址"); return;
                }
                if (token == null || token.isEmpty() || wifiFingerprint.isEmpty()) {
                    error.setText("请先用 USB 连接，并在 Mac 上点击安装 / 更新手机端"); return;
                }
                save.setEnabled(false);
                scan.setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                dialog.setCancelable(false);
                error.setText("正在测试连接…");
                commandBusy = true;
                updateButtons();
                commands.execute(() -> {
                    try {
                        JSONObject verified = request("GET", "/health", null, address, newPort,
                                wifiFingerprint, token);
                        if (!getSharedPreferences("connection", MODE_PRIVATE).edit()
                                .putString("wifiHost", address).putInt("wifiPort", newPort)
                                .commit()) throw new Exception("无法保存连接设置");
                        ui.post(() -> {
                            wifiHost = address;
                            wifiPort = newPort;
                            connected = true;
                            commandBusy = false;
                            applyStatus(verified);
                            updateButtons();
                            dialog.dismiss();
                            Toast.makeText(this, "连接成功，设置已保存", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        ui.post(() -> {
                            commandBusy = false;
                            updateButtons();
                            save.setEnabled(true);
                            scan.setEnabled(true);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                            dialog.setCancelable(true);
                            error.setText("连接失败：" + message(e));
                        });
                    }
                });
            });
        });
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private interface DiscoveryCallback { void complete(boolean found, String message); }

    private void discoverMac(DiscoveryCallback callback) {
        if (discovering) {
            if (callback != null) callback.complete(false, "正在查找，请稍候");
            return;
        }
        lastDiscovery = System.currentTimeMillis();
        if (token == null || token.isEmpty() || !wifiFingerprint.matches("[a-f0-9]{64}")) {
            if (callback != null) callback.complete(false, "请先用 USB 安装手机端并完成连接");
            return;
        }
        discovering = true;
        final String savedToken = token, savedFingerprint = wifiFingerprint, previousHost = wifiHost;
        final int previousPort = wifiPort;
        commands.execute(() -> {
            String address = "";
            int foundPort = 0;
            JSONObject state = null;
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                socket.setSoTimeout(2500);
                byte[] query = "ANDPAN_DISCOVER_V1".getBytes(StandardCharsets.UTF_8);
                try {
                    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                    while (interfaces != null && interfaces.hasMoreElements()) {
                        NetworkInterface network = interfaces.nextElement();
                        if (!network.isUp() || network.isLoopback()) continue;
                        for (InterfaceAddress networkAddress : network.getInterfaceAddresses()) {
                            InetAddress broadcast = networkAddress.getBroadcast();
                            if (broadcast instanceof Inet4Address) {
                                try { socket.send(new DatagramPacket(query, query.length, broadcast, 43127)); }
                                catch (Exception ignored) {}
                            }
                        }
                    }
                } catch (Exception ignored) {}
                long deadline = System.currentTimeMillis() + 2500;
                while (System.currentTimeMillis() < deadline) {
                    byte[] buffer = new byte[128];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
                    socket.receive(packet);
                    String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    String candidate = packet.getAddress().getHostAddress();
                    if (!response.matches("ANDPAN_V1:[0-9]{4,5}") || !validAddress(candidate)) continue;
                    int candidatePort = Integer.parseInt(response.substring("ANDPAN_V1:".length()));
                    if (candidatePort < 1024 || candidatePort > 65535) continue;
                    try {
                        state = request("GET", "/health", null, candidate, candidatePort, savedFingerprint, savedToken);
                        address = candidate;
                        foundPort = candidatePort;
                        break;
                    } catch (Exception ignored) {}
                }
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                android.util.Log.w("AndPan", "自动查找不可用", e);
            }
            if (!address.isEmpty() && token.equals(savedToken) && wifiFingerprint.equals(savedFingerprint)
                    && wifiHost.equals(previousHost) && wifiPort == previousPort) {
                if (!getSharedPreferences("connection", MODE_PRIVATE).edit()
                        .putString("wifiHost", address).putInt("wifiPort", foundPort).commit()) address = "";
            } else address = "";
            final String foundAddress = address;
            final int savedPort = foundPort;
            final JSONObject verified = state;
            ui.post(() -> {
                discovering = false;
                if (!foundAddress.isEmpty()) {
                    android.util.Log.i("AndPan", "已自动连接已配对的 Mac");
                    wifiHost = foundAddress;
                    wifiPort = savedPort;
                    connected = true;
                    applyStatus(verified);
                    updateButtons();
                    if (callback != null) callback.complete(true, "已自动找到并连接 Mac");
                } else if (callback != null) {
                    callback.complete(false, "未找到已配对的 Mac，请检查 Wi-Fi 或手动填写地址");
                }
            });
        });
    }

    private boolean validAddress(String address) {
        if (!address.matches("(\\d{1,3}\\.){3}\\d{1,3}")) return false;
        for (String part : address.split("\\.")) if (Integer.parseInt(part) > 255) return false;
        return true;
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        if (token == null || token.isEmpty()) throw new Exception("请打开设置并连接 Mac");
        return request(method, path, body, wifiHost, wifiPort, wifiFingerprint, token);
    }

    private String computeFileSha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private File downloadApkToCache() throws Exception {
        if (token == null || token.isEmpty()) throw new Exception("请先连接 Mac");
        HttpURLConnection connection = openConnection("GET", "/apk/andpan.apk", wifiHost, wifiPort, wifiFingerprint, token);
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive, application/json");
        connection.setReadTimeout(60000);
        File target = new File(getCacheDir(), "update.apk");
        File tmp = new File(getCacheDir(), "update.apk.tmp");
        try {
            int code = connection.getResponseCode();
            if (code >= 400) {
                InputStream err = connection.getErrorStream();
                String detail = "下载安装包失败";
                if (err != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = err.read(buf)) != -1 && out.size() < 16384) out.write(buf, 0, n);
                    try { detail = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)).optString("error", detail); }
                    catch (Exception ignored) {}
                }
                throw new ServerRejection(detail);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 32 * 1024 * 1024) throw new Exception("安装包体积异常");
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            String expectedHash = connection.getHeaderField("X-Apk-Sha256");
            if (expectedHash != null && !expectedHash.isEmpty() && !expectedHash.equalsIgnoreCase(sb.toString())) {
                throw new Exception("安装包校验失败，请重试");
            }
            if (target.exists()) target.delete();
            if (!tmp.renameTo(target)) throw new Exception("无法保存安装包");
            return target;
        } finally {
            if (tmp.exists()) tmp.delete();
            connection.disconnect();
        }
    }

    private void installDownloadedApk(File apkFile) {
        if (!apkFile.isFile()) return;
        Uri apkUri = Uri.parse("content://com.andpan.deck.apk/update.apk");
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        install.setDataAndType(apkUri, "application/vnd.android.package-archive");
        install.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        install.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        try {
            startActivity(install);
        } catch (Exception ignored) {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(apkUri, "application/vnd.android.package-archive");
            view.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
        }
    }

    private JSONObject request(String method, String path, JSONObject body, String host, int tlsPort,
                               String fingerprint, String bearer) throws Exception {
        HttpURLConnection connection = openConnection(method, path, host, tlsPort, fingerprint, bearer);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (stream != null) {
                try (InputStream input = stream) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + count > 65536) throw new Exception("电脑响应过大");
                        bytes.write(buffer, 0, count);
                    }
                }
            }
            JSONObject response = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (code >= 400) throw new ServerRejection(response.optString("error", "电脑操作失败"));
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(String method, String path, String host, int tlsPort,
                                             String fingerprint, String bearer) throws Exception {
        HttpURLConnection connection;
        if (host.isEmpty()) {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        } else {
            final String destination = host;
            if (!fingerprint.matches("[a-f0-9]{64}"))
                throw new Exception("无线连接信息无效，请通过 USB 重新连接");
            byte[] expected = new byte[32];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (byte) Integer.parseInt(fingerprint.substring(i * 2, i * 2 + 2), 16);
            }
            X509TrustManager trust = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    throw new CertificateException("不接受客户端证书");
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    try {
                        if (chain == null || chain.length == 0) throw new CertificateException("Mac 证书缺失");
                        byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                        if (!MessageDigest.isEqual(expected, actual)) {
                            throw new CertificateException("Mac 证书不匹配，请重新配对");
                        }
                    } catch (CertificateException e) { throw e; }
                    catch (Exception e) { throw new CertificateException(e); }
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trust}, null);
            HttpsURLConnection secure = (HttpsURLConnection) new URL("https://" + destination + ":" + tlsPort + path).openConnection();
            secure.setSSLSocketFactory(context.getSocketFactory());
            // The local IP has no public hostname; the paired certificate authenticates the Mac.
            secure.setHostnameVerifier((hostname, session) -> {
                try {
                    byte[] actual = MessageDigest.getInstance("SHA-256").digest(session.getPeerCertificates()[0].getEncoded());
                    return destination.equals(hostname) && MessageDigest.isEqual(expected, actual);
                } catch (Exception e) { return false; }
            });
            connection = secure;
        }
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(20000);
        if (!bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        return connection;
    }
}
