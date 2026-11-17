package com.rafael.groksessions;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.ByteArrayOutputStream;

final class ShortcutIcon {
    private ShortcutIcon() {}

    static String encode(Bitmap icon) {
        if (icon == null || icon.isRecycled()) return null;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Bitmap scaled = Bitmap.createScaledBitmap(icon, 64, 64, true);
            try { scaled.compress(Bitmap.CompressFormat.PNG, 100, output); }
            finally { if (scaled != icon) scaled.recycle(); }
            if (output.size() > 32000) return null;
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) { return null; }
    }

    static Bitmap decode(String icon) {
        if (icon == null || icon.length() > 50000) return null;
        try {
            byte[] bytes = Base64.decode(icon, Base64.NO_WRAP);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 256 || bounds.outHeight > 256) return null;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (RuntimeException ignored) { return null; }
    }
}
