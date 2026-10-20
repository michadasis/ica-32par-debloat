package com.cleanhome.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Cheap dominant-color sampler for the ambient glow. Downscales the art to a
 * tiny bitmap and averages the vivid, non-dark pixels. No Palette dependency.
 */
public class ColorUtil {

    public static int dominant(Drawable d, int fallback) {
        if (d == null) return fallback;
        try {
            int w = 24, h = 24;
            Bitmap bmp;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                bmp = Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), w, h, true);
            } else {
                int iw = Math.max(1, d.getIntrinsicWidth());
                int ih = Math.max(1, d.getIntrinsicHeight());
                Bitmap tmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(tmp);
                d.setBounds(0, 0, iw, ih);
                d.draw(c);
                bmp = Bitmap.createScaledBitmap(tmp, w, h, true);
                tmp.recycle();
            }

            double rs = 0, gs = 0, bs = 0, wsum = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = bmp.getPixel(x, y);
                    int a = Color.alpha(p);
                    if (a < 128) continue;
                    int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
                    int max = Math.max(r, Math.max(g, b));
                    int min = Math.min(r, Math.min(g, b));
                    int sat = max - min;          // chroma
                    // weight vivid, mid-bright pixels; ignore near-black / near-white / gray
                    if (max < 40 || min > 225) continue;
                    double weight = 1.0 + sat / 32.0;
                    rs += r * weight; gs += g * weight; bs += b * weight; wsum += weight;
                }
            }
            bmp.recycle();
            if (wsum <= 0) return fallback;
            int r = (int) (rs / wsum), g = (int) (gs / wsum), b = (int) (bs / wsum);
            // boost saturation a touch so the glow reads as a color, not gray
            return boost(Color.rgb(r, g, b));
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int boost(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.4f + 0.1f);
        hsv[2] = Math.min(1f, Math.max(hsv[2], 0.6f));
        return Color.HSVToColor(hsv);
    }
}
