package nz.kelly.xora.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The XORA Orb, drawn natively.
 *
 * Kelly's rule from the desktop build carries over: the Orb has NO rings. Only
 * the core, its synapsed glow, and rotating dot rings with a bright comet head.
 * There is no hoop, no TorusGeometry, no solid glowing ring.
 *
 * State drives colour, the same semantics as the panel status dots:
 *   idle/unpaired grey-blue, live cyan, pending amber, error red.
 */
public class OrbView extends View {

    /** Must match the shell's status semantics. */
    public enum State { IDLE, LIVE, PENDING, ERROR }

    private static final int DOT_RINGS = 3;
    private static final int DOTS_PER_RING = 6;

    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coreRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private State state = State.IDLE;
    private float spin = 0f;
    private float breath = 0f;
    private long lastFrame = 0L;
    private boolean running = true;

    private int cyan = Color.parseColor("#61e8ff");
    private int violet = Color.parseColor("#9c8cff");
    private int amber = Color.parseColor("#fbbf24");
    private int red = Color.parseColor("#f87171");
    private int idle = Color.parseColor("#5b6b7d");

    public OrbView(Context context) {
        super(context);
        init();
    }

    public OrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        coreRimPaint.setStyle(Paint.Style.STROKE);
        coreRimPaint.setStrokeWidth(2f);
    }

    public void setState(State s) {
        if (this.state != s) {
            this.state = s;
            invalidate();
        }
    }

    public State getState() {
        return state;
    }

    @Override
    protected void onDetachedFromWindow() {
        // A view that keeps invalidating after detach leaks the activity.
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        running = true;
        lastFrame = 0L;
        super.onAttachedToWindow();
    }

    private int accent() {
        switch (state) {
            case LIVE: return cyan;
            case PENDING: return amber;
            case ERROR: return red;
            default: return idle;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.nanoTime();
        if (lastFrame != 0L) {
            // Cap dt so a dropped frame or a returning-from-background does not
            // teleport the dots forward by seconds.
            float dt = Math.min((now - lastFrame) / 1_000_000_000f, 0.05f);
            spin += dt * 26f;
            breath += dt;
        }
        lastFrame = now;
        if (running) {
            postInvalidateOnAnimation();
        }

        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float unit = Math.min(getWidth(), getHeight());
        final float breathe = 1f + 0.04f * (float) Math.sin(breath * 1.9f);
        final float coreR = unit * 0.17f * breathe;

        // Soft outer glow, drawn first and wide.
        glowPaint.setShader(new RadialGradient(
                cx, cy, coreR * 2.9f,
                new int[]{withAlpha(accent(), 110), withAlpha(accent(), 40), withAlpha(accent(), 0)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, coreR * 2.9f, glowPaint);

        // The core itself, cyan into violet.
        corePaint.setShader(new RadialGradient(
                cx, cy, coreR,
                new int[]{Color.WHITE, cyan, violet},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, coreR, corePaint);

        // A crisp rim keeps the core from bleeding into the glow on OLED.
        coreRimPaint.setColor(withAlpha(Color.WHITE, 150));
        canvas.drawCircle(cx, cy, coreR, coreRimPaint);

        // Dot rings. No hoops, no ring lines: only dots plus one comet head each.
        for (int r = 0; r < DOT_RINGS; r++) {
            final float radius = coreR * (1.75f + 0.62f * r);
            // Inner rings turn faster, like the desktop build.
            final float dir = (r % 2 == 0) ? 1f : -1f;
            final float ringSpin = spin * dir * (1f - 0.28f * r);
            for (int d = 0; d < DOTS_PER_RING; d++) {
                final double ang = Math.toRadians(d * (360.0 / DOTS_PER_RING) + ringSpin);
                final float x = cx + (float) (Math.cos(ang) * radius);
                final float y = cy + (float) (Math.sin(ang) * radius);
                final boolean isHead = d == 0;
                dotPaint.setColor(withAlpha(isHead ? Color.WHITE : accent(), isHead ? 235 : 150));
                canvas.drawCircle(x, y, isHead ? coreR * 0.13f : coreR * 0.075f, dotPaint);
                if (isHead) {
                    // Comet trail behind the head, fading out along the ring.
                    headPaint.setShader(new LinearGradient(
                            x, y, cx, cy,
                            new int[]{withAlpha(accent(), 130), withAlpha(accent(), 0)},
                            null, Shader.TileMode.CLAMP));
                    canvas.drawCircle(x, y, coreR * 0.26f, headPaint);
                }
            }
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
