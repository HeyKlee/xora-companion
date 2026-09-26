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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    private OrbView orb;
    private TextView statusView;
    private TextView leaseView;

    // pairing surface
    private LinearLayout pairCard;
    private EditText hostInput;
    private EditText codeInput;
    private Button pairButton;

    // dashboard surface
    private LinearLayout dash;
    private IconButton micBtn;
    private IconButton handoffBtn;
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

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#070b12"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        // ---- the Orb is the main focus: top of screen, dominant size ----
        orb = new OrbView(this);
        orb.setContentDescription("XORA Orb. Tap to refresh connection.");
        LinearLayout.LayoutParams orbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(250));
        root.addView(orb, orbLp);

        orb.setOnClickListener(v -> refresh());

        TextView title = label("XORA", 20, "#61e8ff");
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusView = label("", 12, "#8ea4bb");
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(6), 0, dp(2));
        root.addView(statusView);

        leaseView = label("", 12, "#fbbf24");
        leaseView.setGravity(Gravity.CENTER);
        root.addView(leaseView);

        // ---- pairing card ----
        pairCard = new LinearLayout(this);
        pairCard.setOrientation(LinearLayout.VERTICAL);
        pairCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        pairCard.setBackground(card("#111b28"));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(18);
        pairCard.setLayoutParams(cardLp);

        pairCard.addView(label("XORA host", 12, "#8ea4bb"));
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
        root.addView(pairCard);

        // ---- dashboard: icons only, no titles ----
        dash = new LinearLayout(this);
        dash.setOrientation(LinearLayout.VERTICAL);
        dash.setGravity(Gravity.CENTER);

        // Primary row: the two things a phone is actually for.
        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER);
        micBtn = icon(IconButton.Glyph.MIC_OFF, "Microphone", 30);
        handoffBtn = icon(IconButton.Glyph.HANDOFF, "Hand off audio", 30);
        row1.addView(micBtn, cell());
        row1.addView(handoffBtn, cell());
        dash.addView(row1);

        // Secondary row: continuity and the lease lifecycle.
        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        claimBtn = icon(IconButton.Glyph.HAND, "Take audio lease", 24);
        releaseBtn = icon(IconButton.Glyph.POWER, "Release audio lease", 24);
        row2.addView(claimBtn, cell());
        row2.addView(releaseBtn, cell());
        dash.addView(row2);

        // Tertiary row: read-only continuity.
        LinearLayout row3 = new LinearLayout(this);
        row3.setGravity(Gravity.CENTER);
        syncBtn = icon(IconButton.Glyph.SYNC, "Sync transcript", 24);
        logBtn = icon(IconButton.Glyph.CHAT, "Transcript entries", 24);
        row3.addView(syncBtn, cell());
        row3.addView(logBtn, cell());
        dash.addView(row3);

        // Quaternary row: escape hatch. Without this the app is a dead end when
        // it is pointed at the wrong host, because the pairing card is hidden
        // whenever a token exists and there is no way back to it.
        LinearLayout row4 = new LinearLayout(this);
        row4.setGravity(Gravity.CENTER);
        hostBtn = icon(IconButton.Glyph.CLOSE, "Change host or unpair", 24);
        hostBtn.setOnClickListener(v -> changeHostOrUnpair());
        row4.addView(hostBtn, cell());
        dash.addView(row4);

        LinearLayout.LayoutParams dashLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dashLp.topMargin = dp(20);
        dash.setLayoutParams(dashLp);
        root.addView(dash);

        setContentView(scroll);
        setPaired(false);
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
        b.setTint(IconButton.Tint.MUTED);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp * 2), dp(sizeDp * 2)));
        return b;
    }

    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(76), dp(76));
        p.setMargins(dp(8), dp(8), dp(8), dp(8));
        return p;
    }

    private void setPaired(boolean paired) {
        pairCard.setVisibility(paired ? View.GONE : View.VISIBLE);
        dash.setVisibility(paired ? View.VISIBLE : View.GONE);
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
        orb.setState(OrbView.State.IDLE);
        statusView.setText("Not paired. Add your XORA host, paste the code, and pair.");
        leaseView.setText("");
    }

    private void enterDashboard(SharedPreferences p) {
        setPaired(true);
        String base = XoraClient.baseUrl(p.getString(KEY_HOST, DEFAULT_HOST));
        client = new XoraClient(base, p.getString(KEY_TOKEN, null),
                p.getString(KEY_DEVICE_ID, null), io);
        orb.setState(OrbView.State.PENDING);
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
                    orb.setState(OrbView.State.ERROR);
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
                    orb.setState(OrbView.State.PENDING);
                    leaseView.setText("Audio handoff offered. Tap the hand icon to take it.");
                    handoffBtn.setGlyph(IconButton.Glyph.HANDOFF);
                    handoffBtn.setTint(IconButton.Tint.PENDING);
                    handoffBtn.setLabel("Accept audio handoff");
                    handoffBtn.setOnClickListener(v -> ackHandoff());
                } else if (iAmOwner) {
                    orb.setState(OrbView.State.PENDING);
                    leaseView.setText("Handing audio to another device. Waiting for it to accept.");
                } else {
                    orb.setState(OrbView.State.LIVE);
                    leaseView.setText("Another device is handing off audio.");
                }
                break;
            case "owned":
                orb.setState(OrbView.State.LIVE);
                leaseView.setText(iAmOwner ? "This phone owns the audio lease." : "Another device owns the audio lease.");
                break;
            default:
                orb.setState(OrbView.State.IDLE);
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
                    orb.setState(OrbView.State.LIVE);
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
                    orb.setState(OrbView.State.IDLE);
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
            orb.setState(OrbView.State.LIVE);
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
        orb.setState(OrbView.State.PENDING);
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
                    orb.setState(OrbView.State.ERROR);
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
