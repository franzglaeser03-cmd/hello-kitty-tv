package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;

import java.util.HashMap;
import java.util.Map;

/**
 * The clear glass look, in the spirit of iOS 26: a card you can see the wallpaper through, with
 * thickness. It has a shadow beneath it, light along its top, shade along its bottom, a sheen across
 * it, and a bright rim where light catches the edge; the app's logo floats on it.
 *
 * Nothing blurs live: this chip has no GPU time to spare for that, and the wallpapers are soft
 * already. Each card is drawn once into a bitmap, shared by every tile of its size and state, so
 * a whole screen of them costs a few textured quads a frame.
 */
final class Glass {
    private static final int LOGO_W = 480, LOGO_H = 270, EDGE = 8;
    private static final Map<String, Bitmap> CARDS = new HashMap<>();

    private Glass() {}

    /** Draws one glass card, reaching {@code margin} past its bounds for the shadow. */
    static final class Card {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final float radius, stroke;
        private final boolean lit;
        private final int tint, margin;
        private Bitmap art;

        /** {@code tint}: the color of smoked glass, for light wallpapers; zero for clear glass. */
        Card(float radius, float stroke, boolean lit, int tint, int margin) {
            this.radius = radius;
            this.stroke = stroke;
            this.lit = lit;
            this.tint = tint;
            this.margin = margin;
        }

        void size(int w, int h) {
            art = w > 0 && h > 0 ? bitmap(w, h, radius, stroke, lit, tint, margin) : null;
        }

        void draw(Canvas c) {
            if (art != null) c.drawBitmap(art, -margin, -margin, paint);
        }
    }

    private static Bitmap bitmap(int w, int h, float radius, float stroke, boolean lit, int tint, int m) {
        String key = w + "x" + h + "/" + radius + "/" + stroke + "/" + lit + "/" + tint + "/" + m;
        Bitmap b = CARDS.get(key);
        if (b != null) return b;
        b = Bitmap.createBitmap(w + 2 * m, h + 2 * m, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(m, m);
        RectF box = new RectF(0, 0, w, h);
        if (m > 0) shadow(c, box, radius, lit, m);
        Path shape = new Path();
        shape.addRoundRect(box, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(shape);
        body(c, w, h, lit, tint);
        c.restore();
        rim(c, w, h, radius, stroke, lit);
        CARDS.put(key, b);
        return b;
    }

    /** Falls below the card and stops at its edge: under glass it would show as a dark box. */
    private static void shadow(Canvas c, RectF box, float radius, boolean lit, int m) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(lit ? 0x8C2A0F1E : 0x592A0F1E);
        p.setMaskFilter(new BlurMaskFilter(m * (lit ? 0.62f : 0.42f), BlurMaskFilter.Blur.NORMAL));
        RectF below = new RectF(box);
        below.offset(0, m * (lit ? 0.40f : 0.22f));
        c.drawRoundRect(below, radius, radius, p);
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawRoundRect(box, radius, radius, clear);
    }

    private static void body(Canvas c, int w, int h, boolean lit, int tint) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int rgb = tint & 0xFFFFFF;
        p.setShader(new LinearGradient(0, 0, 0, h,
                tint == 0 ? (lit ? 0x57FFFFFF : 0x30FFFFFF) : (lit ? 0xCC : 0xA8) << 24 | rgb,
                tint == 0 ? (lit ? 0x2BFFFFFF : 0x12FFFFFF) : (lit ? 0xB0 : 0x8C) << 24 | rgb,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // Light pooled along the top, as if the card were thick and domed.
        p.setShader(new LinearGradient(0, 0, 0, h * 0.5f,
                lit ? 0x66FFFFFF : 0x42FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.5f, p);
        // The far edge of the glass, in shade.
        p.setShader(new LinearGradient(0, h * 0.62f, 0, h,
                0x00000000, lit ? 0x4A1A0512 : 0x5C1A0512, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.62f, w, h, p);
        // A band of reflected light across the corner.
        p.setShader(new LinearGradient(0, 0, w, h * 1.4f,
                new int[] {0x00FFFFFF, lit ? 0x38FFFFFF : 0x22FFFFFF, 0x00FFFFFF},
                new float[] {0.12f, 0.27f, 0.42f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    /** A bright outer edge, lit from the top left, with a thin dark line inside it for thickness. */
    private static void rim(Canvas c, int w, int h, float radius, float stroke, boolean lit) {
        float half = stroke / 2;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setShader(new LinearGradient(0, 0, w, h,
                new int[] {lit ? 0xFFFFFFFF : 0xE6FFFFFF, 0x33FFFFFF, lit ? 0xCCFFFFFF : 0x8CFFFFFF},
                new float[] {0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(half, half, w - half, h - half), radius - half, radius - half, p);
        float in = stroke + stroke / 2;
        p.setShader(new LinearGradient(0, 0, 0, h, 0x00000000, lit ? 0x38000000 : 0x2E000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(in, in, w - in, h - in), radius - in, radius - in, p);
    }

    private static final class CardDrawable extends Drawable {
        private final Card card;

        CardDrawable(float radius, float stroke, boolean lit, int tint, int margin) {
            card = new Card(radius, stroke, lit, tint, margin);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            card.size(b.width(), b.height());
        }

        @Override
        public void draw(Canvas c) {
            int at = c.save();
            c.translate(getBounds().left, getBounds().top);
            card.draw(c);
            c.restoreToCount(at);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter filter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** A glass background that does not change with focus. */
    static Drawable plain(float radius, float stroke, int tint, int margin) {
        return new CardDrawable(radius, stroke, false, tint, margin);
    }

    /** A glass background that brightens when focused. */
    static Drawable focusable(float radius, float stroke, int tint, int margin) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_focused}, new CardDrawable(radius, stroke, true, tint, margin));
        d.addState(new int[0], new CardDrawable(radius, stroke, false, tint, margin));
        return d;
    }

    /**
     * {@code art}'s logo with its background cut away. The background is read along the left and
     * right edges, row by row, and blended across, so a banner that fades from one color to another
     * still comes off clean. What is left keeps its own colors, except that dark parts turn light,
     * because black lettering would vanish on glass; with {@code white} everything turns white, as
     * iOS's clear icons do.
     */
    static Bitmap logo(Drawable art, boolean white) {
        int iw = art.getIntrinsicWidth(), ih = art.getIntrinsicHeight();
        int w = iw > 0 && Math.abs(iw - ih) < iw / 8 ? LOGO_H : LOGO_W, h = LOGO_H;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Drawable.ConstantState state = art.getConstantState();
        Drawable copy = state != null ? state.newDrawable().mutate() : art;
        copy.setBounds(0, 0, w, h);
        copy.draw(c);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        // Read first: the loop below overwrites the rows it has passed.
        int[][] lefts = new int[h][], rights = new int[h][];
        for (int y = 0; y < h; y++) {
            lefts[y] = edge(px, w, h, 0, y);
            rights[y] = edge(px, w, h, w - EDGE, y);
        }
        for (int y = 0, i = 0; y < h; y++) {
            int[] left = lefts[y], right = rights[y];
            for (int x = 0; x < w; x++, i++) {
                float fx = x / (float) (w - 1);
                float bgR = left[0] + (right[0] - left[0]) * fx;
                float bgG = left[1] + (right[1] - left[1]) * fx;
                float bgB = left[2] + (right[2] - left[2]) * fx;
                int p = px[i];
                int srcA = p >>> 24;
                int r = p >> 16 & 0xFF, g = p >> 8 & 0xFF, bl8 = p & 0xFF;
                float diff = Math.max(Math.abs(r - bgR), Math.max(Math.abs(g - bgG), Math.abs(bl8 - bgB))) / 255f;
                float a = (diff - 0.09f) / 0.20f;
                a = a < 0 ? 0 : a > 1 ? 1 : a;
                a = a * a * (3 - 2 * a) * srcA / 255f;
                if (a <= 0) {
                    px[i] = 0;
                    continue;
                }
                // Takes the background back out of soft edges, or they keep a fringe of its color.
                float solid = Math.min(1f, a);
                float nr = clamp(bgR + (r - bgR) / solid), ng = clamp(bgG + (g - bgG) / solid);
                float nb = clamp(bgB + (bl8 - bgB) / solid);
                float luma = (0.299f * nr + 0.587f * ng + 0.114f * nb) / 255f;
                float toWhite = white ? 1f : luma < 0.45f && chroma(nr, ng, nb) < 0.25f ? 1f - luma / 0.45f * 0.2f : 0f;
                nr += (255 - nr) * toWhite;
                ng += (255 - ng) * toWhite;
                nb += (255 - nb) * toWhite;
                px[i] = (int) (a * 255) << 24 | (int) nr << 16 | (int) ng << 8 | (int) nb;
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    /** Average color of a patch of the edge around row {@code y}; see-through counts as dark. */
    private static int[] edge(int[] px, int w, int h, int left, int y) {
        int r = 0, g = 0, b = 0, n = 0;
        int from = Math.max(0, y - 3), to = Math.min(h - 1, y + 3);
        for (int row = from; row <= to; row++) {
            for (int x = left; x < left + EDGE; x++) {
                int p = px[row * w + x];
                int a = p >>> 24;
                r += (p >> 16 & 0xFF) * a / 255;
                g += (p >> 8 & 0xFF) * a / 255;
                b += (p & 0xFF) * a / 255;
                n++;
            }
        }
        return new int[] {r / n, g / n, b / n};
    }

    private static float chroma(float r, float g, float b) {
        return (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b))) / 255f;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }
}
