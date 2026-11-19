package com.presentationglass.app

import android.app.Application

class PresenterApp : Application() {
  override fun onCreate() {
    super.onCreate()
    Presenter.init(this)
  }
}
