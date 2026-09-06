package com.alphapi.codexremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    val repository = RemoteRepository.get(application)
    val state = repository.state

    init {
        repository.restoreAndStart()
    }

    fun pair(url: String, code: String, deviceName: String, connectionName: String) {
        repository.pair(url, code, deviceName, connectionName)
    }
}
