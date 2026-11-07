package dev.avijit.orbithealth

import android.app.Application

class OrbitApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this).also { it.start() }
    }
}
