package tv.dumbtv.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

import java.io.File;

final class Ui {
    static final int TEXT = 0xFFF2F2F3;
    static final int MUTED = 0x99FFFFFF;
    static final int FAINT = 0x66FFFFFF;
    static final int SURFACE = 0xFF1C1D21;
    private static final int PLUM = 0xFF5A2A42;
    static final int DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static Typeface serif;

    private Ui() {}

    /** Tinos, a serif drawn to Times New Roman's measures; a Latin subset ships in the APK. */
    static Typeface serif(Context c) {
        if (serif == null) {
            try {
                serif = Typeface.createFromAsset(c.getAssets(), "fonts/Tinos-subset.ttf");
            } catch (RuntimeException e) {
                serif = Typeface.SERIF;
            }
        }
        return serif;
    }

    static boolean glass(Context c) {
        return "glass".equals(Config.get(c).get(Config.TILE_STYLE));
    }

    /** The color of smoked glass, or zero for clear glass. */
    static int glassTint(Context c) {
        return glass(c) && "smoked".equals(Config.get(c).get(Config.GLASS_TONE)) ? PLUM : 0;
    }

    /** Text that sits directly on the wallpaper: dark on the light ones smoked glass goes with. */
    static int textOnWallpaper(Context c) {
        return glassTint(c) != 0 ? PLUM : TEXT;
    }

    /**
     * What a settings page sits on: in the glass style the wallpaper, darkened for the text, and
     * otherwise, or with no wallpaper, the plain color.
     */
    static Drawable page(Context c) {
        File file = glass(c) ? Config.get(c).wallpaper() : null;
        if (file != null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
            if (b != null) {
                BitmapDrawable d = new BitmapDrawable(c.getResources(), b);
                d.setFilterBitmap(true);
                d.setColorFilter(glassTint(c) != 0 ? 0xFF4E4247 : 0xFF9A9A9A, PorterDuff.Mode.MULTIPLY);
                return d;
            }
        }
        return new ColorDrawable(0xFF0C0C0E);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** {@code german} on a TV set to German, {@code english} everywhere else. */
    static String t(Context c, String english, String german) {
        return "de".equals(c.getResources().getConfiguration().getLocales().get(0).getLanguage())
                ? german : english;
    }

    /** Black on light accents, white on dark ones. */
    static int ink(int color) {
        return Color.luminance(color) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Accent fill when focused, a faint fill otherwise. */
    static void focusStyle(TextView v, int accent, int idle, float radius) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, round(accent, radius));
        bg.addState(new int[0], round(idle, radius));
        v.setBackground(bg);
        v.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {ink(accent), 0xD9FFFFFF}));
    }
}
