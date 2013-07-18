package com.applidium.shutterbug.downloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import android.os.AsyncTask;

import com.applidium.shutterbug.utils.DownloadRequest;

public class ShutterbugDownloader implements ShutterbugStreamOpener {
    private static final int                   DOWNLOAD_THREADPOOL_SIZE = 5;
    private final static int                   TIMEOUT = 30000;
    
    private static Executor                    sDownloadExecutor;

    private ShutterbugOnOpenedListener         mListener;
    private DownloadRequest                    mDownloadRequest;
    private AsyncTask<Void, Void, InputStream> mCurrentTask;

    public ShutterbugDownloader(ShutterbugOnOpenedListener listener, DownloadRequest downloadRequest) {
        mListener = listener;
        mDownloadRequest = downloadRequest;
    }

    @Override
    public String getResourceUrl() {
        return mDownloadRequest.getUrl();
    }

    @Override
    public void start() {
        if (sDownloadExecutor == null) {
            sDownloadExecutor = Executors.newFixedThreadPool(DOWNLOAD_THREADPOOL_SIZE);
        }

        mCurrentTask = new AsyncTask<Void, Void, InputStream>() {

            @Override
            protected InputStream doInBackground(Void... params) {
                return getBitmapUsingPath( mDownloadRequest.getUrl() );
            }

            @Override
            protected void onPostExecute(InputStream inputStream) {
                if (isCancelled()) {
                    inputStream = null;
                }

                if (inputStream != null) {
                    mListener.onImageOpenSuccess(ShutterbugDownloader.this, inputStream, mDownloadRequest);
                } else {
                    mListener.onImageOpenFailure(ShutterbugDownloader.this, mDownloadRequest);
                }
            }
            
            @SuppressWarnings("resource")
            private InputStream getBitmapUsingPath( String pathToImage ) {
               InputStream in = null;
               try {
                  if ( pathToImage.startsWith( File.separator ) ) {
                     File bitmapFile = new File( pathToImage );
                     in = new FileInputStream( bitmapFile );
                  } else {
                     URL imageUrl = new URL(pathToImage);
                     HttpURLConnection connection = (HttpURLConnection) imageUrl.openConnection();
                     connection.setConnectTimeout(TIMEOUT);
                     connection.setReadTimeout(TIMEOUT);
                     connection.setInstanceFollowRedirects(true);
                     in = connection.getInputStream();
                  }
               } catch ( IOException e ) {
                  e.printStackTrace();
               }

               return in;
            }


        }.executeOnExecutor(sDownloadExecutor);

    }

    @Override
    public void cancel() {
        if (mCurrentTask != null) {
            mCurrentTask.cancel(true);
        }
    }
}
