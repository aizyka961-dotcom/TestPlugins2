package com.animefox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeFoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeFoxProvider())
    }
}
