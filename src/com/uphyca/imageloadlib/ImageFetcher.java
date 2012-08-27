package com.uphyca.imageloadlib;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Hashtable;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import android.widget.ImageView;

public abstract class ImageFetcher {
    private static final String TAG = ImageFetcher.class.getSimpleName();

    public static final int IO_BUFFER_SIZE_BYTES = 1 * 1024; // 1KB

    private static final int DEFAULT_MAX_THUMBNAIL_BYTES = 70 * 1024; // 70KB
    private static final int DEFAULT_MAX_IMAGE_HEIGHT = 1024;
    private static final int DEFAULT_MAX_IMAGE_WIDTH = 1024;

    private static final int DEFAULT_HTTP_CACHE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final String DEFAULT_HTTP_CACHE_DIR = "http";

    public static class ImageFetcherParams {
        public int mImageWidth = DEFAULT_MAX_IMAGE_WIDTH;
        public int mImageHeight = DEFAULT_MAX_IMAGE_HEIGHT;
        public int mMaxThumbnailBytes = DEFAULT_MAX_THUMBNAIL_BYTES;
        public int mHttpCacheSize = DEFAULT_HTTP_CACHE_SIZE;
        public String mHttpCacheDir = DEFAULT_HTTP_CACHE_DIR;
    }

    private Context mContext;
    private ImageFetcherParams mFetcherParams;
    private ImageCache mImageCache;

    private final Hashtable<Integer, Bitmap> loadingBitmaps = new Hashtable<Integer, Bitmap>(2);

    public ImageFetcher(Context context, ImageFetcherParams params) {
        mContext = context;
        mFetcherParams = params;
    }

    public ImageFetcher(Context context) {
        mContext = context;
        mFetcherParams = new ImageFetcherParams();
    }

    public void setImageCache(ImageCache cacheCallback) {
        mImageCache = cacheCallback;
    }

    public ImageCache getImageCache() {
        return mImageCache;
    }
    
    public void loadImage(String url, ImageView imageView, int resId, int reqWidth, int reqHeight) {
        mFetcherParams.mImageHeight = reqHeight;
        mFetcherParams.mImageWidth = reqWidth;
        loadImage(url, imageView, resId);
    }

    public void loadImage(String url, ImageView imageView, int resId) {
        if (!loadingBitmaps.containsKey(resId)) {
            // 複数回のデコードを防ぐため
            loadingBitmaps.put(resId, BitmapFactory.decodeResource(mContext.getResources(), resId));
        }
        loadImage(url, imageView, loadingBitmaps.get(resId));
    }

    private void loadImage(String url, ImageView imageView, Bitmap loadingBitmap) {
        Bitmap bitmap = null;

        // キャッシュにあるかチェック
        if (mImageCache != null) {
            bitmap = mImageCache.getBitmapFromMemCache(url);
        }

        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);

        } else if (cancelPotentialWork(url, imageView)) {
            final BitmapWorkerTask task = new BitmapWorkerTask(imageView);
            final AsyncDrawable asyncDrawable = new AsyncDrawable(mContext.getResources(), loadingBitmap, task);
            imageView.setImageDrawable(asyncDrawable);
            executeTaskInParallel(url, task);
        }
    }

    protected abstract void executeTaskInParallel(String url, BitmapWorkerTask task);

    private static boolean cancelPotentialWork(Object data, ImageView imageView) {
        final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final Object bitmapData = bitmapWorkerTask.data;
            if (bitmapData == null || !bitmapData.equals(data)) {
                // 以前のタスクをキャンセル
                bitmapWorkerTask.cancel(true);
            } else {
                // 同じタスクがすでに走っているので、このタスクは実行しない
                return false;
            }
        }
        // この ImageView に関連する新しいタスクを実行する
        return true;
    }

    private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable) {
                final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    private static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

        public AsyncDrawable(Resources res, Bitmap bitmap, BitmapWorkerTask bitmapWorkerTask) {
            super(res, bitmap);

            bitmapWorkerTaskReference = new WeakReference<BitmapWorkerTask>(bitmapWorkerTask);
        }

        public BitmapWorkerTask getBitmapWorkerTask() {
            return bitmapWorkerTaskReference.get();
        }
    }

    class BitmapWorkerTask extends AsyncTask<String, Void, Bitmap> {
        private Object data;
        private final WeakReference<ImageView> mImageViewReference;

        public BitmapWorkerTask(ImageView imageView) {
            mImageViewReference = new WeakReference<ImageView>(imageView);
        }

        // バックグラウンドで画像をデコード
        @Override
        protected Bitmap doInBackground(String... params) {
            data = params[0];
            final String dataString = String.valueOf(data);
            Bitmap bitmap = null;

            // ディスクキャッシュにあるかチェック
            if (mImageCache != null && !isCancelled() && getAttachedImageView() != null) {
                bitmap = mImageCache.getBitmapFromDiskCache(dataString);
            }

            if (bitmap == null) {
                bitmap = processBitmap(params[0]);
            }

            if (bitmap != null && mImageCache != null) {
                mImageCache.addBitmapToCache(dataString, bitmap);
            }

            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            // キャンセルされていたらなにもしない
            if (isCancelled()) {
                bitmap = null;
            }

            final ImageView imageView = getAttachedImageView();
            if (bitmap != null && imageView != null) {
                setImageBitmap(imageView, bitmap);
            }
        }

        private ImageView getAttachedImageView() {
            final ImageView imageView = mImageViewReference.get();
            final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

            if (this == bitmapWorkerTask && imageView != null) {
                return imageView;
            }

            return null;
        }
    }

    private Bitmap processBitmap(String url) {
        final File f = downloadBitmapToFile(mContext, url, mFetcherParams.mHttpCacheDir);
        if (f != null) {
            // Return a sampled down version
            final Bitmap bitmap = decodeSampledBitmapFromFile(f.toString(), mFetcherParams.mImageWidth,
                    mFetcherParams.mImageHeight);
            f.delete();
            return bitmap;
        }
        
        return null;
    }

    private static void disableConnectionReuseIfNecessary() {
        // HTTP connection reuse which was buggy pre-froyo
        // http://android-developers.blogspot.com/2011/09/androids-http-clients.html
        if (!Utils.hasFroyo()) {
            System.setProperty("http.keepAlive", "false");
        }
    }

    private static File downloadBitmapToFile(Context context, String urlString, String uniqueName) {
        final File cacheDir = DiskLruCache.getDiskCacheDir(context, uniqueName);

        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }

        disableConnectionReuseIfNecessary();
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        InputStream in = null;

        try {
            final File tempFile = File.createTempFile("bitmap", null, cacheDir);

            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            if (urlConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE_BYTES);
            out = new BufferedOutputStream(new FileOutputStream(tempFile), IO_BUFFER_SIZE_BYTES);

            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }

            return tempFile;

        } catch (final IOException e) {
            Log.e(TAG, "Error in downloadBitmap - " + e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (final IOException e) {
                Log.e(TAG, "Error in downloadBitmap - " + e);
            }
        }

        return null;
    }

    private void setImageBitmap(ImageView imageView, Bitmap bitmap) {
        // if (mFadeInBitmap) {
        // // Use TransitionDrawable to fade in.
        // final TransitionDrawable td =
        // new TransitionDrawable(new Drawable[] {
        // new ColorDrawable(android.R.color.transparent),
        // new BitmapDrawable(mContext.getResources(), bitmap)
        // });
        // //noinspection deprecation
        // imageView.setBackgroundDrawable(imageView.getDrawable());
        // imageView.setImageDrawable(td);
        // td.startTransition(FADE_IN_TIME);
        // } else {
        imageView.setImageBitmap(bitmap);
        // }
    }

    private static synchronized Bitmap decodeSampledBitmapFromFile(String filePath, int reqWidth, int reqHeight) {

        // inJustDecodeBounds=true で画像のサイズをチェック
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filePath, options);

        // inSampleSize を計算
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // inSampleSize をセットしてデコード
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(filePath, options);
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {

        // 画像の元サイズ
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            if (width > height) {
                inSampleSize = Math.round((float) height / (float) reqHeight);
            } else {
                inSampleSize = Math.round((float) width / (float) reqWidth);
            }
        }
        return inSampleSize;
    }

    public static class ImageFetcherPostEclair extends ImageFetcher {

        public ImageFetcherPostEclair(Context context) {
            super(context);
        }

        public ImageFetcherPostEclair(Context context, ImageFetcherParams params) {
            super(context, params);
        }

        @Override
        @TargetApi(Build.VERSION_CODES.HONEYCOMB)
        protected void executeTaskInParallel(String url, BitmapWorkerTask task) {
            if (Utils.hasHoneycomb()) {
                // Execute in parallel
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, url);
            } else {
                task.execute(url);
            }
        }
    }
    
    /**
     * Android 1.6 以前向け
     */
    public static class ImageFetcherPreEclair extends ImageFetcher {

        public ImageFetcherPreEclair(Context context) {
            super(context);
        }

        public ImageFetcherPreEclair(Context context, ImageFetcherParams params) {
            super(context, params);
        }

        @Override
        protected void executeTaskInParallel(String url, BitmapWorkerTask task) {
            task.execute(url);
        }
    }
}
