package com.dl1991.feiyu

import android.app.Application
import com.dl1991.feiyu.dafeiyu.DfyOverlay
import com.dl1991.feiyu.dafeiyu.DfyVoiceConfig
import com.dl1991.feiyu.dafeiyu.DfyWorker
import com.dl1991.feiyu.dafeiyu.FyContentStore
import com.dl1991.feiyu.dafeiyu.FyPlatforms
import com.dl1991.feiyu.llm.FyProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FeiyuApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch(Dispatchers.IO) {
            runCatching { com.dl1991.feiyu.dafeiyu.DfySprites.init(this@FeiyuApp) }
            runCatching { FyProviders.ensure(this@FeiyuApp) }
            runCatching { FyContentStore.ensure(this@FeiyuApp) }
            runCatching { FyPlatforms.ensure(this@FeiyuApp) }
            runCatching { DfyVoiceConfig.get(this@FeiyuApp) }
            // re-arm the B站 life loop (no-op when off) and the floating whale (if enabled)
            runCatching { DfyWorker.sync(this@FeiyuApp) }
            runCatching { kotlinx.coroutines.withContext(Dispatchers.Main) { DfyOverlay.refresh(this@FeiyuApp) } }
        }
    }
}
