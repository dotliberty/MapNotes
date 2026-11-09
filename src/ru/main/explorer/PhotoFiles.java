package ru.main.explorer;

import android.content.Context;
import android.graphics.*;
import android.location.Location;
import android.media.ExifInterface;
import java.io.*;

public final class PhotoFiles {
    public static long save(Context context, byte[] jpeg, Location location, long time)
            throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1)
            throw new IOException(
                    Language.text(context, R.string.camera_returned_an_invalid_image));
        File dir = new File(context.getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs())
            throw new IOException(Language.text(context, R.string.could_not_create_photo_folder));
        File file = File.createTempFile("photo-", ".jpg", dir);
        try {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(jpeg);
                out.getFD().sync();
            }
            try (Store db = new Store(context)) {
                return db.addPhoto(file.getPath(), location, time);
            }
        } catch (Exception e) {
            file.delete();
            throw e;
        }
    }

    public static Bitmap load(String path, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth < 1) return null;
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > maxSide * 2)
            o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap b = BitmapFactory.decodeFile(path, o);
        if (b == null) return null;
        try {
            int orientation =
                    new ExifInterface(path)
                            .getAttributeInt(
                                    ExifInterface.TAG_ORIENTATION,
                                    ExifInterface.ORIENTATION_NORMAL);
            Matrix m = new Matrix();
            switch (orientation) {
                case 2:
                    m.setScale(-1, 1);
                    break;
                case 3:
                    m.setRotate(180);
                    break;
                case 4:
                    m.setScale(1, -1);
                    break;
                case 5:
                    m.setRotate(90);
                    m.postScale(-1, 1);
                    break;
                case 6:
                    m.setRotate(90);
                    break;
                case 7:
                    m.setRotate(270);
                    m.postScale(-1, 1);
                    break;
                case 8:
                    m.setRotate(270);
                    break;
            }
            if (!m.isIdentity()) {
                Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (rotated != b) b.recycle();
                b = rotated;
            }
        } catch (IOException ignored) {
        }
        return b;
    }
}
