package com.applidium.shutterbug;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.applidium.shutterbug.cache.ImageCache;
import com.applidium.shutterbug.utils.ShutterbugManager;
import com.applidium.shutterbug.utils.ShutterbugManager.ShutterbugManagerListener;
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

    @Override
    public void onImageSuccess(ShutterbugManager imageManager, Bitmap bitmap, String url) {
        if (!url.equals(mCurrentUrl)) {
            XLog.x(this, "URL mismatch; skipping");
            return;
        }
        
        if (mGreyScale) {
            bitmap = getGrayscaleBitmap(bitmap);
        }
        setImageBitmap(bitmap);

        if (mListener != null) {
            mListener.onImageFetched(bitmap, url);
        }
    }

    @Override
    public void onImageFailure(ShutterbugManager imageManager, String url) {
        if (url.equals(mCurrentUrl)) {
            if (mListener != null) {
                mListener.onImageFailure(url);
            }
            if (mFailureDrawable != null) {
                setImageDrawable(mFailureDrawable);
            }
           
            mCurrentUrl = "";
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
