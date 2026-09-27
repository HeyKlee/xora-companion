package nz.kelly.xora.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * XORA Companion: the phone-side of the workspace.
 *
 * Layout is orb-first. The Orb is the main focus and takes the top of the screen;
 * controls are icon-only beneath it, because a phone has no room for titles.
 * The dashboard only appears once the device is actually paired, so the user is
 * never shown controls that would fail.
 *
 * The audio handoff is a real protocol, not a label: claim, poll for a pending
 * transfer, acknowledge it to take the lease, and release to give it back.
 */
public class MainActivity extends Activity {

    /**
     * The LIVE XORA server, not the preview. 9105 serves var/app.db, which is
     * where real accounts and real paired devices live; 9106 serves
     * var/preview/app.db, where the phone has no enrolment, so a default of
     * 9106 makes every device token come back 401 "invalid or revoked".
     * 9105 is TLS on the tailnet cert this app already pins.
     */
    private static final String DEFAULT_HOST = "100.64.0.1:9105";

    private static final String PREFS = "xora_companion";
    private static final String KEY_HOST = "host";
    private static final String KEY_TOKEN = "device_token";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_CURSOR = "transcript_cursor";
    private static final int REQ_MIC = 7;
    private static final long POLL_MS = 4000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable poller;

    private TextView statusView;
    private TextView leaseView;

    /*
     * The Orb is a WebView running the REAL holo-orb.js from the server, not a
     * Canvas reimplementation. See res/raw/orb_host.html for why.
     */
    private WebView orbWeb;
    private volatile boolean orbReady = false;

    // pairing surface
    private LinearLayout pairCard;
    private EditText hostInput;
    private EditText codeInput;
    private Button pairButton;

    // dashboard surface: one floating dock plus a grouped panel tray
    private LinearLayout dock;
    private LinearLayout panelTray;
    private LinearLayout centerColumn;
    private IconButton panelsBtn;
    private IconButton micBtn;
    private IconButton handoffBtn;
    private IconButton chatBtn;
    private IconButton friendsBtn;
    private IconButton syncBtn;
    private IconButton claimBtn;
    private IconButton releaseBtn;
    private IconButton logBtn;
    private IconButton hostBtn;

    private XoraClient client;
    private MediaRecorder recorder;
    private AudioRecord meter;
    private volatile boolean micOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        hostInput.setText(p.getString(KEY_HOST, DEFAULT_HOST));
        if (p.getString(KEY_TOKEN, null) != null) {
            // Paired: show the dashboard straight away.
            enterDashboard(p);
        } else {
            showPairing();
        }
    }

    // ---- layout ----------------------------------------------------------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, float sp, String colour) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor(colour));
        t.setTextSize(sp);
        return t;
    }

    /**
     * Load the real Orb from the paired host's /static over the pinned TLS cert.
     *
     * The host page is bundled in res/raw so the shell of the app is local, but
     * the Orb itself, Three.js and the postprocessing addons all come from the
     * server the phone is already paired with. That is the whole point: it is
     * literally the same holo-orb.js the desktop runs, so it cannot drift.
     */
    private void loadOrb(String host) {
        if (orbWeb == null) {
            return;
        }
        final String base = XoraClient.baseUrl(host);
        final String html = "file:///android_asset/orb_host.html";
        final String target = html + "#" + base;
        orbReady = false;
        // The bundled page is a file:// document, so its own /static references
        // need an explicit origin. Injecting it per-load is the simplest correct
        // approach: a <base> tag makes the module and import map resolve against
        // the paired host rather than the (nonexistent) file:// origin.
        orbWeb.loadDataWithBaseURL(
                base,
                injectOrbBase(readRawAsset("orb_host.html"), base),
                "text/html",
                "UTF-8",
                null);
    }

    /** Rewrite the bundled page so its /static URLs resolve to the paired host. */
    private String injectOrbBase(String html, String base) {
        // The page already ships the absolute /static paths and an import map;
        // the base URL supplied to loadDataWithBaseURL resolves them. All that
        // is strictly needed is to make sure there is no file:// leftover.
        return html.replace("file://", base + "/");
    }

    private String readRawAsset(String name) {
        try (java.io.InputStream in = getResources().openRawResource(R.raw.orb_host)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            android.util.Log.e("XoraOrb", "could not read raw asset " + name, e);
            return "<html><body></body></html>";
        }
    }

    /** Receives lifecycle messages from the Orb host page. */
    private final class OrbBridge {
        @JavascriptInterface
        public void postMessage(String raw) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(raw);
                String type = o.optString("type");
                if ("orb-ready".equals(type)) {
                    orbReady = true;
                    android.util.Log.i("XoraOrb", "real holo-orb.js reported ready");
                } else if ("orb-error".equals(type)) {
                    android.util.Log.e("XoraOrb", "orb host error: " + o.optString("message"));
                }
            } catch (Exception e) {
                android.util.Log.e("XoraOrb", "bad bridge payload: " + raw, e);
            }
        }
    }

    /**
     * Pause or resume the real Orb.
     *
     * The real window.XoraOrb API is { setActive(bool), setOrbType(str),
     * setRingType(str), dispose(), ready }. setActive is a PAUSE control, not a
     * state colour: it backs the desktop's "Companion paused" toggle. holo-orb.js
     * deliberately exposes no state-colour entry point, so the phone does not
     * invent one. Connection state is carried by the status dot and the status
     * line, which is the same division of labour the desktop already uses.
     */
    private void setOrbActive(final boolean active) {
        runOnUiThread(() -> {
            if (orbWeb == null) {
                return;
            }
            orbWeb.evaluateJavascript(
                    "(function(){var o=window.XoraOrb;if(!o||!o.setActive){return false;}"
                            + "o.setActive(" + active + ");return true;})()",
                    value -> {
                        if (value != null && value.contains("false")) {
                            android.util.Log.w("XoraOrb", "XoraOrb API absent; setActive not applied");
                        }
                    });
        });
    }

    private void buildUi() {
        // FrameLayout, not a ScrollView: the Orb must be the full-bleed
        // background with the dock floating over it, exactly like the desktop
        // shell. There is NO title bar and no app bar.
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.parseColor("#070b12"));

        // ---- the Orb: the real holo-orb.js, in a transparent WebView ----------
        // This replaces a native Canvas OrbView, which was the wrong tool: the
        // real Orb is Three.js + UnrealBloomPass with additive blending, and
        // Canvas 2D cannot blend additively, so the bloom stacked into a flat
        // violet disc and the twinkle flashed white. Running the actual module
        // is both less code and the only way it looks like the desktop.
        orbWeb = new WebView(this);
        WebSettings ws = orbWeb.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(false);
        // The Orb is our own code from our own pinned host. It needs no file
        // access, no geolocation, no third-party cookies.
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMediaPlaybackRequiresUserGesture(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setUseWideViewPort(false);
        ws.setLoadWithOverviewMode(false);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        // Transparent so the native window background (#070b12) is the Orb's
        // backdrop, with the native dock floating over the top of it.
        orbWeb.setBackgroundColor(0x00000000);
        orbWeb.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // Touch belongs to the dock; the Orb's own drag/repel would fight it.
        orbWeb.setOnTouchListener((v, ev) -> false);
        // Keep the old affordance: tapping the Orb refreshes the connection.
        orbWeb.setOnClickListener(v -> refresh());
        orbWeb.setContentDescription("XORA Orb. Tap to refresh connection.");
        orbWeb.addJavascriptInterface(new OrbBridge(), "XoraAndroid");
        // Refuse to navigate anywhere: a redirect off-host would break the
        // pinned-cert guarantee the rest of the app depends on.
        orbWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return null; // load normally; same-origin is pinned by the host
            }
        });
        FrameLayout.LayoutParams orbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        frame.addView(orbWeb, orbLp);
        // The Orb renders from the paired host's /static. Until it is paired
        // there is no origin to load, so the WebView stays empty and the pair
        // card is all the user sees.
        loadOrb(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_HOST, DEFAULT_HOST));

        // Status sits just under the Orb, deliberately minimal: the Orb's colour
        // is the primary state signal, so the text only confirms it in words.
        centerColumn = new LinearLayout(this);
        centerColumn.setOrientation(LinearLayout.VERTICAL);
        centerColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        centerColumn.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        statusView = label("", 11, "#8ea4bb");
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(10), 0, 0);
        centerColumn.addView(statusView);

        leaseView = label("", 11, "#fbbf24");
        leaseView.setGravity(Gravity.CENTER);
        centerColumn.addView(leaseView);

        // Push the text block below the Orb's visual centre, leaving room for
        // the floating dock at the bottom.
        centerColumn.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 2.4f));

        FrameLayout.LayoutParams centreLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        centreLp.bottomMargin = dp(150);
        frame.addView(centerColumn, centreLp);

        // ---- pairing card (only when not paired) --------------------------
        pairCard = new LinearLayout(this);
        pairCard.setOrientation(LinearLayout.VERTICAL);
        pairCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        pairCard.setBackground(card("#111b28"));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = dp(24);
        cardLp.rightMargin = dp(24);
        cardLp.bottomMargin = dp(110);
        frame.addView(pairCard, cardLp);
        hostInput = new EditText(this);
        hostInput.setTextColor(Color.parseColor("#e6f0ff"));
        hostInput.setHintTextColor(Color.parseColor("#5b6b7d"));
        hostInput.setHint(DEFAULT_HOST);
        hostInput.setSingleLine(true);
        pairCard.addView(hostInput);

        pairCard.addView(label("Pairing code from your PC. Generate one in the workspace under Voice & Devices. Codes expire after 5 minutes.", 12, "#8ea4bb"));
        codeInput = new EditText(this);
        codeInput.setTextColor(Color.parseColor("#e6f0ff"));
        codeInput.setHintTextColor(Color.parseColor("#5b6b7d"));
        codeInput.setHint("pairing code");
        codeInput.setSingleLine(true);
        pairCard.addView(codeInput);

        pairButton = new Button(this);
        pairButton.setText("Pair this device");
        pairButton.setOnClickListener(v -> pairDevice());
        pairCard.addView(pairButton);

        // ---- grouped panel tray: opens from the PANELS dock button ---------
        panelTray = buildPanelTray();
        FrameLayout.LayoutParams trayLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trayLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        trayLp.bottomMargin = dp(74);
        frame.addView(panelTray, trayLp);

        // ---- the floating dock --------------------------------------------
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setBackground(dockBackground());
        dock.setPadding(dp(6), dp(6), dp(6), dp(6));
        dock.setElevation(dp(12));

        panelsBtn = icon(IconButton.Glyph.PANELS, "Panels", 15);
        panelsBtn.setOnClickListener(v -> togglePanelTray());
        micBtn = icon(IconButton.Glyph.MIC_OFF, "Microphone", 15);
        micBtn.setOnClickListener(v -> toggleMic());
        handoffBtn = icon(IconButton.Glyph.HANDOFF, "Hand off audio", 15);
        handoffBtn.setOnClickListener(v -> requestHandoff());
        chatBtn = icon(IconButton.Glyph.CHAT, "Chat", 15);
        chatBtn.setOnClickListener(v -> setToast("Chat is a desktop surface. Open the XORA workspace on the PC."));
        friendsBtn = icon(IconButton.Glyph.PEOPLE, "Friends", 15);
        friendsBtn.setOnClickListener(v -> setToast("Friends is a desktop surface. Open the XORA workspace on the PC."));
        syncBtn = icon(IconButton.Glyph.SYNC, "Sync transcript", 15);
        syncBtn.setOnClickListener(v -> syncTranscript());
        logBtn = icon(IconButton.Glyph.NOTE, "Transcript entries", 15);
        logBtn.setOnClickListener(v -> showLog());
        claimBtn = icon(IconButton.Glyph.HAND, "Take audio lease", 15);
        claimBtn.setOnClickListener(v -> claimAudio());
        releaseBtn = icon(IconButton.Glyph.POWER, "Release audio lease", 15);
        releaseBtn.setOnClickListener(v -> releaseAudio());
        hostBtn = icon(IconButton.Glyph.LOCK, "Change host or unpair", 15);
        hostBtn.setOnClickListener(v -> changeHostOrUnpair());

        for (IconButton b : dockOrder()) {
            dock.addView(b, dockCell());
        }

        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dockLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        dockLp.bottomMargin = dp(16);
        frame.addView(dock, dockLp);

        setContentView(frame);
        setPaired(false);
    }

    /** Dock order: panels, mic, handoff, chat, friends, then continuity. */
    private IconButton[] dockOrder() {
        return new IconButton[]{panelsBtn, micBtn, handoffBtn, chatBtn, friendsBtn,
                syncBtn, logBtn, claimBtn, releaseBtn, hostBtn};
    }

    private LinearLayout.LayoutParams dockCell() {
        int s = dp(32);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(s, s);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        return p;
    }

    /** Translucent rounded dock, macOS style: floating, no hard edge. */
    private GradientDrawable dockBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#d2101826"));
        g.setCornerRadius(dp(26));
        g.setStroke(dp(1), Color.parseColor("#4d61e8ff"));
        return g;
    }

    /**
     * The grouped panel tray. It mirrors the unified shell's own project list, so
     * the phone offers exactly the panels the desktop offers, grouped by purpose
     * rather than dumped flat.
     */
    private LinearLayout buildPanelTray() {
        LinearLayout tray = new LinearLayout(this);
        tray.setOrientation(LinearLayout.VERTICAL);
        tray.setVisibility(View.GONE);
        tray.setBackground(dockBackground());
        tray.setPadding(dp(12), dp(10), dp(12), dp(12));
        tray.setElevation(dp(11));

        tray.addView(groupHeader("Trading"));
        tray.addView(trayRow(new IconButton[]{
                trayIcon(IconButton.Glyph.CHART, "MultiHedge"),
                trayIcon(IconButton.Glyph.COIN, "Finance")}));

        tray.addView(groupHeader("Knowledge"));
        tray.addView(trayRow(new IconButton[]{
                trayIcon(IconButton.Glyph.BOOK, "Knowledge"),
                trayIcon(IconButton.Glyph.SPARK, "Skills")}));

        tray.addView(groupHeader("Work"));
        tray.addView(trayRow(new IconButton[]{
                trayIcon(IconButton.Glyph.TERMINAL, "Hermes"),
                trayIcon(IconButton.Glyph.GRID, "Projects")}));

        tray.addView(groupHeader("Creative"));
        tray.addView(trayRow(new IconButton[]{
                trayIcon(IconButton.Glyph.NOTE, "Lyric Council")}));

        tray.addView(groupHeader("Social"));
        tray.addView(trayRow(new IconButton[]{
                trayIcon(IconButton.Glyph.PEOPLE, "Friends"),
                trayIcon(IconButton.Glyph.MIC, "Voice & Devices")}));

        tray.addView(groupHeader("System"));
        tray.addView(trayRow(new IconButton[]{
                trayIcon(IconButton.Glyph.SHIELD, "Admin")}));

        return tray;
    }

    private TextView groupHeader(String text) {
        TextView t = label(text.toUpperCase(), 9, "#5b6b7d");
        t.setPadding(dp(2), dp(6), 0, dp(2));
        return t;
    }

    private LinearLayout trayRow(IconButton[] buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (IconButton b : buttons) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(36), dp(36));
            p.setMargins(dp(3), dp(2), dp(3), dp(2));
            row.addView(b, p);
        }
        return row;
    }

    /**
     * Panel buttons stay honest about scope: these surfaces live in the desktop
     * workspace, so tapping one says where to go rather than opening an empty
     * shell on the phone.
     */
    private IconButton trayIcon(IconButton.Glyph g, String name) {
        IconButton b = icon(g, name, 18);
        b.setOnClickListener(v -> setToast(name + " is a desktop surface. Open the XORA workspace on the PC."));
        return b;
    }

    private void togglePanelTray() {
        panelTray.setVisibility(panelTray.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        panelsBtn.setTint(panelTray.getVisibility() == View.VISIBLE
                ? IconButton.Tint.ACTIVE : IconButton.Tint.NORMAL);
    }

    private GradientDrawable card(String hex) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(hex));
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), Color.parseColor("#26374a"));
        return g;
    }

    private IconButton icon(IconButton.Glyph g, String label, int sizeDp) {
        IconButton b = new IconButton(this);
        b.setGlyph(g);
        b.setLabel(label);
        b.setTint(IconButton.Tint.NORMAL);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp * 2), dp(sizeDp * 2)));
        return b;
    }

    private void setPaired(boolean paired) {
        pairCard.setVisibility(paired ? View.GONE : View.VISIBLE);
        // The Orb is always visible: it is the app, and it doubles as the status
        // indicator. Only the controls and the tray depend on pairing.
        dock.setVisibility(paired ? View.VISIBLE : View.GONE);
        if (!paired) {
            panelTray.setVisibility(View.GONE);
        }
    }

    /**
     * Drop the stored enrolment and return to the pairing card.
     *
     * The host is intentionally KEPT so the user can correct a wrong port
     * without retyping it; only the token and device id are cleared, because
     * those are what make the app look "paired" while every call 401s.
     */
    private void changeHostOrUnpair() {
        stopPolling();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove(KEY_TOKEN)
                .remove(KEY_DEVICE_ID)
                .remove(KEY_CURSOR)
                .apply();
        client = null;
        setToast("Unpaired. Check the host, then pair again.");
        showPairing();
    }

    private void showPairing() {
        setPaired(false);
        statusView.setText("Not paired. Add your XORA host, paste the code, and pair.");
        leaseView.setText("");
    }

    private void enterDashboard(SharedPreferences p) {
        setPaired(true);
        String base = XoraClient.baseUrl(p.getString(KEY_HOST, DEFAULT_HOST));
        client = new XoraClient(base, p.getString(KEY_TOKEN, null),
                p.getString(KEY_DEVICE_ID, null), io);
        statusView.setText("Paired. Checking in...");

        micBtn.setOnClickListener(v -> toggleMic());
        handoffBtn.setOnClickListener(v -> requestHandoff());
        claimBtn.setOnClickListener(v -> claimAudio());
        releaseBtn.setOnClickListener(v -> releaseAudio());
        syncBtn.setOnClickListener(v -> syncTranscript());
        logBtn.setOnClickListener(v -> showLog());

        startPolling();
        refresh();
    }

    // ---- polling ---------------------------------------------------------

    private void startPolling() {
        stopPolling();
        poller = new Runnable() {
            @Override
            public void run() {
                pollLease();
                main.postDelayed(this, POLL_MS);
            }
        };
        main.postDelayed(poller, POLL_MS);
    }

    private void stopPolling() {
        if (poller != null) {
            main.removeCallbacks(poller);
            poller = null;
        }
    }

    /** Poll the lease so a handoff started on the PC shows up here. */
    private void pollLease() {
        if (client == null) {
            return;
        }
        io.execute(() -> {
            try {
                JSONObject s = client.audioState(MainActivity.this);
                String status = s.optString("status", "idle");
                boolean iAmOwner = client.deviceId().equals(s.optString("owner_device_id", ""));
                boolean iAmTarget = client.deviceId().equals(s.optString("target_device_id", ""));
                main.post(() -> renderLease(status, iAmOwner, iAmTarget));
            } catch (Exception e) {
                main.post(() -> {
                    statusView.setText("Cannot reach XORA: " + e.getMessage());
                });
            }
        });
    }

    private void renderLease(String status, boolean iAmOwner, boolean iAmTarget) {
        switch (status) {
            case "awaiting_ack":
                if (iAmTarget) {
                    // A transfer is addressed to this phone: offer the handoff.
                                leaseView.setText("Audio handoff offered. Tap the hand icon to take it.");
                    handoffBtn.setGlyph(IconButton.Glyph.HANDOFF);
                    handoffBtn.setTint(IconButton.Tint.PENDING);
                    handoffBtn.setLabel("Accept audio handoff");
                    handoffBtn.setOnClickListener(v -> ackHandoff());
                } else if (iAmOwner) {
                                leaseView.setText("Handing audio to another device. Waiting for it to accept.");
                } else {
                            leaseView.setText("Another device is handing off audio.");
                }
                break;
            case "owned":
                    leaseView.setText(iAmOwner ? "This phone owns the audio lease." : "Another device owns the audio lease.");
                break;
            default:
                        leaseView.setText(iAmOwner ? "Idle, but this phone still holds the lease."
                        : "No active audio lease.");
        }
        // Release only makes sense while this device holds it.
        releaseBtn.setTint(iAmOwner ? IconButton.Tint.ACTIVE : IconButton.Tint.MUTED);
        releaseBtn.setEnabled(iAmOwner);
        claimBtn.setEnabled(!iAmOwner);
        claimBtn.setTint(iAmOwner ? IconButton.Tint.MUTED : IconButton.Tint.NORMAL);
    }

    // ---- actions ---------------------------------------------------------

    private void refresh() {
        pollLease();
    }

    /** The phone is a handoff TARGET: acknowledge to take the lease. */
    private void ackHandoff() {
        busy("Accepting handoff...");
        io.execute(() -> {
            try {
                JSONObject r = client.ackTransfer(MainActivity.this);
                main.post(() -> {
                    String stop = r.optString("stop_source_device_id", "");
                    setToast("Audio taken. Stopped source: " + shortId(stop));
                    handoffBtn.setGlyph(IconButton.Glyph.HANDOFF_DONE);
                    handoffBtn.setTint(IconButton.Tint.ACTIVE);
                    handoffBtn.setLabel("Audio handoff complete");
                    pollLease();
                });
            } catch (Exception e) {
                main.post(() -> setToast("Handoff refused: " + e.getMessage()));
            }
        });
    }

    /** The phone is a handoff SOURCE: ask the other device to take over. */
    private void requestHandoff() {
        if (client == null) {
            return;
        }
        // transfer is session-only on the server, so the PC drives the outgoing
        // handoff. The phone instead shows the target list is not reachable and
        // tells the user plainly rather than pretending.
        setToast("Start outgoing handoff from the desktop: Voice & Devices > Request audio handoff.");
        pollLease();
    }

    private void claimAudio() {
        if (client == null) {
            return;
        }
        busy("Taking audio lease...");
        io.execute(() -> {
            try {
                int cursor = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_CURSOR, 0);
                JSONObject r = client.claimAudio(MainActivity.this, cursor);
                main.post(() -> {
                            setToast("This phone owns audio. Resume after cursor "
                            + r.optInt("resume_after", cursor) + ".");
                    pollLease();
                });
            } catch (Exception e) {
                main.post(() -> setToast("Could not take the lease: " + e.getMessage()));
            }
        });
    }

    private void releaseAudio() {
        if (client == null) {
            return;
        }
        busy("Releasing lease...");
        io.execute(() -> {
            try {
                client.releaseAudio(MainActivity.this);
                main.post(() -> {
                                handoffBtn.setGlyph(IconButton.Glyph.HANDOFF);
                    handoffBtn.setTint(IconButton.Tint.NORMAL);
                    handoffBtn.setLabel("Hand off audio");
                    handoffBtn.setOnClickListener(v -> requestHandoff());
                    setToast("Audio lease released.");
                    pollLease();
                });
            } catch (Exception e) {
                main.post(() -> setToast("Could not release: " + e.getMessage()));
            }
        });
    }

    private void syncTranscript() {
        if (client == null) {
            return;
        }
        busy("Syncing transcript...");
        io.execute(() -> {
            try {
                int cursor = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_CURSOR, 0);
                JSONObject o = client.transcript(MainActivity.this, cursor);
                int n = o.getJSONArray("items").length();
                int max = cursor;
                for (int i = 0; i < n; i++) {
                    JSONObject e = o.getJSONArray("items").getJSONObject(i);
                    max = Math.max(max, e.optInt("sequence", 0));
                }
                if (max > cursor) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_CURSOR, max).apply();
                }
                final int found = n;
                main.post(() -> setToast("Transcript synced: " + found + " new entries."));
            } catch (Exception e) {
                main.post(() -> setToast("Sync failed: " + e.getMessage()));
            }
        });
    }

    private void showLog() {
        if (client == null) {
            return;
        }
        io.execute(() -> {
            try {
                JSONObject o = client.transcript(MainActivity.this, 0);
                StringBuilder sb = new StringBuilder();
                org.json.JSONArray items = o.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject e = items.getJSONObject(i);
                    sb.append(e.optInt("sequence", 0)).append("  ")
                            .append(e.optString("role", "?")).append("  ")
                            .append(e.optString("text", "")).append('\n');
                }
                final String text = items.length() == 0 ? "Transcript is empty." : sb.toString();
                main.post(() -> setToast(text.length() > 300 ? text.substring(0, 300) + "..." : text));
            } catch (Exception e) {
                main.post(() -> setToast("Could not read transcript: " + e.getMessage()));
            }
        });
    }

    // ---- microphone ------------------------------------------------------

    private void toggleMic() {
        if (micOn) {
            stopMic();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startMic();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startMic();
            } else {
                setToast("Microphone permission denied.");
            }
        }
    }

    private void startMic() {
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(getCacheDir().getAbsolutePath() + "/scratch.3gp");
            recorder.prepare();
            recorder.start();
            micOn = true;
            micBtn.setGlyph(IconButton.Glyph.MIC);
            micBtn.setTint(IconButton.Tint.ACTIVE);
            micBtn.setLabel("Microphone on, tap to stop");
            setToast("Microphone live. Nothing is uploaded.");
        } catch (Exception e) {
            micOn = false;
            setToast("Could not start the mic: " + e.getMessage());
        }
    }

    private void stopMic() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Exception ignored) {
            // stop() throws if it was never started; the state is what matters.
        }
        recorder = null;
        micOn = false;
        micBtn.setGlyph(IconButton.Glyph.MIC_OFF);
        micBtn.setTint(IconButton.Tint.MUTED);
        micBtn.setLabel("Microphone");
        pollLease();
    }

    // ---- pairing ---------------------------------------------------------

    private void pairDevice() {
        final String host = hostInput.getText().toString().trim();
        final String code = codeInput.getText().toString().trim();
        if (host.isEmpty()) {
            setToast("Enter the XORA host first.");
            return;
        }
        if (code.isEmpty()) {
            setToast("Paste the pairing code from your PC first.");
            return;
        }
        pairButton.setEnabled(false);
        statusView.setText("Pairing...");
        final String base = XoraClient.baseUrl(host);
        io.execute(() -> {
            try {
                JSONObject paired = XoraClient.pair(MainActivity.this, base, code,
                        android.os.Build.MODEL);
                String token = paired.getString("device_token");
                String deviceId = paired.getJSONObject("device").getString("id");
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_HOST, host)
                        .putString(KEY_TOKEN, token)
                        .putString(KEY_DEVICE_ID, deviceId)
                        .putInt(KEY_CURSOR, 0)
                        .apply();
                main.post(() -> {
                    codeInput.setText("");
                    enterDashboard(getSharedPreferences(PREFS, MODE_PRIVATE));
                    setToast("Paired. Dashboard ready.");
                });
            } catch (Exception e) {
                main.post(() -> {
                    statusView.setText("Pairing failed: " + e.getMessage());
                    pairButton.setEnabled(true);
                });
            }
        });
    }

    // ---- plumbing --------------------------------------------------------

    private void busy(String what) {
        statusView.setText(what);
    }

    private void setToast(String s) {
        statusView.setText(s);
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String shortId(String id) {
        if (id == null || id.length() < 8) {
            return "none";
        }
        return id.substring(0, 8);
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        stopMic();
        io.shutdownNow();
        super.onDestroy();
    }
}
