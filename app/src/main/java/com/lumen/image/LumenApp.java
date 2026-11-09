package com.lumen.image;

import android.app.Application;

public class LumenApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 让 WebView / 网络栈不因为证书问题卡住（全本地，无所谓）
        android.webkit.WebView.setWebContentsDebuggingEnabled(false);
    }
}