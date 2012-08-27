package com.uphyca.imageloadlib;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap.CompressFormat;

public abstract class ImageCacheParams {

    // Default memory cache size
    protected static final int DEFAULT_MEM_CACHE_SIZE = 1024 * 1024 * 2; // 2MB
    protected static final int DEFAULT_MEM_CACHE_DIVIDER = 8; // memory
                                                              // class/this
                                                              // = mem cache
                                                              // size

    // Default disk cache size
    protected static final int DEFAULT_DISK_CACHE_SIZE = 1024 * 1024 * 10; // 10MB

    // Compression settings when writing images to disk cache
    protected static final CompressFormat DEFAULT_COMPRESS_FORMAT = CompressFormat.JPEG;
    protected static final int DEFAULT_COMPRESS_QUALITY = 75;

    protected static final String CACHE_FILENAME_PREFIX = "cache_";

    protected static final int ICS_DISK_CACHE_INDEX = 0;

    // Constants to easily toggle various caches
    protected static final boolean DEFAULT_MEM_CACHE_ENABLED = true;
    protected static final boolean DEFAULT_DISK_CACHE_ENABLED = true;
    protected static final boolean DEFAULT_CLEAR_DISK_CACHE_ON_START = false;
    

    public String uniqueName;

    public int memCacheSize = DEFAULT_MEM_CACHE_SIZE;
    public long diskCacheSize = DEFAULT_DISK_CACHE_SIZE;

    public CompressFormat compressFormat = DEFAULT_COMPRESS_FORMAT;
    public int compressQuality = DEFAULT_COMPRESS_QUALITY;

    public boolean memoryCacheEnabled = DEFAULT_MEM_CACHE_ENABLED;
    public boolean diskCacheEnabled = DEFAULT_DISK_CACHE_ENABLED;

    public boolean clearDiskCacheOnStart = DEFAULT_CLEAR_DISK_CACHE_ON_START;
    public String cacheFilenamePrefix = CACHE_FILENAME_PREFIX;
    public int memoryClass = 0;

    
    public static class ImageCacheParamsPostEclair extends ImageCacheParams {

        public ImageCacheParamsPostEclair(String uniqueName) {
            this.uniqueName = uniqueName;
        }

        public ImageCacheParamsPostEclair(Context context, String uniqueName) {
            this.uniqueName = uniqueName;
            final ActivityManager activityManager = (ActivityManager) context
                    .getSystemService(Activity.ACTIVITY_SERVICE);
            memoryClass = activityManager.getMemoryClass();
            memCacheSize = memoryClass / DEFAULT_MEM_CACHE_DIVIDER * 1024 * 1024;
        }
    }

    /**
     * Android 1.6 以前向け
     */
    public static class ImageCacheParamsPreEclair extends ImageCacheParams {

        public ImageCacheParamsPreEclair(String uniqueName) {
            this.uniqueName = uniqueName;
        }

        public ImageCacheParamsPreEclair(Context context, String uniqueName) {
            this.uniqueName = uniqueName;
            memoryClass = 16;
            memCacheSize = memoryClass / DEFAULT_MEM_CACHE_DIVIDER * 1024 * 1024;
        }
    }
}
