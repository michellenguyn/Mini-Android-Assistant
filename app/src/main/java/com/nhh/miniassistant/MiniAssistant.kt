package com.nhh.miniassistant

import android.app.Application
import com.nhh.miniassistant.data.ObjectBoxStore
import com.nhh.miniassistant.di.AppModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.ksp.generated.module
import org.koin.ksp.generated.defaultModule

class MiniAssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MiniAssistantApp)
            modules(AppModule().module, defaultModule )
        }
        ObjectBoxStore.init(this)
    }
}