package nz.kelly.xora.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * An icon-only control: a circular glass button that draws its own glyph.
 *
 * Kelly asked for the mobile build to be icons instead of titles, so this is the
 * primary control. The label is kept ONLY as contentDescription, which is what a
 * screen reader announces, so dropping the visible text costs no accessibility.
 *
 * Glyphs are drawn with Path primitives rather than shipped as bitmaps: no
 * density buckets to get wrong, and they stay crisp on any panel size.
 */
public class IconButton extends View {

    public enum Glyph { MIC, MIC_OFF, HANDOFF, HANDOFF_DONE, SYNC, CHAT, POWER, LOCK, HAND, CLOSE }

    private static final int BG = Color.parseColor("#111b28");
    private static final int RING = Color.parseColor("#61e8ff");

    public enum Tint { NORMAL, ACTIVE, PENDING, ERROR, MUTED }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    private Glyph glyphId = Glyph.SYNC;
    private Tint tint = Tint.NORMAL;
    private String label = "";
    private boolean pressed;
    private float downX, downY;
    private int accent = RING;

    public IconButton(Context context) {
        super(context);
        init();
    }

    private void init() {
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        glyph.setStyle(Paint.Style.FILL);
        setClickable(true);
        setFocusable(true);
    }

    public void setGlyph(Glyph g) {
        this.glyphId = g;
        invalidate();
    }

    public void setTint(Tint t) {
        this.tint = t;
        invalidate();
    }

    public void setAccent(int color) {
        this.accent = color;
        invalidate();
    }

    /** Always set this: it is the only accessible name the control has. */
    public void setLabel(String s) {
        this.label = s;
        setContentDescription(s);
    }

    public String label() {
        return label;
    }

    private int tintColor() {
        switch (tint) {
            case ACTIVE: return RING;
            case PENDING: return Color.parseColor("#fbbf24");
            case ERROR: return Color.parseColor("#f87171");
            case MUTED: return Color.parseColor("#5b6b7d");
            default: return Color.parseColor("#cfe4f5");
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                pressed = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                boolean wasPressed = pressed;
                pressed = false;
                invalidate();
                // Only fire when released inside, so a drag-off cancels cleanly.
                if (wasPressed && Math.hypot(e.getX() - downX, e.getY() - downY) < 24f
                        && performClick()) {
                    return true;
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float radius = Math.min(getWidth(), getHeight()) / 2f;
        final int c = tintColor();

        // Translucent glass disc.
        fill.setColor(pressed ? withAlpha(c, 46) : withAlpha(BG, 210));
        canvas.drawCircle(cx, cy, radius - 2f, fill);

        // Status ring, matching the Orb and the panel dots.
        stroke.setStrokeWidth(radius * 0.075f);
        stroke.setColor(withAlpha(tint == Tint.NORMAL ? Color.parseColor("#2b3a4d") : c,
                tint == Tint.NORMAL ? 220 : 235));
        canvas.drawCircle(cx, cy, radius - stroke.getStrokeWidth() / 2f, stroke);

        drawGlyph(canvas, cx, cy, radius, c);
    }

    /** All glyphs are drawn inside a unit box scaled to the button. */
    private void drawGlyph(Canvas canvas, float cx, float cy, float radius, int c) {
        final float s = radius * 0.52f;
        glyph.setColor(c);
        stroke.setColor(c);
        stroke.setStrokeWidth(Math.max(2f, radius * 0.11f));
        path.reset();

        switch (glyphId) {
            case MIC: {
                r.set(cx - s * 0.34f, cy - s * 0.72f, cx + s * 0.34f, cy + s * 0.16f);
                path.addRoundRect(r, s * 0.34f, s * 0.34f, Path.Direction.CW);
                canvas.drawPath(path, glyph);
                path.reset();
                path.moveTo(cx - s * 0.72f, cy + s * 0.02f);
                path.quadTo(cx, cy + s * 0.82f, cx + s * 0.72f, cy + s * 0.02f);
                canvas.drawPath(path, stroke);
                canvas.drawLine(cx, cy + s * 0.78f, cx, cy + s * 1.0f, stroke);
                break;
            }
            case MIC_OFF: {
                r.set(cx - s * 0.34f, cy - s * 0.72f, cx + s * 0.34f, cy + s * 0.16f);
                path.addRoundRect(r, s * 0.34f, s * 0.34f, Path.Direction.CW);
                canvas.drawPath(path, glyph);
                path.reset();
                path.moveTo(cx - s * 0.72f, cy + s * 0.02f);
                path.quadTo(cx, cy + s * 0.82f, cx + s * 0.72f, cy + s * 0.02f);
                canvas.drawPath(path, stroke);
                // The slash is what distinguishes it from MIC.
                canvas.drawLine(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f, stroke);
                break;
            }
            case HANDOFF: {
                // Two arrows swapping direction: audio moving to another device.
                canvas.drawLine(cx - s * 0.9f, cy - s * 0.34f, cx + s * 0.5f, cy - s * 0.34f, stroke);
                path.reset();
                path.moveTo(cx + s * 0.22f, cy - s * 0.66f);
                path.lineTo(cx + s * 0.74f, cy - s * 0.34f);
                path.lineTo(cx + s * 0.22f, cy - s * 0.02f);
                canvas.drawPath(path, stroke);
                canvas.drawLine(cx + s * 0.9f, cy + s * 0.34f, cx - s * 0.5f, cy + s * 0.34f, stroke);
                path.reset();
                path.moveTo(cx - s * 0.22f, cy + s * 0.02f);
                path.lineTo(cx - s * 0.74f, cy + s * 0.34f);
                path.lineTo(cx - s * 0.22f, cy + s * 0.66f);
                canvas.drawPath(path, stroke);
                break;
            }
            case HANDOFF_DONE: {
                canvas.drawCircle(cx, cy, s * 0.86f, stroke);
                path.reset();
                path.moveTo(cx - s * 0.42f, cy + s * 0.02f);
                path.lineTo(cx - s * 0.08f, cy + s * 0.38f);
                path.lineTo(cx + s * 0.46f, cy - s * 0.34f);
                canvas.drawPath(path, stroke);
                break;
            }
            case SYNC: {
                // Circular arrow: a fetch, not a download.
                r.set(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f);
                canvas.drawArc(r, 40f, 260f, false, stroke);
                path.reset();
                path.moveTo(cx + s * 0.44f, cy - s * 0.94f);
                path.lineTo(cx + s * 0.86f, cy - s * 0.5f);
                path.lineTo(cx + s * 0.28f, cy - s * 0.34f);
                canvas.drawPath(path, glyph);
                break;
            }
            case CHAT: {
                r.set(cx - s * 0.86f, cy - s * 0.7f, cx + s * 0.86f, cy + s * 0.4f);
                path.addRoundRect(r, s * 0.34f, s * 0.34f, Path.Direction.CW);
                canvas.drawPath(path, stroke);
                path.reset();
                path.moveTo(cx - s * 0.34f, cy + s * 0.38f);
                path.lineTo(cx - s * 0.5f, cy + s * 0.92f);
                path.lineTo(cx + s * 0.06f, cy + s * 0.4f);
                canvas.drawPath(path, glyph);
                break;
            }
            case POWER: {
                r.set(cx - s * 0.68f, cy - s * 0.68f, cx + s * 0.68f, cy + s * 0.68f);
                canvas.drawArc(r, 60f, 240f, false, stroke);
                canvas.drawLine(cx, cy - s * 0.94f, cx, cy - s * 0.1f, stroke);
                break;
            }
            case LOCK: {
                r.set(cx - s * 0.72f, cy - s * 0.1f, cx + s * 0.72f, cy + s * 0.86f);
                path.addRoundRect(r, s * 0.2f, s * 0.2f, Path.Direction.CW);
                canvas.drawPath(path, glyph);
                path.reset();
                r.set(cx - s * 0.36f, cy - s * 0.94f, cx + s * 0.36f, cy - s * 0.3f);
                path.addArc(r, 180f, -180f);
                canvas.drawPath(path, stroke);
                break;
            }
            case HAND: {
                // Open palm: take the audio.
                path.moveTo(cx - s * 0.6f, cy + s * 0.7f);
                path.lineTo(cx - s * 0.6f, cy - s * 0.3f);
                canvas.drawLine(cx - s * 0.6f, cy - s * 0.3f, cx - s * 0.6f, cy - s * 0.62f, stroke);
                canvas.drawLine(cx - s * 0.2f, cy - s * 0.7f, cx - s * 0.2f, cy - s * 0.3f, stroke);
                canvas.drawLine(cx + s * 0.2f, cy - s * 0.7f, cx + s * 0.2f, cy - s * 0.3f, stroke);
                canvas.drawLine(cx + s * 0.6f, cy - s * 0.5f, cx + s * 0.6f, cy - s * 0.3f, stroke);
                r.set(cx - s * 0.62f, cy - s * 0.3f, cx + s * 0.62f, cy + s * 0.72f);
                path.addRoundRect(r, s * 0.34f, s * 0.34f, Path.Direction.CW);
                canvas.drawPath(path, stroke);
                break;
            }
            case CLOSE: {
                canvas.drawLine(cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.6f, stroke);
                canvas.drawLine(cx + s * 0.6f, cy - s * 0.6f, cx - s * 0.6f, cy + s * 0.6f, stroke);
                break;
            }
            default:
                canvas.drawCircle(cx, cy, s * 0.5f, glyph);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
