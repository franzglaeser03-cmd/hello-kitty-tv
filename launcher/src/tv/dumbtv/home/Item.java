package tv.dumbtv.home;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.Drawable;

/** One tile: an installed app (id = package name) or a TV input (id = input id). */
final class Item {
    final String id;
    final CharSequence label;
    final Intent intent;
    ActivityInfo info;
    boolean leanback;
    int state = -1;
    volatile boolean artRequested;
    volatile Drawable banner, icon;
    volatile int backgroundColor;
    volatile boolean colorRequested;
    /** One of the launcher's own screens: started plainly, in the home screen's task. */
    boolean own;
    /** For the launcher's own tiles: the card's colors, top to bottom. Zero: an ordinary card. */
    int tintTop, tintBottom;

    Item(String id, CharSequence label, Intent intent) {
        this.id = id;
        this.label = label;
        this.intent = intent;
    }
}
