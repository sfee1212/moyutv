package com.gsoft.mitv;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import com.anymediacloud.iptv.standard.ForceTV;

/* loaded from: classes.dex */
public class MainActivity extends Service {

    /* renamed from: l  reason: collision with root package name */
    public ForceTV f1751l;

    /* renamed from: m  reason: collision with root package name */
    public Binder f1752m;

    public MainActivity() {
        try {
            System.loadLibrary("mitv");
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private native void loadLibrary(int i6);

    @Override // android.app.Service
    public final IBinder onBind(Intent intent) {
        ForceTV obj = new ForceTV();
        this.f1751l = obj;
        try {
            obj.start(9002, 20971520);
        } catch (Throwable unused) {
        }
        return this.f1752m;
    }

    @Override // android.app.Service
    public final void onCreate() {
        super.onCreate();
        try {
            this.f1752m = new Binder();
            loadLibrary(1);
        } catch (Throwable unused) {
        }
    }

    @Override // android.app.Service
    public final boolean onUnbind(Intent intent) {
        ForceTV forceTV = this.f1751l;
        if (forceTV != null) {
            forceTV.stop();
            return false;
        }
        return false;
    }
}
