package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

/**
 * A card drawn in one pass: banner, or icon plus name, or an input name; focus ring on top.
 * A lifted tile (the top shelf layout) has no ring: focus raises it on a shadow with a light glare.
 */
final class Tile extends View {
    private static final float FOCUS_SCALE = 1.08f, LIFT_SCALE = 1.1f;
    private static final Interpolator SETTLE = new DecelerateInterpolator(1.6f);

    final Item item;
    final String kind;
    private final CharSequence title;
    private final int cardW, cardH, labelH;
    private final boolean labelAlways, labelOnFocus, lift;
    private final float radius, stroke;
    private final Paint glare = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Glass style, resting and focused; null for the other styles. */
    private final Glass.Card glassRest, glassLit;
    private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF logoBox = new RectF();
    private final RectF card = new RectF();
    private final Path clip = new Path();
    private final Path arrow = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint inner = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint under = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean moving;
    private final AfterglowTile afterglow;

    Tile(Context c, Item item, String kind, CharSequence title, int cardW, int cardH,
            String labels, int accent, boolean lift) {
        super(c);
        this.item = item;
        this.kind = kind;
        this.title = title;
        this.cardW = cardW;
        this.cardH = cardH;
        this.lift = lift;
        boolean app = Catalog.APPS.equals(kind);
        afterglow = app && "afterglow".equals(Config.get(c).get(Config.TILE_STYLE))
                ? new AfterglowTile(c, item.id, title, accent) : null;
        labelH = app && afterglow == null ? Ui.dp(c, lift ? 30 : 26) : 0;
        labelAlways = "always".equals(labels);
        labelOnFocus = "focus".equals(labels);
        radius = Ui.dp(c, lift ? 12 : 10);
        stroke = Ui.dp(c, afterglow == null ? 2.5f : 1f);
        boolean glass = lift && afterglow == null && Ui.glass(c);
        int tint = Ui.glassTint(c);
        int shadow = Ui.dp(c, 22);
        glassRest = glass ? new Glass.Card(radius, Ui.dp(c, 1.3f), false, tint, shadow) : null;
        glassLit = glass ? new Glass.Card(radius, Ui.dp(c, 1.7f), true, tint, shadow) : null;
        if (lift) {
            // The shadow follows the card only, not the label below it.
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, Tile.this.cardW, Tile.this.cardH, radius);
                }
            });
            glare.setShader(new LinearGradient(0, 0, 0, cardH * 0.7f,
                    0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        }

        fill.setColor(app ? Ui.SURFACE : 0xFF17181B);
        if (item.tintTop != 0 && glassRest == null) {
            fill.setShader(new LinearGradient(0, 0, 0, cardH, item.tintTop, item.tintBottom,
                    Shader.TileMode.CLAMP));
        }
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(stroke);
        ring.setColor(accent);
        dot.setColor(0xFF34C759);
        inner.setColor(Ui.TEXT);
        inner.setTypeface(Ui.MEDIUM);
        inner.setTextAlign(Paint.Align.CENTER);
        inner.setTextSize(Ui.dp(c, app ? 14 : 17));
        under.setColor(glass ? Ui.textOnWallpaper(c) : Ui.TEXT);
        under.setTypeface(Ui.MEDIUM);
        under.setTextAlign(Paint.Align.CENTER);
        under.setTextSize(Ui.dp(c, 13));

        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setContentDescription(title);
    }

    void setMoving(boolean on) {
        moving = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(cardW, cardH + labelH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        card.set(0, 0, cardW, cardH);
        clip.reset();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        if (afterglow != null) afterglow.resize(cardW, cardH);
        if (glassRest != null) {
            glassRest.size(cardW, cardH);
            glassLit.size(cardW, cardH);
        }
        setPivotX(cardW / 2f);
        setPivotY(cardH / 2f);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        animate().cancel();
        if (lift) {
            float s = focused ? LIFT_SCALE : 1f;
            // No shadow under glass: it would show through the card as a dark box.
            float z = focused && glassRest == null ? Ui.dp(getContext(), 16) : 0;
            animate().scaleX(s).scaleY(s).translationZ(z).setDuration(focused ? 170 : 130)
                    .setInterpolator(SETTLE).start();
        } else if (focused) {
            animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).setDuration(110).start();
        } else {
            setScaleX(1f);
            setScaleY(1f);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable banner = item.banner, icon = item.icon;
        Bitmap logo = item.logo;
        // Before the clip: the card's shadow lies outside its edge.
        if (glassRest != null) (isFocused() ? glassLit : glassRest).draw(canvas);
        canvas.save();
        canvas.clipPath(clip);
        if (glassRest == null) canvas.drawRect(card, fill);
        if (glassRest != null && logo != null) {
            fitLogo(logo);
            canvas.drawBitmap(logo, null, logoBox, logoPaint);
        } else if (afterglow != null) {
            afterglow.draw(canvas, icon, cardW, cardH);
        } else if (banner != null && glassRest == null) {
            banner.setBounds(0, 0, cardW, cardH);
            banner.draw(canvas);
        } else if (icon != null && banner == null) {
            int size = cardH * 46 / 100;
            int left = (cardW - size) / 2, top = cardH * 12 / 100;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
            drawLine(canvas, inner, cardW / 2f, cardH - cardH * 13 / 100f);
        } else if (!Catalog.APPS.equals(kind)) {
            drawLine(canvas, inner, cardW / 2f, cardH / 2f - (inner.ascent() + inner.descent()) / 2);
        }
        if (lift && glassRest == null && isFocused() && !moving) canvas.drawRect(card, glare);
        if (moving) canvas.drawColor(0x99000000);
        canvas.restore();

        if (afterglow != null) afterglow.edge(canvas, cardW, cardH, radius);

        if (item.state == TvInputManager.INPUT_STATE_CONNECTED) {
            float r = Ui.dp(getContext(), 3.5f);
            canvas.drawCircle(cardW - r * 3, r * 3, r, dot);
        }
        if (moving || isFocused() && !lift) {
            float half = stroke / 2;
            ring.setPathEffect(moving ? new DashPathEffect(new float[] {stroke * 3, stroke * 2}, 0) : null);
            canvas.drawRoundRect(half, half, cardW - half, cardH - half, radius - half, radius - half, ring);
        }
        if (moving) drawArrows(canvas);
        boolean label = (banner != null || glassRest != null && logo != null)
                && (labelAlways || labelOnFocus && isFocused());
        if (labelH > 0 && label) {
            drawLine(canvas, under, cardW / 2f, cardH + labelH - Ui.dp(getContext(), 6));
        }
    }

    /** Sets logoBox to {@code logo}'s shape, centered: nearly the full height for a square glyph. */
    private void fitLogo(Bitmap logo) {
        float w = logo.getWidth(), h = logo.getHeight();
        boolean square = w <= h * 1.1f;
        float room = square ? cardH * 0.94f : cardH * 0.80f, k = room / h;
        float dw = Math.min(w * k, cardW * 0.86f), dh = h * dw / w;
        float x = (cardW - dw) / 2, y = (cardH - dh) / 2;
        logoBox.set(x, y, x + dw, y + dh);
    }

    private void drawLine(Canvas canvas, TextPaint paint, float x, float baseline) {
        float room = cardW - Ui.dp(getContext(), 16);
        CharSequence s = TextUtils.ellipsize(title, paint, room, TextUtils.TruncateAt.END);
        canvas.drawText(s, 0, s.length(), x, baseline, paint);
    }

    private void drawArrows(Canvas canvas) {
        float h = cardH / 6f, w = h * 0.7f, mid = cardH / 2f, pad = h * 0.8f;
        inner.setStyle(Paint.Style.FILL);
        arrow.reset();
        arrow.moveTo(pad, mid);
        arrow.lineTo(pad + w, mid - h / 2);
        arrow.lineTo(pad + w, mid + h / 2);
        arrow.close();
        arrow.moveTo(cardW - pad, mid);
        arrow.lineTo(cardW - pad - w, mid - h / 2);
        arrow.lineTo(cardW - pad - w, mid + h / 2);
        arrow.close();
        canvas.drawPath(arrow, inner);
    }
}
