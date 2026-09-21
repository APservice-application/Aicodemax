package com.aicodemax.app

import android.app.Application
import com.aicodemax.core.common.LoggerFactory
import com.aicodemax.core.resources.AndroidLogger

class AicodeApp : Application() {
    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        LoggerFactory.current = AndroidLogger()
        services = ServiceLocator(this)
    }
}
