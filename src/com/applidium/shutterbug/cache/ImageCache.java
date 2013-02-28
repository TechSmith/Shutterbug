package com.applidium.shutterbug.cache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.os.AsyncTask;

import com.applidium.shutterbug.cache.DiskLruCache.Editor;
import com.applidium.shutterbug.cache.DiskLruCache.Snapshot;
import com.applidium.shutterbug.utils.DownloadRequest;
import com.applidium.shutterbug.utils.ShutterbugManager;

public class ImageCache {
    public interface ImageCacheListener {
        void onImageFound(ImageCache imageCache, Bitmap bitmap, String key, DownloadRequest downloadRequest);

        void onImageNotFound(ImageCache imageCache, String key, DownloadRequest downloadRequest);
    }

    // 1 entry per key
    private final static int         DISK_CACHE_VALUE_COUNT = 1;
    // 100 MB of disk cache
    private final static int         DISK_CACHE_MAX_SIZE    = 100 * 1024 * 1024;

    private static ImageCache        sImageCache;
    private Context                  mContext;
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache             mDiskCache;
    private Executor                 mCacheExecutor; // TODO: Merge with Cloud SDK executors

    ImageCache(Context context) {
        mContext = context;
        mCacheExecutor = Executors.newFixedThreadPool( 2 );
        // Get memory class of this device, exceeding this amount will throw an
        // OutOfMemory exception.
        final int memClass = ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();

        // Use 1/8th of the available memory for this memory cache.
        final int cacheSize = 1024 * 1024 * memClass / 8;

        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // The cache size will be measured in bytes rather than number
                // of items.
                return bitmap.getRowBytes() * bitmap.getHeight();
            }
        };

        openDiskCache();
    }

    public static ImageCache getSharedImageCache(Context context) {
        if (sImageCache == null) {
            sImageCache = new ImageCache(context);
        }
        return sImageCache;
    }

    public void queryCache(String cacheKey, ImageCacheListener listener, DownloadRequest downloadRequest) {
        if (cacheKey == null) {
            listener.onImageNotFound(this, cacheKey, downloadRequest);
            return;
        }

        // First check the in-memory cache...
        Bitmap cachedBitmap = mMemoryCache.get(cacheKey);

        if (cachedBitmap != null) {
            // ...notify listener immediately, no need to go async
            listener.onImageFound(this, cachedBitmap, cacheKey, downloadRequest);
            return;
        }

        if (mDiskCache != null) {
            new BitmapDecoderTask(cacheKey, listener, downloadRequest).executeOnExecutor(mCacheExecutor);
            return;
        }
        listener.onImageNotFound(this, cacheKey, downloadRequest);
    }
    
    public void remove(String cacheKey) {
        mMemoryCache.remove(cacheKey);

        try {
            mDiskCache.remove(cacheKey);
        } catch ( IOException e ) {
            e.printStackTrace();
        }
    }

    public Snapshot storeToDisk(InputStream inputStream, String cacheKey) {
        try {
            Editor editor = mDiskCache.edit(cacheKey);
            final OutputStream outputStream = editor.newOutputStream(0);
            final int bufferSize = 1024;
            try {
                byte[] bytes = new byte[bufferSize];
                for (;;) {
                    int count = inputStream.read(bytes, 0, bufferSize);
                    if (count == -1)
                        break;
                    outputStream.write(bytes, 0, count);
                }
                outputStream.close();
                editor.commit();
                return mDiskCache.get(cacheKey);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void storeToMemory(Bitmap bitmap, String cacheKey) {
        mMemoryCache.put(cacheKey, bitmap);
    }
    
    public void onTrimMemory(int level) {
       if (mMemoryCache != null) {
          if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) { // 60
             // Nearing middle of list of cached background apps
             mMemoryCache.evictAll();
          } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) { // 40
             // Entering list of cached background apps
             mMemoryCache.trimToSize(mMemoryCache.size() / 2);
          }
       }
    }
    
    public void onLowMemory() {
       if (mMemoryCache != null) {
          mMemoryCache.evictAll();
       }
    }

    public void clear() {
        try {
            mDiskCache.delete();
            openDiskCache();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mMemoryCache.evictAll();
    }

    private class BitmapDecoderTask extends AsyncTask<Void, Void, Bitmap> {
        private String             mCacheKey;
        private ImageCacheListener mListener;
        private DownloadRequest    mDownloadRequest;

        public BitmapDecoderTask(String cacheKey, ImageCacheListener listener, DownloadRequest downloadRequest) {
            mCacheKey = cacheKey;
            mListener = listener;
            mDownloadRequest = downloadRequest;
        }

        @Override
        protected Bitmap doInBackground(Void... params) {
            try {
                Snapshot snapshot = mDiskCache.get(mCacheKey);
                if (snapshot != null) {
                    return ShutterbugManager.getSampledBitmapFromStream(snapshot.getInputStream(0));
                } else {
                    return null;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            if (result != null) {
                storeToMemory(result, mCacheKey);
                mListener.onImageFound(ImageCache.this, result, mCacheKey, mDownloadRequest);
            } else {
                mListener.onImageNotFound(ImageCache.this, mCacheKey, mDownloadRequest);
            }
        }

    }
    
    private void openDiskCache() {
        File directory;
        if (android.os.Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED)) {
            directory = new File(android.os.Environment.getExternalStorageDirectory(), "Applidium Image Cache");
        } else {
            directory = mContext.getCacheDir();
        }
        int versionCode;
        try {
            versionCode = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0).versionCode;
        } catch (NameNotFoundException e) {
            versionCode = 0;
            e.printStackTrace();
        }
        try {
            mDiskCache = DiskLruCache.open(directory, versionCode, DISK_CACHE_VALUE_COUNT, DISK_CACHE_MAX_SIZE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
