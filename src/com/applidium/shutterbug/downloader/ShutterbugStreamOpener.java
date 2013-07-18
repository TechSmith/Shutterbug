package com.applidium.shutterbug.downloader;

import java.io.InputStream;

import com.applidium.shutterbug.utils.DownloadRequest;

public interface ShutterbugStreamOpener {
    public interface ShutterbugOnOpenedListener {
        void onImageOpenSuccess(ShutterbugStreamOpener downloader, InputStream inputStream, DownloadRequest downloadRequest);
        void onImageOpenFailure(ShutterbugStreamOpener downloader, DownloadRequest downloadRequest);
    }
    
    void start();
    void cancel();
    String getResourceUrl();
}
