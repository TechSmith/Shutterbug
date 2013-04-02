package com.applidium.shutterbug.cache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.os.AsyncTask;

import com.applidium.shutterbug.cache.DiskLruCache.Editor;
import com.applidium.shutterbug.cache.DiskLruCache.Snapshot;
import com.applidium.shutterbug.utils.DownloadRequest;
import com.techsmith.utilities.Bitmaps;
import com.techsmith.utilities.IO;
import com.techsmith.utilities.ThreadPoolAsyncTaskRunner;

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

    ImageCache(Context context) {
        mContext = context;

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

    public void queryCache(String url, ImageCacheListener listener, DownloadRequest downloadRequest) {
        if (url == null) {
            listener.onImageNotFound(this, url, downloadRequest);
            return;
        }
        
        String fullSizeCacheKey = getCacheKey(url);
        String scaledCacheKey = "";
        
        int desiredWidth = downloadRequest.getListener().getDesiredWidth();
        int desiredHeight = downloadRequest.getListener().getDesiredHeight();
        
        if ( desiredWidth > 0 && desiredHeight > 0 ) {
            scaledCacheKey = getCacheKey(url, desiredWidth, desiredHeight);
           
            synchronized(mMemoryCache) {
                Bitmap scaledBitmap = mMemoryCache.get(scaledCacheKey);
                if (scaledBitmap != null) {
                    listener.onImageFound(this, scaledBitmap, url, downloadRequest);
                    return;
                }
            }
        }

        // First check the in-memory cache...
        synchronized(mMemoryCache) {
           Bitmap cachedBitmap = mMemoryCache.get(fullSizeCacheKey);
   
           if (cachedBitmap != null) {
               // ...notify listener immediately, no need to go async
               listener.onImageFound(this, cachedBitmap, url, downloadRequest);
               return;
           }
        }

        if (mDiskCache != null) {
           ThreadPoolAsyncTaskRunner.runTaskOnPool(
                 ThreadPoolAsyncTaskRunner.THUMBNAIL_THREAD_POOL,
                 new BitmapDecoderTask(url, listener, downloadRequest),
                 (Object[]) null);
           
            return;
        }
        listener.onImageNotFound(this, url, downloadRequest);
    }
    
    public boolean hasKeyInMemory(String url) {
        synchronized(mMemoryCache) {
            return mMemoryCache.get(getCacheKey(url)) != null;
        }
    }
    
    public boolean hasKeyInMemory(String url, int preferredWidth, int preferredHeight) {
        synchronized(mMemoryCache) {
            return mMemoryCache.get(getCacheKey(url, preferredWidth, preferredHeight)) != null;
        }
    }

    public static String getCacheKey(String url) {
        try {
            return URLEncoder.encode(url, "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    public static String getCacheKey(String url, int imageWidth, int imageHeight) {
       return getCacheKey(String.format(Locale.getDefault(), "%s_%d_%d", url, imageWidth, imageHeight) );
    }
    
    public void remove(String cacheKey) {
        synchronized(mMemoryCache) {
            mMemoryCache.remove(getCacheKey(cacheKey));
        }

        try {
            mDiskCache.remove(getCacheKey(cacheKey));
        } catch ( IOException e ) {
            e.printStackTrace();
        }
    }
    
    public List<Bitmap> removeByPrefix(String cacheKeyPrefix) {
        synchronized(mMemoryCache) {
            List<Bitmap> bitmaps = mMemoryCache.removeByPrefix(getCacheKey(cacheKeyPrefix));
          
            // TODO: Try to remove from disk cache
          
            return bitmaps;
        }
    }
    
    public void clearMemoryCache() {
        synchronized(mMemoryCache) {
            mMemoryCache.evictAll();
        }
    }

    public Snapshot storeToDisk(InputStream inputStream, String cacheKey) {
        try {
            Editor editor = mDiskCache.edit(cacheKey);
            if (editor != null) {
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
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void storeToMemory(Bitmap bitmap, String cacheKey) {
        synchronized(mMemoryCache) {
            mMemoryCache.put(cacheKey, bitmap);
        }
    }
    
    public void onTrimMemory(int level) {
        synchronized(mMemoryCache) {
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
    }
    
    public void onLowMemory() {
        if (mMemoryCache != null) {
            synchronized(mMemoryCache) {
                mMemoryCache.evictAll();
            }
        }
    }

    public void clear() {
        try {
            mDiskCache.delete();
            openDiskCache();
        } catch (IOException e) {
            e.printStackTrace();
        }
        synchronized(mMemoryCache) {
            mMemoryCache.evictAll();
        }
    }

    private class BitmapDecoderTask extends AsyncTask<Object, Void, Bitmap> {
        private String mUrl;
        private ImageCacheListener mListener;
        private DownloadRequest    mDownloadRequest;

        public BitmapDecoderTask(String url, ImageCacheListener listener, DownloadRequest downloadRequest) {
            mUrl = url;
            mListener = listener;
            mDownloadRequest = downloadRequest;
        }

        @Override
        protected Bitmap doInBackground(Object... params) {
            InputStream inStream = null;
            try {
                String scaledCacheKey = getCacheKey(
                      mUrl,
                      mDownloadRequest.getListener().getDesiredWidth(),
                      mDownloadRequest.getListener().getDesiredHeight());
                Snapshot snapshot = mDiskCache.get(scaledCacheKey);
                if (snapshot != null) {
                   inStream = snapshot.getInputStream(0);
                   return Bitmaps.safeDecodeStream(inStream);
                }
                
                snapshot = mDiskCache.get(getCacheKey(mUrl));
                if (snapshot != null) {
                   inStream = snapshot.getInputStream(0);
                    return Bitmaps.safeDecodeStream(snapshot.getInputStream(0));
                }
                
                return null;
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            } finally {
               IO.closeQuietly( inStream );
            }
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            if (result != null) {
                storeToMemory(result, getCacheKey(mUrl));
                mListener.onImageFound(ImageCache.this, result, mUrl, mDownloadRequest);
            } else {
                mListener.onImageNotFound(ImageCache.this, mUrl, mDownloadRequest);
            }
        }

    }
    
    private void openDiskCache() {
        File directory;
        if (android.os.Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED)) {
            directory = new File(android.os.Environment.getExternalStorageDirectory(), "CoachsEye Image Cache");
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
