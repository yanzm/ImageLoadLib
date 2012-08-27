/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uphyca.imageloadlib;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import com.uphyca.android.imageloadlib.BuildConfig;

/**
 * A simple disk LRU bitmap cache to illustrate how a disk cache would be used
 * for bitmap caching. A much more robust and efficient disk LRU cache solution
 * can be found in the ICS source code
 * (libcore/luni/src/main/java/libcore/io/DiskLruCache.java) and is preferable
 * to this simple implementation.
 */
public abstract class DiskLruCache {
    private static final String TAG = "DiskLruCache";

    private static final int INITIAL_CAPACITY = 32;
    private static final float LOAD_FACTOR = 0.75f;

    private static final String CACHE_FILENAME_PREFIX = "cache_";

    private static final int IO_BUFFER_SIZE = 1 * 1024; // 1KB
    private CompressFormat mCompressFormat = CompressFormat.JPEG;
    private int mCompressQuality = 70;

    private final File mCacheDir;

    private static final int MAX_REMOVALS = 4;
    private final int maxCacheItemSize = 64; // 64 item default
    private long maxCacheByteSize = 1024 * 1024 * 5; // 5MB default

    private int cacheSize = 0;
    private int cacheByteSize = 0;

    private final Map<String, String> mLinkedHashMap = Collections.synchronizedMap(new LinkedHashMap<String, String>(
            INITIAL_CAPACITY, LOAD_FACTOR, true));

    /**
     * A filename filter to use to identify the cache filenames which have
     * CACHE_FILENAME_PREFIX prepended.
     */
    private static final FilenameFilter cacheFileFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String filename) {
            return filename.startsWith(CACHE_FILENAME_PREFIX);
        }
    };

    /**
     * Used to fetch an instance of DiskLruCache.
     * 
     * @param context
     * @param cacheDir
     * @param maxByteSize
     * @return
     */
    public static DiskLruCache openCache(Context context, File cacheDir, long maxByteSize) {
        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }

        if (cacheDir.isDirectory() && cacheDir.canWrite() && getUsableSpace(cacheDir) > maxByteSize) {
            if (Utils.hasEclair()) {
                return new DiskLruCachePostEclair(cacheDir, maxByteSize);
            } else {
                return new DiskLruCachePreEclair(cacheDir, maxByteSize);
            }
        }

        return null;
    }

    /**
     * Constructor that should not be called directly, instead use
     * {@link DiskLruCache#openCache(Context, File, long)} which runs some extra
     * checks before creating a DiskLruCache instance.
     * 
     * @param cacheDir
     * @param maxByteSize
     */
    private DiskLruCache(File cacheDir, long maxByteSize) {
        mCacheDir = cacheDir;
        maxCacheByteSize = maxByteSize;
    }

    /**
     * Check how much usable space is available at a given path.
     *
     * @param path The path to check
     * @return The space available in bytes
     */
    protected static long getUsableSpace(File path) {
        if (Utils.hasEclair()) {
            return DiskLruCachePostEclair.getUsableSpace(path);
        } else {
            return DiskLruCachePreEclair.getUsableSpace(path);
        }
    }

    /**
     * Add a bitmap to the disk cache.
     * 
     * @param key
     *            A unique identifier for the bitmap.
     * @param data
     *            The bitmap to store.
     */
    public void put(String key, Bitmap data) {
        synchronized (mLinkedHashMap) {
            if (mLinkedHashMap.get(key) == null) {
                try {
                    final String file = createFilePath(mCacheDir, key);
                    if (writeBitmapToFile(data, file)) {
                        put(key, file);
                        flushCache();
                    }
                } catch (final FileNotFoundException e) {
                    Log.e(TAG, "Error in put: " + e.getMessage());
                } catch (final IOException e) {
                    Log.e(TAG, "Error in put: " + e.getMessage());
                }
            }
        }
    }

    private void put(String key, String file) {
        mLinkedHashMap.put(key, file);
        cacheSize = mLinkedHashMap.size();
        cacheByteSize += new File(file).length();
    }

    /**
     * Flush the cache, removing oldest entries if the total size is over the
     * specified cache size. Note that this isn't keeping track of stale files
     * in the cache directory that aren't in the HashMap. If the images and keys
     * in the disk cache change often then they probably won't ever be removed.
     */
    private void flushCache() {
        Entry<String, String> eldestEntry;
        File eldestFile;
        long eldestFileSize;
        int count = 0;

        while (count < MAX_REMOVALS && (cacheSize > maxCacheItemSize || cacheByteSize > maxCacheByteSize)) {
            eldestEntry = mLinkedHashMap.entrySet().iterator().next();
            eldestFile = new File(eldestEntry.getValue());
            eldestFileSize = eldestFile.length();
            mLinkedHashMap.remove(eldestEntry.getKey());
            eldestFile.delete();
            cacheSize = mLinkedHashMap.size();
            cacheByteSize -= eldestFileSize;
            count++;
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "flushCache - Removed cache file, " + eldestFile + ", " + eldestFileSize);
            }
        }
    }

    /**
     * Get an image from the disk cache.
     * 
     * @param key
     *            The unique identifier for the bitmap
     * @return The bitmap or null if not found
     */
    public Bitmap get(String key) {
        synchronized (mLinkedHashMap) {
            final String file = mLinkedHashMap.get(key);
            if (file != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Disk cache hit");
                }
                return BitmapFactory.decodeFile(file);
            } else {
                final String existingFile = createFilePath(mCacheDir, key);
                if (new File(existingFile).exists()) {
                    put(key, existingFile);
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Disk cache hit (existing file)");
                    }
                    return BitmapFactory.decodeFile(existingFile);
                }
            }
            return null;
        }
    }

    /**
     * Checks if a specific key exist in the cache.
     * 
     * @param key
     *            The unique identifier for the bitmap
     * @return true if found, false otherwise
     */
    public boolean containsKey(String key) {
        // See if the key is in our HashMap
        if (mLinkedHashMap.containsKey(key)) {
            return true;
        }

        // Now check if there's an actual file that exists based on the key
        final String existingFile = createFilePath(mCacheDir, key);
        if (new File(existingFile).exists()) {
            // File found, add it to the HashMap for future use
            put(key, existingFile);
            return true;
        }
        return false;
    }

    /**
     * Removes all disk cache entries from this instance cache dir
     */
    public void clearCache() {
        DiskLruCache.clearCache(mCacheDir);
    }

    /**
     * Removes all disk cache entries from the application cache directory in
     * the uniqueName sub-directory.
     * 
     * @param context
     *            The context to use
     * @param uniqueName
     *            A unique cache directory name to append to the app cache
     *            directory
     */
    public static void clearCache(Context context, String uniqueName) {
        File cacheDir = getDiskCacheDir(context, uniqueName);
        clearCache(cacheDir);
    }

    /**
     * Removes all disk cache entries from the given directory. This should not
     * be called directly, call {@link DiskLruCache#clearCache(Context, String)}
     * or {@link DiskLruCache#clearCache()} instead.
     * 
     * @param cacheDir
     *            The directory to remove the cache files from
     */
    private static void clearCache(File cacheDir) {
        final File[] files = cacheDir.listFiles(cacheFileFilter);
        for (int i = 0; i < files.length; i++) {
            files[i].delete();
        }
    }

    /**
     * Get a usable cache directory (external if available, internal otherwise).
     * 
     * @param context
     *            The context to use
     * @param uniqueName
     *            A unique directory name to append to the cache dir
     * @return The cache dir
     */
    public static File getDiskCacheDir(Context context, String uniqueName) {

        final String cachePath = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
                || !isExternalStorageRemovable() ? getExternalCacheDir(context).getPath() : context.getCacheDir()
                .getPath();

        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * Check if external storage is built-in or removable.
     *
     * @return True if external storage is removable (like an SD card), false
     *         otherwise.
     */
    protected static boolean isExternalStorageRemovable() {
        if (Utils.hasEclair()) {
            return DiskLruCachePostEclair.isExternalStorageRemovable();
        } else {
            return DiskLruCachePreEclair.isExternalStorageRemovable();
        }
    }

    /**
     * Get the external app cache directory.
     *
     * @param context The context to use
     * @return The external cache dir
     */
    protected static File getExternalCacheDir(Context context) {
        if (Utils.hasEclair()) {
            return DiskLruCachePostEclair.getExternalCacheDir(context);
        } else {
            return DiskLruCachePreEclair.getExternalCacheDir(context);
        }
    }

    /**
     * Creates a constant cache file path given a target cache directory and an
     * image key.
     * 
     * @param cacheDir
     * @param key
     * @return
     */
    public static String createFilePath(File cacheDir, String key) {
        return cacheDir.getAbsolutePath() + File.separator + CACHE_FILENAME_PREFIX + key;
    }

    /**
     * Sets the target compression format and quality for images written to the
     * disk cache.
     * 
     * @param compressFormat
     * @param quality
     */
    public void setCompressParams(CompressFormat compressFormat, int quality) {
        mCompressFormat = compressFormat;
        mCompressQuality = quality;
    }

    /**
     * Writes a bitmap to a file. Call
     * {@link DiskLruCache#setCompressParams(CompressFormat, int)} first to set
     * the target bitmap compression and format.
     * 
     * @param bitmap
     * @param file
     * @return
     */
    private boolean writeBitmapToFile(Bitmap bitmap, String file) throws IOException, FileNotFoundException {

        OutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(file), IO_BUFFER_SIZE);
            return bitmap.compress(mCompressFormat, mCompressQuality, out);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    public static class DiskLruCachePostEclair extends DiskLruCache {
        public DiskLruCachePostEclair(File cacheDir, long maxByteSize) {
            super(cacheDir, maxByteSize);
        }

        /**
         * Check how much usable space is available at a given path.
         *
         * @param path The path to check
         * @return The space available in bytes
         */
        @TargetApi(Build.VERSION_CODES.GINGERBREAD)
        protected static long getUsableSpace(File path) {
            if (Utils.hasGingerbread()) {
                return path.getUsableSpace();
            }
            final StatFs stats = new StatFs(path.getPath());
            return (long) stats.getBlockSize() * (long) stats.getAvailableBlocks();
        }

        /**
         * Check if external storage is built-in or removable.
         *
         * @return True if external storage is removable (like an SD card), false
         *         otherwise.
         */
        @TargetApi(Build.VERSION_CODES.GINGERBREAD)
        protected static boolean isExternalStorageRemovable() {
            if (Utils.hasGingerbread()) {
                return Environment.isExternalStorageRemovable();
            }
            return true;
        }

        /**
         * Get the external app cache directory.
         *
         * @param context The context to use
         * @return The external cache dir
         */
        @TargetApi(Build.VERSION_CODES.FROYO)
        protected static File getExternalCacheDir(Context context) {
            if (Utils.hasFroyo()) {
                File cacheDir = context.getExternalCacheDir();
                if (cacheDir != null) {
                    return cacheDir;
                }
            }

            // Froyo 以前は自前でディレクトリを作成する
            final String cacheDir = "/Android/data/" + context.getPackageName() + "/cache/";
            return new File(Environment.getExternalStorageDirectory().getPath() + cacheDir);
        }
    }

    /**
     * Android 1.6 以前向け
     */
    public static class DiskLruCachePreEclair extends DiskLruCache {
        public DiskLruCachePreEclair(File cacheDir, long maxByteSize) {
            super(cacheDir, maxByteSize);
        }

        /**
         * Check how much usable space is available at a given path.
         *
         * @param path The path to check
         * @return The space available in bytes
         */
        protected static long getUsableSpace(File path) {
            final StatFs stats = new StatFs(path.getPath());
            return (long) stats.getBlockSize() * (long) stats.getAvailableBlocks();
        }

        /**
         * Check if external storage is built-in or removable.
         *
         * @return True if external storage is removable (like an SD card), false
         *         otherwise.
         */
        protected static boolean isExternalStorageRemovable() {
            return true;
        }

        /**
         * Get the external app cache directory.
         *
         * @param context The context to use
         * @return The external cache dir
         */
        protected static File getExternalCacheDir(Context context) {
            // Froyo 以前は自前でディレクトリを作成する
            final String cacheDir = "/Android/data/" + context.getPackageName() + "/cache/";
            return new File(Environment.getExternalStorageDirectory().getPath() + cacheDir);
        }
    }
}
