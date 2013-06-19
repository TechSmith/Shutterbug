package com.applidium.shutterbug.utils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;

import com.applidium.shutterbug.cache.DiskLruCache.Snapshot;
import com.applidium.shutterbug.cache.ImageCache;
import com.applidium.shutterbug.cache.ImageCache.ImageCacheListener;
import com.applidium.shutterbug.downloader.ShutterbugDownloader;
import com.applidium.shutterbug.downloader.ShutterbugDownloader.ShutterbugDownloaderListener;
import com.techsmith.utilities.Bitmaps;
import com.techsmith.utilities.IO;
import com.techsmith.utilities.ThreadPoolAsyncTaskRunner;

public class ShutterbugManager implements ImageCacheListener, ShutterbugDownloaderListener {
    public interface ShutterbugManagerListener {
        void onImageSuccess(ShutterbugManager imageManager, Bitmap bitmap, String url);

        void onImageFailure(ShutterbugManager imageManager, String url);

        int getDesiredWidth();
        
        int getDesiredHeight();
    }

    private static ShutterbugManager          sImageManager;

    private Context                           mContext;
    private List<String>                      mFailedUrls             = Collections.synchronizedList( new ArrayList<String>() );
    private List<ShutterbugManagerListener>   mCacheListeners         = Collections.synchronizedList( new ArrayList<ShutterbugManagerListener>() );
    private List<String>                      mCacheUrls              = Collections.synchronizedList( new ArrayList<String>() );
    private Map<String, ShutterbugDownloader> mDownloadersMap         = new ConcurrentHashMap<String, ShutterbugDownloader>();
    private List<DownloadRequest>             mDownloadRequests       = Collections.synchronizedList( new ArrayList<DownloadRequest>() );
    private List<ShutterbugManagerListener>   mDownloadImageListeners = Collections.synchronizedList( new ArrayList<ShutterbugManagerListener>() );
    private List<ShutterbugDownloader>        mDownloaders            = Collections.synchronizedList( new ArrayList<ShutterbugDownloader>() );

    final static private int                  LISTENER_NOT_FOUND      = -1;

    public ShutterbugManager(Context context) {
        mContext = context;
    }

    public static ShutterbugManager getSharedImageManager(Context context) {
        if (sImageManager == null) {
            sImageManager = new ShutterbugManager(context);
        }
        return sImageManager;
    }

    public void download(String url, ShutterbugManagerListener listener) {
        // TODO: Add an option (per URL?) to enable/disable tracking of failed downloads
        //if (url == null || listener == null || mFailedUrls.contains(url)) {
        if (url == null || listener == null) {
            return;
        }

        mCacheListeners.add(listener);
        mCacheUrls.add(url);
        ImageCache.getSharedImageCache(mContext).queryCache(url, this, new DownloadRequest(url, listener));
    }
    
    public void remove(String url) {
       ImageCache.getSharedImageCache(mContext).remove(url);
    }
    
    public List<Bitmap> removeByPrefix(String urlPrefix) {
       return ImageCache.getSharedImageCache(mContext).removeByPrefix(urlPrefix);
    }

    private int getListenerIndex(ShutterbugManagerListener listener, String url) {
        for (int index = 0; index < mCacheListeners.size(); index++) {
            if (mCacheListeners.get(index) == listener && mCacheUrls.get(index).equals(url)) {
                return index;
            }
        }
        return LISTENER_NOT_FOUND;
    }

    @Override
    public void onImageFound(ImageCache imageCache, Bitmap bitmap, String key, DownloadRequest downloadRequest) {
        final String url = downloadRequest.getUrl();
        final ShutterbugManagerListener listener = downloadRequest.getListener();

        int idx = getListenerIndex(listener, url);
        if (idx == LISTENER_NOT_FOUND) {
            // Request has since been canceled
            return;
        }

        listener.onImageSuccess(this, bitmap, url);
        mCacheListeners.remove(idx);
        mCacheUrls.remove(idx);
    }

    @Override
    public void onImageNotFound(ImageCache imageCache, String key, DownloadRequest downloadRequest) {
        final String url = downloadRequest.getUrl();
        final ShutterbugManagerListener listener = downloadRequest.getListener();

        int idx = getListenerIndex(listener, url);
        if (idx == LISTENER_NOT_FOUND) {
            // Request has since been canceled
            return;
        }
        mCacheListeners.remove(idx);
        mCacheUrls.remove(idx);

        // Share the same downloader for identical URLs so we don't download the
        // same URL several times
        ShutterbugDownloader downloader = mDownloadersMap.get(url);
        if (downloader == null) {
            downloader = new ShutterbugDownloader(this, downloadRequest);
            downloader.start();
            mDownloadersMap.put(url, downloader);
        }
        mDownloadRequests.add(downloadRequest);
        mDownloadImageListeners.add(listener);
        mDownloaders.add(downloader);
    }

    @Override
    public void onImageDownloadSuccess(final ShutterbugDownloader downloader, final InputStream inputStream,
            final DownloadRequest downloadRequest) {

        ThreadPoolAsyncTaskRunner.runTaskOnPool(
              ThreadPoolAsyncTaskRunner.THUMBNAIL_THREAD_POOL,
              new InputStreamHandlingTask(downloader, downloadRequest),
              inputStream);
    }

    @Override
    public void onImageDownloadFailure(ShutterbugDownloader downloader, DownloadRequest downloadRequest) {
        for (int idx = mDownloaders.size() - 1; idx >= 0; idx--) {
            final int uidx = idx;
            ShutterbugDownloader aDownloader = mDownloaders.get(uidx);
            if (aDownloader == downloader) {
                ShutterbugManagerListener listener = mDownloadImageListeners.get(uidx);
                listener.onImageFailure(this, downloadRequest.getUrl());
                mDownloaders.remove(uidx);
                mDownloadImageListeners.remove(uidx);
                mDownloadRequests.remove(uidx);
            }
        }
        mDownloadersMap.remove(downloadRequest.getUrl());

    }

    private class InputStreamHandlingTask extends AsyncTask<Object, Void, Bitmap> {
        ShutterbugDownloader mDownloader;
        DownloadRequest      mDownloadRequest;
        int                  mViewWidth;
        int                  mViewHeight;

        InputStreamHandlingTask(ShutterbugDownloader downloader, DownloadRequest downloadRequest) {
            mDownloader = downloader;
            mDownloadRequest = downloadRequest;
            mViewWidth = downloadRequest.getListener().getDesiredWidth();
            mViewHeight = downloadRequest.getListener().getDesiredHeight();
        }

        @Override
        protected Bitmap doInBackground(Object... params) {
            InputStream inStream = (InputStream) params[0];
            final ImageCache sharedImageCache = ImageCache.getSharedImageCache(mContext);
            final String cacheKey = ImageCache.getCacheKey(mDownloadRequest.getUrl());
            Bitmap bitmap = null;
            if (mDownloadRequest.getUrl().startsWith("http")) {
               // Store the image in the cache
               Snapshot cachedSnapshot = sharedImageCache.storeToDisk(inStream, cacheKey);
               if (cachedSnapshot != null) {
                   bitmap = Bitmaps.safeDecodeStream(cachedSnapshot.getInputStream(0));
                   cachedSnapshot.close();
                   
                   // Disabled for performance reasons. The caller of this AsyncTask will handling caching.
//                   if (bitmap != null) {
//                       sharedImageCache.storeToMemory(bitmap, cacheKey);
//                   }
               }
            } else {
               BitmapFactory.Options inOptions = new BitmapFactory.Options();
               inOptions.inJustDecodeBounds = true;
               BitmapFactory.decodeStream(inStream, null, inOptions);
               
               boolean haveValidViewBounds = mViewWidth > 0 && mViewHeight > 0; 
               float scale = 1f;
               
               while (haveValidViewBounds && 
                     (inOptions.outWidth / scale / 2) >= mViewWidth &&
                     (inOptions.outHeight / scale / 2) >= mViewHeight) {
                  scale *= 2f;
               }
               
               FileInputStream fileInStream = null;
               try {
                  fileInStream = new FileInputStream(mDownloadRequest.getUrl());
                  bitmap = Bitmaps.safeDecodeStream(fileInStream, (int)scale);
               } catch (IOException e) {
                  e.printStackTrace();
               } finally {
                  IO.closeQuietly( fileInStream );
               }
            }

            IO.closeQuietly( inStream );
            
            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            // Notify all the downloadListener with this downloader
            for (int idx = mDownloaders.size() - 1; idx >= 0; idx--) {
                final int uidx = idx;
                ShutterbugDownloader aDownloader = mDownloaders.get(uidx);
                if (aDownloader == mDownloader) {
                    ShutterbugManagerListener listener = mDownloadImageListeners.get(uidx);
                    if (bitmap != null) {
                        listener.onImageSuccess(ShutterbugManager.this, bitmap, mDownloadRequest.getUrl());
                    } else {
                        listener.onImageFailure(ShutterbugManager.this, mDownloadRequest.getUrl());
                    }
                    mDownloaders.remove(uidx);
                    mDownloadImageListeners.remove(uidx);
                    mDownloadRequests.remove(uidx);
                }
            }
            if (bitmap != null) {
            } else { // TODO add retry option
                mFailedUrls.add(mDownloadRequest.getUrl());
            }
            mDownloadersMap.remove(mDownloadRequest.getUrl());
        }

    }

    public void cancel(ShutterbugManagerListener listener) {
        int idx;
        while ((idx = mCacheListeners.indexOf(listener)) != -1) {
            mCacheListeners.remove(idx);
            mCacheUrls.remove(idx);
        }

        while ((idx = mDownloadImageListeners.indexOf(listener)) != -1) {
            ShutterbugDownloader downloader = mDownloaders.get(idx);

            mDownloadRequests.remove(idx);
            mDownloadImageListeners.remove(idx);
            mDownloaders.remove(idx);

            if (!mDownloaders.contains(downloader)) {
                // No more listeners are waiting for this download, cancel it
                downloader.cancel();
                mDownloadersMap.remove(downloader.getUrl());
            }
        }

    }
}
