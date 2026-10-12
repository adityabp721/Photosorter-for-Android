package com.example.photosorter

import android.app.Application

/**
 * Holds the one [AppModel] for the whole process, so running jobs survive the activity being
 * closed, rotated or recreated. [WorkService] keeps the process alive while a job runs.
 */
class PhotoSorterApp : Application() {

    val model: AppModel by lazy { AppModel(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

val android.content.Context.appModel: AppModel
    get() = (applicationContext as PhotoSorterApp).model
