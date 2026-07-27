package com.gennakersystems.locshare

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class LocShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Config.isConfigured) {
            val options = FirebaseOptions.Builder()
                .setProjectId(Config.FIREBASE_PROJECT_ID)
                .setApplicationId(Config.FIREBASE_APP_ID)
                .setApiKey(Config.FIREBASE_API_KEY)
                .setDatabaseUrl(Config.DATABASE_URL)
                .build()
            FirebaseApp.initializeApp(this, options)
        }
        ShareState.load(this)
    }
}
