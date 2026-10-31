package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.service.dreams.DreamService;
import android.widget.ImageView;

import java.io.File;

/**
 * A screensaver that shows one picture and nothing else: {@code screensaver.jpg} next to home.conf,
 * or the wallpaper when there is none. No clock, no video, no network, and it costs nothing while
 * the TV is in use. Fire OS has no menu for it; see launcher/README.md for the one setting.
 */
public final class Dream extends DreamService {
    private Bitmap picture;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setFullscreen(true);
        setInteractive(false);
        ImageView view = new ImageView(this);
        view.setBackgroundColor(Color.BLACK);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        File file = Config.get(this).screensaver();
        if (file != null) picture = BitmapFactory.decodeFile(file.getPath());
        view.setImageBitmap(picture);
        setContentView(view);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (picture != null) {
            picture.recycle();
            picture = null;
        }
    }
}
