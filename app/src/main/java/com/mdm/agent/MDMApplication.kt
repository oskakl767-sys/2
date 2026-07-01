package com.mdm.agent

import android.app.Application
import android.util.Log

class MDMApplication : Application() {

    companion object {
        private const val TAG = "MDMApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MDM Agent starting")
    }
}