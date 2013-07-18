package com.applidium.shutterbug.downloader;

import java.io.IOException;
import java.io.InputStream;

import android.content.Context;
import android.os.AsyncTask;

import com.applidium.shutterbug.utils.AssetParser;
import com.applidium.shutterbug.utils.DownloadRequest;

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
                    String relativePath = AssetParser.relativePathForAssetUri(getResourceUrl());
                    
                    if ( relativePath != null ) {
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
                    mListener.onImageOpenFailure( ShutterbugAssetOpener.this, mDownloadRequest );
                }
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
