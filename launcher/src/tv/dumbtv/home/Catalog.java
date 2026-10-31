package tv.dumbtv.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.text.TextUtils;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Where tiles come from, and which of them the user wants on the home screen. */
final class Catalog {
    static final String APPS = "apps", INPUTS = "inputs";
    static final String CAST_ID = "dumbtv.cast", LIVE_TV_ID = "dumbtv.livetv", WIFI_ID = "dumbtv.wifi";
    private static final String LIVE_TV = "com.amazon.tv.livetv";
    private static final String SETTINGS = "com.amazon.tv.settings.v2";
    private static final String CHANNELS = "vnd.android.cursor.dir/channel";

    private Catalog() {}

    /** Every launchable app, TV apps first; drawables are left for {@link #art}. */
    static List<Item> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Item> found = new LinkedHashMap<>();
        collect(pm, found, Intent.CATEGORY_LEANBACK_LAUNCHER, true, c.getPackageName());
        collect(pm, found, Intent.CATEGORY_LAUNCHER, false, c.getPackageName());
        List<Item> out = new ArrayList<>(found.values());
        Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        builtIn(c, pm, out);
        return out;
    }

    /**
     * Three tiles no launcher entry exists for: the TV tuner, which is not a passthrough input and
     * has no app icon on Fire OS, the screen that gathers the ways to cast from a phone, and the
     * Wi-Fi settings, which are otherwise a few menus deep.
     */
    private static void builtIn(Context c, PackageManager pm, List<Item> out) {
        Intent tuner = new Intent(Intent.ACTION_VIEW).setType(CHANNELS)
                .setClassName(LIVE_TV, LIVE_TV + ".TvChannelsPlayerActivityAlias");
        if (tuner.resolveActivity(pm) != null) {
            Item it = new Item(LIVE_TV_ID, Ui.t(c, "Live TV", "Kabel-TV"), tuner);
            it.leanback = true;
            it.icon = c.getDrawable(R.drawable.tile_tv);
            it.artRequested = true;
            it.tintTop = 0xFF3B82D6;
            it.tintBottom = 0xFF1B3A73;
            if (Ui.glass(c)) it.logo = iconFile(Config.get(c), it.id);
            out.add(it);
        }
        Item cast = new Item(CAST_ID, Ui.t(c, "Cast", "Vom Handy"), new Intent(c, Cast.class));
        cast.leanback = true;
        cast.own = true;
        cast.icon = c.getDrawable(R.drawable.tile_cast);
        cast.artRequested = true;
        cast.tintTop = 0xFF8A5FD1;
        cast.tintBottom = 0xFF3D2A73;
        if (Ui.glass(c)) cast.logo = iconFile(Config.get(c), cast.id);
        out.add(cast);
        // Fire OS's own network screen when it is there, or whatever answers the standard action.
        Intent wifi = new Intent().setClassName(SETTINGS, SETTINGS + ".tv.network.NetworkActivity");
        if (wifi.resolveActivity(pm) == null) wifi = new Intent(Settings.ACTION_WIFI_SETTINGS);
        if (wifi.resolveActivity(pm) != null) {
            Item it = new Item(WIFI_ID, Ui.t(c, "Wi-Fi", "WLAN"), wifi);
            it.leanback = true;
            it.icon = c.getDrawable(R.drawable.tile_wifi);
            it.artRequested = true;
            it.tintTop = 0xFF2FA88F;
            it.tintBottom = 0xFF14574C;
            if (Ui.glass(c)) it.logo = iconFile(Config.get(c), it.id);
            out.add(it);
        }
    }

    private static void collect(PackageManager pm, Map<String, Item> found, String category,
            boolean leanback, String self) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(self) || found.containsKey(ai.packageName)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(ai.packageName, ai.name);
            Item it = new Item(ai.packageName, ri.loadLabel(pm), launch);
            it.info = ai;
            it.leanback = leanback;
            found.put(ai.packageName, it);
        }
    }

    /**
     * Loads a banner, plus an icon for Afterglow or when there is no banner. Slow: off the UI
     * thread. {@code art.<id>=<package>} in home.conf borrows another app's banner, which also
     * works when that app is switched off.
     */
    static void art(Context c, Item it, boolean cornerIcon) {
        if (it.info == null) return;
        PackageManager pm = c.getPackageManager();
        Config cfg = Config.get(c);
        Drawable other = borrowed(pm, cfg.get("art." + it.id, null));
        it.banner = other != null ? other : it.info.loadBanner(pm);
        if (it.banner == null || cornerIcon) it.icon = it.info.loadIcon(pm);
        if ("glass".equals(cfg.get(Config.TILE_STYLE))) {
            it.logo = iconFile(cfg, it.id);
            Drawable art = it.banner != null ? it.banner : it.icon;
            if (it.logo == null && art != null) {
                it.logo = Glass.logo(art, "white".equals(cfg.get(Config.LOGOS)));
            }
        }
    }

    private static Bitmap iconFile(Config cfg, String id) {
        File f = cfg.icon(id);
        return f == null ? null : BitmapFactory.decodeFile(f.getPath());
    }

    private static Drawable borrowed(PackageManager pm, String pkg) {
        if (pkg == null) return null;
        try {
            PackageInfo info = pm.getPackageInfo(pkg,
                    PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (info.activities != null) {
                for (ActivityInfo a : info.activities) {
                    Drawable banner = a.loadBanner(pm);
                    if (banner != null) return banner;
                }
            }
            return info.applicationInfo.loadBanner(pm);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * HDMI, AV and other passthrough inputs; HDMI first in port order. The intent is the TV's own
     * app for the input; {@link Input#intent} decides whether a tile opens that or the built-in
     * screen.
     */
    static List<Item> inputs(Context c, TvInputManager tv) {
        List<Item> out = new ArrayList<>();
        if (tv == null) return out;
        List<Item> other = new ArrayList<>();
        for (TvInputInfo in : tv.getTvInputList()) {
            if (!in.isPassthroughInput() || in.isHidden(c)) continue;
            CharSequence label = in.loadCustomLabel(c);
            if (TextUtils.isEmpty(label)) label = in.loadLabel(c);
            Intent view = new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(in.getId()));
            Item it = new Item(in.getId(), label, view);
            it.state = tv.getInputState(in.getId());
            (in.getType() == TvInputInfo.TYPE_HDMI ? out : other).add(it);
        }
        Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        Collections.sort(other, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        out.addAll(other);
        return out;
    }

    static CharSequence label(Config cfg, Item it) {
        return cfg.get("name." + it.id, it.label.toString());
    }

    static boolean shown(Config cfg, String kind, Item it) {
        if (cfg.list(kind + ".hide").contains(it.id)) return false;
        return !APPS.equals(kind) || it.leanback || cfg.list("apps.show").contains(it.id);
    }

    static void setShown(Config cfg, String kind, Item it, boolean on) {
        List<String> hide = cfg.list(kind + ".hide");
        hide.remove(it.id);
        if (!on && (it.leanback || !APPS.equals(kind))) hide.add(it.id);
        cfg.setList(kind + ".hide", hide);
        if (APPS.equals(kind) && !it.leanback) {
            List<String> show = cfg.list("apps.show");
            show.remove(it.id);
            if (on) show.add(it.id);
            cfg.setList("apps.show", show);
        }
    }

    /** The shown items, in the user's order; anything never moved keeps catalog order. */
    static List<Item> visible(Config cfg, String kind, List<Item> all) {
        return visible(cfg, kind, all, null);
    }

    /** As above, then newest first by {@code recent} (package to last-used time) when it is set. */
    static List<Item> visible(Config cfg, String kind, List<Item> all, Map<String, Long> recent) {
        List<String> order = cfg.list(kind + ".order");
        List<Item> out = new ArrayList<>();
        for (Item it : all) {
            if (shown(cfg, kind, it)) out.add(it);
        }
        Collections.sort(out, (a, b) -> Integer.compare(rank(order, a.id), rank(order, b.id)));
        // The sort is stable, so apps with no record keep the user's order after the rest.
        if (recent != null) Collections.sort(out, (a, b) -> Recent.compare(recent, a.id, b.id));
        return out;
    }

    private static int rank(List<String> order, String id) {
        int i = order.indexOf(id);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Saves {@code ids} as the new order, keeping the old positions of hidden items after them. */
    static void saveOrder(Config cfg, String kind, List<String> ids) {
        List<String> order = new ArrayList<>(ids);
        for (String id : cfg.list(kind + ".order")) {
            if (!order.contains(id)) order.add(id);
        }
        cfg.setList(kind + ".order", order);
    }
}
