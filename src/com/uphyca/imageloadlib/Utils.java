package com.uphyca.imageloadlib;

import android.os.Build;
import android.support.v4.app.FragmentActivity;

import com.uphyca.imageloadlib.ImageFetcher.ImageFetcherPostEclair;
import com.uphyca.imageloadlib.ImageFetcher.ImageFetcherPreEclair;

public class Utils {

    public static ImageFetcher getImageFetcher(final FragmentActivity activity) {
        ImageFetcher fetcher;
        if (hasEclair()) {
            fetcher = new ImageFetcherPostEclair(activity);
        } else {
            fetcher = new ImageFetcherPreEclair(activity);
        }
        // RetainFragment から取得したメモリキャッシュを ImageFetcher にセット
        fetcher.setImageCache(ImageCache.findOrCreateCache(activity, "imageFetcher"));
        return fetcher;
    }

    public static boolean hasEclair() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.ECLAIR;
    }

    public static boolean hasFroyo() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO;
    }

    public static boolean hasGingerbread() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD;
    }

    public static boolean hasHoneycomb() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB;
    }

    public static boolean hasHoneycombMR1() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1;
    }
}
