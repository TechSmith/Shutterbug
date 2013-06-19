package com.applidium.shutterbug.downloader;

import java.io.IOException;
import java.io.InputStream;

import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;

import com.applidium.shutterbug.utils.DownloadRequest;
import com.applidium.shutterbug.utils.ShutterbugManager;
import com.techsmith.utilities.XLog;

public class ShutterbugAssetOpener implements ShutterbugStreamOpener {

    private Context                            mContext;
    private ShutterbugOnOpenedListener         mListener;
    private DownloadRequest                    mDownloadRequest;
    private AsyncTask<Void, Void, InputStream> mCurrentTask;

    public ShutterbugAssetOpener( Context context, ShutterbugOnOpenedListener listener, DownloadRequest request ) {
        mContext = context;
        mListener = listener;
        mDownloadRequest = request;
    }

    @Override
    public void start() {
        mCurrentTask = new AsyncTask<Void, Void, InputStream>() {

            @Override
            protected InputStream doInBackground(Void... params) {
                InputStream in = null;
                
                try {
                    Uri assetUri = Uri.parse( getResourceUrl() );
                    
                    String absPath = assetUri.getPath();
                    if ( absPath.length() > 1 ) {
                        String relativePath = absPath.substring( 1, absPath.length() );
                        in = mContext.getAssets().open( relativePath );
                    }
                    
                } catch ( IOException e ) {
                    e.printStackTrace();
                }
                
                return in;
            }

            @Override
            protected void onPostExecute( InputStream result ) {
                if ( isCancelled() ) {
                    result = null;
                }
                
                if ( result != null ) {
                    mListener.onImageOpenSuccess( ShutterbugAssetOpener.this, result, mDownloadRequest );
                } else {
                    mListener.onImageOpenFailure( ShutterbugAssetOpener.this, mDownloadRequest );                }
            }
        };
        
        mCurrentTask.execute(); // TODO common executor
    }

    @Override
    public void cancel() {
        if ( mCurrentTask != null ) {
            mCurrentTask.cancel( true );
        }
    }

    @Override
    public String getResourceUrl() {
        return mDownloadRequest.getUrl();
    }

}
