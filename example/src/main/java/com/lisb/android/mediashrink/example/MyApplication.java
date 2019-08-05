package com.lisb.android.mediashrink.example;

import android.app.Application;

import timber.log.Timber;

public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Timber.plant(new Timber.DebugTree());
    }
}
