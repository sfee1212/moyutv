package com.forcetech.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import com.forcetech.android.ForceTV;

/* loaded from: classes.dex */
public abstract class PxPService extends Service {

    /* renamed from: l  reason: collision with root package name */
    public ForceTV f1749l;

    /* renamed from: m  reason: collision with root package name */
    public Binder f1750m;

    public abstract int a();

    @Override // android.app.Service
    public final IBinder onBind(Intent intent) {
        ForceTV obj = new ForceTV();
        this.f1749l = obj;
        String stringExtra = intent.getStringExtra("scheme");
        int a6 = a();
        try {
            System.loadLibrary(stringExtra);
            obj.start(a6, 20971520);
        } catch (Throwable unused) {
        }
        return this.f1750m;
    }

    @Override // android.app.Service
    public final void onCreate() {
        super.onCreate();
        this.f1750m = new Binder();
    }

    @Override // android.app.Service
    public final boolean onUnbind(Intent intent) {
        ForceTV forceTV = this.f1749l;
        if (forceTV != null) {
            forceTV.stop();
            return false;
        }
        return false;
    }
}
