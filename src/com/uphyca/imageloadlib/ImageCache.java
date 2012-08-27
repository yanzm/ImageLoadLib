package com.uphyca.imageloadlib;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.util.LruCache;

import com.uphyca.imageloadlib.ImageCacheParams.ImageCacheParamsPostEclair;
import com.uphyca.imageloadlib.ImageCacheParams.ImageCacheParamsPreEclair;

public abstract class ImageCache {
    private static final String TAG = ImageCache.class.getSimpleName();

    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskCache;

    protected ImageCache(Context context, ImageCacheParams cacheParams) {
        init(context, cacheParams);
    }

    protected ImageCache(Context context, String uniqueName) {
    }

    protected void init(Context context, ImageCacheParams cacheParams) {

        // Set up disk cache
        if (cacheParams.diskCacheEnabled) {
            final File diskCacheDir = DiskLruCache.getDiskCacheDir(context, cacheParams.uniqueName);
            mDiskCache = DiskLruCache.openCache(context, diskCacheDir, cacheParams.diskCacheSize);
        }

        // Set up memory cache
        if (cacheParams.memoryCacheEnabled) {
            mMemoryCache = new LruCache<String, Bitmap>(cacheParams.memCacheSize) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return getBitmapSize(bitmap);
                }
            };
        }
    }

    /**
     * Get the size in bytes of a bitmap.
     * @param bitmap
     * @return size in bytes
     */
    protected static int getBitmapSize(Bitmap bitmap) {
        if (Utils.hasEclair()) {
            return ImageCachePostEclair.getBitmapSize(bitmap);
        } else {
            return ImageCachePreEclair.getBitmapSize(bitmap);
        }
    }

    public static ImageCache findOrCreateCache(final FragmentActivity activity, final String uniqueName) {

        final RetainFragment mRetainFragment = findOrCreateRetainFragment(activity.getSupportFragmentManager());

        ImageCache imageCache = (ImageCache) mRetainFragment.getObject();

        if (imageCache == null) {
            if (Utils.hasEclair()) {
                imageCache = new ImageCachePostEclair(activity, uniqueName);
            } else {
                imageCache = new ImageCachePreEclair(activity, uniqueName);
            }
            mRetainFragment.setObject(imageCache);
        }

        return imageCache;
    }

    protected static RetainFragment findOrCreateRetainFragment(FragmentManager fm) {

        RetainFragment mRetainFragment = (RetainFragment) fm.findFragmentByTag(TAG);

        if (mRetainFragment == null) {
            mRetainFragment = new RetainFragment();
            fm.beginTransaction().add(mRetainFragment, TAG).commit();
        }

        return mRetainFragment;
    }

    public Bitmap getBitmapFromMemCache(String data) {
        if (mMemoryCache != null) {
            final Bitmap memBitmap = mMemoryCache.get(data);
            if (memBitmap != null) {
                return memBitmap;
            }
        }
        return null;
    }

    public Bitmap getBitmapFromDiskCache(String data) {
        final String key = hashKeyForDisk(data);

        if (mDiskCache != null) {
            return mDiskCache.get(key);
        }
        return null;
    }

    public static String hashKeyForDisk(String key) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("SHA-1");
            mDigest.update(key.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(key.hashCode());
        }

        return cacheKey;
    }

    private static String bytesToHexString(byte[] bytes) {
        // http://stackoverflow.com/questions/332079
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    public synchronized void addBitmapToCache(String data, Bitmap bitmap) {
        if (data == null || bitmap == null) {
            return;
        }

        // Add to memory cache
        if (mMemoryCache != null && mMemoryCache.get(data) == null) {
            mMemoryCache.put(data, bitmap);
        }

        // Add to disk cache
        if (mDiskCache != null) {
            final String key = hashKeyForDisk(data);
            if (!mDiskCache.containsKey(key)) {
                mDiskCache.put(key, bitmap);
            }
        }
    }

    public static class ImageCachePostEclair extends ImageCache {

        private ImageCachePostEclair(Context context, ImageCacheParams cacheParams) {
            super(context, cacheParams);
        }

        private ImageCachePostEclair(Context context, String uniqueName) {
            super(context, uniqueName);
            init(context, new ImageCacheParamsPostEclair(context, uniqueName));
        }

        /**
         * Get the size in bytes of a bitmap.
         * @param bitmap
         * @return size in bytes
         */
        @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
        protected static int getBitmapSize(Bitmap bitmap) {
            if (Utils.hasHoneycombMR1()) {
                return bitmap.getByteCount();
            }
            return bitmap.getRowBytes() * bitmap.getHeight();
        }
    }

    /**
     * Android 1.6 以前向け
     */
    public static class ImageCachePreEclair extends ImageCache {

        private ImageCachePreEclair(Context context, ImageCacheParams cacheParams) {
            super(context, cacheParams);
        }

        private ImageCachePreEclair(Context context, String uniqueName) {
            super(context, uniqueName);
            init(context, new ImageCacheParamsPreEclair(context, uniqueName));
        }

        /**
         * Get the size in bytes of a bitmap.
         * @param bitmap
         * @return size in bytes
         */
        protected static int getBitmapSize(Bitmap bitmap) {
            return bitmap.getRowBytes() * bitmap.getHeight();
        }
    }
}
