package com.applidium.shutterbug.downloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import android.os.AsyncTask;

import com.applidium.shutterbug.utils.DownloadRequest;

public class ShutterbugDownloader {
    public interface ShutterbugDownloaderListener {
        void onImageDownloadSuccess(ShutterbugDownloader downloader, InputStream inputStream, DownloadRequest downloadRequest);

        void onImageDownloadFailure(ShutterbugDownloader downloader, DownloadRequest downloadRequest);
    }

    private String                             mUrl;
    private ShutterbugDownloaderListener       mListener;
    private byte[]                             mImageData;
    private DownloadRequest                    mDownloadRequest;
    private final static int                   TIMEOUT = 30000;
    private AsyncTask<Void, Void, InputStream> mCurrentTask;

    public ShutterbugDownloader(String url, ShutterbugDownloaderListener listener, DownloadRequest downloadRequest) {
        mUrl = url;
        mListener = listener;
        mDownloadRequest = downloadRequest;
    }

    public String getUrl() {
        return mUrl;
    }

    public ShutterbugDownloaderListener getListener() {
        return mListener;
    }

    public byte[] getImageData() {
        return mImageData;
    }

    public DownloadRequest getDownloadRequest() {
        return mDownloadRequest;
    }

    public void start() {
        mCurrentTask = new AsyncTask<Void, Void, InputStream>() {

            @Override
            protected InputStream doInBackground(Void... params) {
                return getBitmapUsingPath( mUrl );
            }

            @Override
            protected void onPostExecute(InputStream inputStream) {
                if (isCancelled()) {
                    inputStream = null;
                }

                if (inputStream != null) {
                    mListener.onImageDownloadSuccess(ShutterbugDownloader.this, inputStream, mDownloadRequest);
                } else {
                    mListener.onImageDownloadFailure(ShutterbugDownloader.this, mDownloadRequest);
                }
            }
            
            @SuppressWarnings( "resource" )
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


        }.execute();

    }

    public void cancel() {
        if (mCurrentTask != null) {
            mCurrentTask.cancel(true);
        }
    }
}
