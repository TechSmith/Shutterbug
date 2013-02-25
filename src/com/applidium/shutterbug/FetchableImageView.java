package com.applidium.shutterbug;

import android.R;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.applidium.shutterbug.utils.ShutterbugManager;
import com.applidium.shutterbug.utils.ShutterbugManager.ShutterbugManagerListener;

public class FetchableImageView extends ImageView implements ShutterbugManagerListener {
    private boolean mScaleImage = false;
    
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
        setImage(url, false, new ColorDrawable(getContext().getResources().getColor(R.color.transparent)));
    }
    
    public void setImage(String url, boolean scaleImageToView) {
        setImage(url, scaleImageToView, null);
    }

    public void setImage(String url, boolean scaleImageToView, int placeholderDrawableId) {
        setImage(url, scaleImageToView, getContext().getResources().getDrawable(placeholderDrawableId));
    }

    public void setImage(String url, boolean scaleImageToView, Drawable placeholderDrawable) {
        mScaleImage = scaleImageToView;
        final ShutterbugManager manager = ShutterbugManager.getSharedImageManager(getContext());
        manager.cancel(this);
        if (placeholderDrawable != null) {
           setImageDrawable(placeholderDrawable);
        }
        if (url != null) {
            manager.download(url, this);
        }
    }

    public void cancelCurrentImageLoad() {
        ShutterbugManager.getSharedImageManager(getContext()).cancel(this);
    }

    @Override
    public void onImageSuccess(ShutterbugManager imageManager, Bitmap bitmap, String url) {
        if (mScaleImage) {
            setImageBitmap(null);
            new DownloadImageTask( getWidth(), getHeight(), bitmap ).execute();
        } else {
            setImageBitmap(bitmap);
        }
        requestLayout();
        if (mListener != null) {
            mListener.onImageFetched(bitmap, url);
        }
    }

    @Override
    public void onImageFailure(ShutterbugManager imageManager, String url) {
        if (mListener != null) {
            mListener.onImageFailure(url);
        }
    }

    public class DownloadImageTask extends AsyncTask<Void, Void, Bitmap> {
       protected int                      mMaxWidth;
       protected int                      mMaxHeight;
       protected Bitmap mBitmap;
       
       public DownloadImageTask( int maxWidth, int maxHeight, Bitmap bitmap ) {
          mMaxWidth = maxWidth;
          mMaxHeight = maxHeight;
          mBitmap = bitmap;
       }

       protected Bitmap doInBackground( Void... args ) {
          Bitmap thumbnail;
          if ( isCancelled() || mBitmap == null ) { return null; }

          if ( mMaxWidth <= 0 && mMaxHeight <= 0 ) {
             thumbnail = mBitmap;
          } else {
             float scaleX = Float.MAX_VALUE;
             float scaleY = Float.MAX_VALUE;

             if ( mMaxWidth > 0 ) {
                scaleX = (float) mMaxWidth / (float) mBitmap.getWidth();
             }

             if ( mMaxHeight > 0 ) {
                scaleY = (float) mMaxHeight / (float) mBitmap.getHeight();
             }

             float scale = Math.min( scaleX, scaleY );

             thumbnail = Bitmap.createScaledBitmap(
                   mBitmap,
                   Math.round( mBitmap.getWidth() * scale ),
                   Math.round( mBitmap.getHeight() * scale ),
                   false );
          }
          
          return thumbnail;
       }
       
       protected void onPostExecute(Bitmap scaledBitmap) {
           setImageBitmap(scaledBitmap);
       }
    }
}
