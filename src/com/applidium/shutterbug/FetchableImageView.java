package com.applidium.shutterbug;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.applidium.shutterbug.cache.DiskLruCache.Snapshot;
import com.applidium.shutterbug.cache.ImageCache;
import com.applidium.shutterbug.utils.ShutterbugManager;
import com.applidium.shutterbug.utils.ShutterbugManager.ShutterbugManagerListener;
import com.techsmith.android.cloudsdk.common.ThreadPoolAsyncTaskRunner;
import com.techsmith.cloudsdk.IO;
import com.techsmith.utilities.Bitmaps;
import com.techsmith.utilities.XLog;

public class FetchableImageView extends ImageView implements ShutterbugManagerListener {
    private boolean  mGreyScale = false;
    private boolean  mScaleImage = false;
    private Drawable mFailureDrawable;
    private String   mCurrentUrl;
    
    public interface FetchableImageViewListener {
        void onImageFetched(Bitmap bitmap, String url);

        void onImageFailure(String url);
    }

    private FetchableImageViewListener mListener;

    public FetchableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FetchableImageViewListener getListener() {
        return mListener;
    }

    public void setListener(FetchableImageViewListener listener) {
        mListener = listener;
    }

    public void setImage(String url) {
        Drawable transDrawable = new ColorDrawable(getContext().getResources().getColor(android.R.color.transparent));
        setImage(url, false, transDrawable, null);
    }
    
    public void setImage(String url, boolean scaleImageToView) {
        setImage(url, scaleImageToView, null, null);
    }

    public void setImage(String url, boolean scaleImageToView, int placeholderDrawableId) {
        setImage(url, scaleImageToView, getContext().getResources().getDrawable(placeholderDrawableId), null);
    }
    
    public void setImage(String url, boolean scaleImageToView, Drawable placeholderDrawable, Drawable failureDrawable) {
      setImage( url, scaleImageToView, placeholderDrawable, failureDrawable, false );
    }

    public void setImage(String url, boolean scaleImageToView, Drawable placeholderDrawable, Drawable failureDrawable, boolean greyScale) {
        boolean urlIsCached = ImageCache.getSharedImageCache(getContext()).hasKeyInMemory(url, getWidth(), getHeight());
        if (!url.equals(mCurrentUrl) || !urlIsCached) {
           mCurrentUrl = url;
           mScaleImage = scaleImageToView;
           mFailureDrawable = failureDrawable;
           mGreyScale = greyScale;
           final ShutterbugManager manager = ShutterbugManager.getSharedImageManager(getContext());
           manager.cancel(this);
           if (placeholderDrawable != null) {
              setImageDrawable(placeholderDrawable);
           }
           if (url != null) {
               manager.download(url, this);
           }
        }
    }

    public void cancelCurrentImageLoad() {
        ShutterbugManager.getSharedImageManager(getContext()).cancel(this);
    }

    @Override
    public void onImageSuccess(ShutterbugManager imageManager, Bitmap bitmap, String url) {
        boolean imageNeedsToBeRescaled = getWidth() < bitmap.getWidth() && getHeight() < bitmap.getHeight();
        
        if (mScaleImage && getWidth() > 0 && getHeight() > 0 && imageNeedsToBeRescaled) {
            setImageBitmap(null);
            ThreadPoolAsyncTaskRunner.runTaskOnPool(
                  ThreadPoolAsyncTaskRunner.THUMBNAIL_THREAD_POOL,
                  new ScaleImageTask(url, getWidth(), getHeight(), bitmap),
                  (Object[]) null);
        } else {
            if (mGreyScale) {
               bitmap = getGrayscaleBitmap(bitmap);
               setImageBitmap(bitmap);
            } else {
               setImageBitmap(bitmap);
            }

            ImageCache.getSharedImageCache(getContext()).storeToMemory(bitmap, ImageCache.getCacheKey(url));
        }

        if (mListener != null) {
            mListener.onImageFetched(bitmap, url);
        }
    }

    @Override
    public void onImageFailure(ShutterbugManager imageManager, String url) {
        if (mListener != null) {
            mListener.onImageFailure(url);
        }
        if (mFailureDrawable != null) {
           setImageDrawable(mFailureDrawable);
        }
    }

    public class ScaleImageTask extends AsyncTask<Object, Void, Bitmap> {
       protected String                   mUrl;
       protected int                      mMaxWidth;
       protected int                      mMaxHeight;
       protected Bitmap                   mBitmap;
       
       public ScaleImageTask(String url, int maxWidth, int maxHeight, Bitmap bitmap) {
          mUrl = url;
          mMaxWidth = maxWidth;
          mMaxHeight = maxHeight;
          mBitmap = bitmap;
       }

       protected Bitmap doInBackground(Object... args) {
          Bitmap thumbnail = null;
          if (isCancelled() || mBitmap == null) { return null; }

          if (mMaxWidth <= 0 && mMaxHeight <= 0) {
             thumbnail = mBitmap;
          } else {
             thumbnail = Bitmaps.safeCenterCrop(mBitmap, mMaxWidth, mMaxHeight);
          }
          
          if (thumbnail != null) {
             ImageCache imageCache = ImageCache.getSharedImageCache(getContext());
             imageCache.storeToMemory(thumbnail, ImageCache.getCacheKey(mUrl, getWidth(), getHeight()));
             
             if (mUrl.startsWith("http")) {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                thumbnail.compress(CompressFormat.JPEG, 100, stream);
                InputStream inStream = new ByteArrayInputStream(stream.toByteArray());
                Snapshot snapshot = imageCache.storeToDisk(inStream, ImageCache.getCacheKey(mUrl, getWidth(), getHeight()));
                IO.closeQuietly( inStream );
                if (snapshot != null) {
                    snapshot.close();
                }
             }
          } else {
             XLog.x(this, "Image from %s was null", mUrl);
          }


          return thumbnail;
       }
       
       protected void onPostExecute(Bitmap scaledBitmap) {           
           if (mGreyScale) {
              scaledBitmap = getGrayscaleBitmap(scaledBitmap);
           }
           setImageBitmap(scaledBitmap);
       }
    }
    
    static public Bitmap getGrayscaleBitmap(Bitmap bmpOriginal) {
       int height = bmpOriginal.getHeight();
       int width  = bmpOriginal.getWidth();

       Bitmap bmpGrayscale = Bitmaps.safeCreateBitmap(width, height, Bitmap.Config.RGB_565);
       Canvas c = new Canvas(bmpGrayscale);
       Paint paint = new Paint();
       ColorMatrix cm = new ColorMatrix();
       cm.setSaturation(0);
       ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
       paint.setColorFilter(f);
       c.drawBitmap(bmpOriginal, 0, 0, paint);
       return bmpGrayscale;
    }
    
    @Override
    public int getDesiredHeight() {
       return getHeight();
    }

    @Override
    public int getDesiredWidth() {
       return getWidth();
    }
}
