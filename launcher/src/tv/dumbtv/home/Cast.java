package tv.dumbtv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Casting from a phone takes a different road for each kind of phone and app, and on Fire OS each
 * one is buried somewhere else. This screen puts them side by side: pick what you hold, and the
 * receiver for it opens, with what to press on the phone written next to it.
 */
public final class Cast extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String MIRROR = "com.amazon.cast.sink";
    private static final String SMARTTUBE = "org.smarttube.stable";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(Ui.page(this));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(56), dp(36), dp(56), dp(36));
        scroll.addView(list);

        TextView title = Ui.text(this, 30, Ui.TEXT, Ui.LIGHT);
        title.setText(Ui.t(this, "Cast from your phone", "Vom Handy auf den Fernseher"));
        title.setPadding(dp(16), 0, 0, dp(6));
        list.addView(title);

        String name = Settings.Global.getString(getContentResolver(), "device_name");
        TextView hint = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        hint.setText(Ui.t(this, "Phone and TV need the same Wi-Fi.", "Handy und Fernseher müssen im selben WLAN sein.")
                + (TextUtils.isEmpty(name) ? "" : "  " + Ui.t(this, "This TV is called: ", "Dieser Fernseher heißt: ") + name));
        hint.setPadding(dp(16), 0, 0, dp(18));
        list.addView(hint);

        // Apple's AirPlay service runs by itself on Fire TV televisions; nothing has to be opened,
        // and its screen only accepts Fire OS itself. So this row is an instruction, not a button.
        list.addView(row(Ui.t(this, "iPhone, iPad or Mac: AirPlay", "iPhone, iPad oder Mac: AirPlay"),
                Ui.t(this, "Always ready, nothing to open here. On the phone: Control Center › Screen Mirroring, then pick this TV. Or the AirPlay icon in an app.",
                        "Immer bereit, hier muss nichts geöffnet werden. Am Handy: Kontrollzentrum › Bildschirmsynchronisierung, dann diesen Fernseher wählen. Oder das AirPlay-Symbol in einer App."),
                null));
        list.addView(row(Ui.t(this, "Android phone or Windows PC: screen mirroring", "Android-Handy oder Windows-PC: Bildschirm spiegeln"),
                Ui.t(this, "Press OK, then on the phone: Quick Settings › Smart View or Cast. On Windows: Win+K. While this screen is open the TV leaves the Wi-Fi; press Back to end it.",
                        "OK drücken, dann am Handy: Schnelleinstellungen › Smart View oder Übertragen. Unter Windows: Win+K. Solange dieser Bildschirm offen ist, verlässt der Fernseher das WLAN; mit Zurück beenden."),
                v -> open(MIRROR)));
        list.addView(row("YouTube",
                Ui.t(this, "Opens SmartTube, which must be running to appear on the phone. Link once: SmartTube › Settings › Remote control, then enter the code in the YouTube app › Settings › Watch on TV.",
                        "Öffnet SmartTube, das dafür laufen muss. Einmal verbinden: SmartTube › Einstellungen › Fernsteuerung, dann den Code in der YouTube-App eingeben: Einstellungen › Auf dem Fernseher ansehen."),
                v -> open(SMARTTUBE)));

        TextView apps = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        apps.setLineSpacing(0, 1.3f);
        apps.setPadding(dp(16), dp(18), dp(16), 0);
        apps.setText(Ui.t(this, "Netflix, Prime Video and others have their own cast icon in the phone app: tap it and pick this TV. Nothing to open here.",
                "Netflix, Prime Video und andere haben ein eigenes Cast-Symbol in der Handy-App: einfach antippen und diesen Fernseher wählen. Hier muss dafür nichts geöffnet werden."));
        list.addView(apps);

        setContentView(scroll);
    }

    private View row(String heading, String how, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        // Still focusable when it only explains something, or the remote could not scroll to it.
        if (click != null) {
            row.setClickable(true);
            row.setOnClickListener(click);
        }
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        int accent = Config.get(this).accent();
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused},
                Ui.round((accent & 0x00FFFFFF) | 0x33000000, dp(12)));
        bg.addState(new int[0], Ui.round(0x0FFFFFFF, dp(12)));
        row.setBackground(Ui.glass(this) ? Glass.focusable(dp(14), dp(1.2f), Ui.glassTint(this), 0) : bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);

        TextView top = Ui.text(this, 19, Ui.TEXT, Ui.MEDIUM);
        top.setText(heading);
        TextView under = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        under.setText(how);
        under.setLineSpacing(0, 1.2f);
        under.setPadding(0, dp(6), 0, 0);
        row.addView(top);
        row.addView(under);
        return row;
    }

    private void open(String pkg) {
        Intent i = MIRROR.equals(pkg) ? mirror() : launchIntent(pkg);
        if (i == null) {
            Toast.makeText(this, Ui.t(this, "That app is not installed", "Diese App ist nicht installiert"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, Ui.t(this, "Could not open that", "Das ließ sich nicht öffnen"),
                    Toast.LENGTH_LONG).show();
        }
    }

    private Intent launchIntent(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : getPackageManager().getLaunchIntentForPackage(pkg);
    }

    /** Fire OS's own Miracast receiver; it has no launcher entry. */
    private Intent mirror() {
        Intent i = new Intent().setClassName(MIRROR, MIRROR_ACTIVITY);
        return i.resolveActivity(getPackageManager()) != null ? i : null;
    }

    private static final String MIRROR_ACTIVITY = "com.amazon.cast.sink.DisplayMirroringSinkActivity";

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
