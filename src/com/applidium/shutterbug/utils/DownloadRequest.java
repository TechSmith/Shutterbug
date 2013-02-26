package com.applidium.shutterbug.utils;

import java.lang.ref.WeakReference;

import com.applidium.shutterbug.utils.ShutterbugManager.ShutterbugManagerListener;

public class DownloadRequest {
    private String                    mUrl;
    private WeakReference<ShutterbugManagerListener> mListener;

    public DownloadRequest(String url, ShutterbugManagerListener listener) {
        mUrl = url;
        mListener = new WeakReference<ShutterbugManagerListener>(listener);
    }

    public String getUrl() {
        return mUrl;
    }

    public ShutterbugManagerListener getListener() {
        return mListener.get();
    }
}
