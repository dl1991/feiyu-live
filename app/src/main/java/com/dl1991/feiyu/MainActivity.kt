package com.dl1991.feiyu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dl1991.feiyu.dafeiyu.DfyLive
import com.dl1991.feiyu.dafeiyu.DfyLiveRoomScreen
import com.dl1991.feiyu.dafeiyu.DfyRoutes
import com.dl1991.feiyu.dafeiyu.DfySecrets
import com.dl1991.feiyu.dafeiyu.EvType
import com.dl1991.feiyu.dafeiyu.FyDirector
import com.dl1991.feiyu.dafeiyu.FyPlatforms
import com.dl1991.feiyu.dafeiyu.LiveEvent
import com.dl1991.feiyu.dafeiyu.LivePlatform
import com.dl1991.feiyu.dafeiyu.SurfMode
import com.dl1991.feiyu.dafeiyu.dafeiyuGraph
import com.dl1991.feiyu.screens.ContentScreen
import com.dl1991.feiyu.screens.DirectorScreen
import com.dl1991.feiyu.screens.GrowthScreen
import com.dl1991.feiyu.screens.HomeScreen
import com.dl1991.feiyu.screens.LifeVoiceScreen
import com.dl1991.feiyu.screens.LiveConnectScreen
import com.dl1991.feiyu.screens.PersonaScreen
import com.dl1991.feiyu.screens.VoiceScreen
import com.dl1991.feiyu.ui.FyTheme
import com.dl1991.feiyu.ui.FyTokens
import com.dl1991.feiyu.ui.FyType
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val HOME = "tab/home"
    const val LIFE = "tab/life"
    const val CONNECT = "tab/connect"
    const val CONTENT = "tab/content"
    const val PERSONA = "fy/persona"
    const val GROWTH = "fy/growth"
    val TABS = listOf(HOME to "首页", LIFE to "人生&声音", CONNECT to "直播接入", CONTENT to "直播内容")

    /** feiyu://app/<name> → route. */
    fun forUri(u: Uri?): String? {
        if (u == null || u.scheme != "feiyu" || u.host != "app") return null
        return when (u.path?.trim('/')) {
            "", "home" -> HOME; "life" -> LIFE; "connect", "prelive" -> CONNECT; "content" -> CONTENT
            "director" -> DfyRoutes.DIRECTOR; "growth" -> GROWTH; "live" -> DfyRoutes.LIVE; "voice" -> DfyRoutes.VOICE; "persona" -> PERSONA
            "her", "life-home" -> DfyRoutes.HOME; "chat" -> DfyRoutes.CHAT; "memory" -> DfyRoutes.MEMORY; "knowledge" -> DfyRoutes.KNOWLEDGE
            "timeline" -> DfyRoutes.TIMELINE; "viewers" -> DfyRoutes.VIEWERS; "settings" -> DfyRoutes.SETTINGS; "pending" -> DfyRoutes.PENDING
            else -> HOME
        }
    }
}

class MainActivity : ComponentActivity() {
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            FyTheme {
                val nav = rememberNavController()
                val route by pendingRoute.collectAsState()
                LaunchedEffect(route) {
                    val r = route ?: return@LaunchedEffect
                    pendingRoute.value = null
                    go(nav, r)
                }
                AppShell(nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        if (BuildConfig.DEBUG) ciHooks(i)
        (i.getStringExtra("ci_route") ?: Routes.forUri(i.data))?.let { pendingRoute.value = it }
    }

    /**
     * Debug-only launch extras for the emulator smoke test:
     *  ci_demo=true  seeds a fake 抖音 endpoint (never pushed) + a B站 room id so 直播接入 shows filled state
     *  ci_live=true  goes live locally with simulated viewers (fast crowd)
     *  ci_director=card|pin|standby|clear  exercises the 导播台 during the live run
     */
    private fun ciHooks(i: Intent) {
        val ctx = applicationContext
        if (i.getBooleanExtra("ci_demo", false)) {
            FyPlatforms.ensure(ctx)
            if (!DfySecrets.has(ctx, LivePlatform.DOUYIN.keyRef)) FyPlatforms.save(ctx, LivePlatform.DOUYIN, "rtmp://push-rtmp-l6.douyincdn.com/stage", "ci-demo-not-a-real-key")
            FyPlatforms.setSources(ctx, FyPlatforms.sources.value.copy(sim = true, simIntensity = 4, biliRoom = 6))
            FyPlatforms.setChecklist(ctx, setOf(0, 1))
        }
        if (i.getBooleanExtra("ci_live", false) && !DfyLive.isLive()) {
            FyPlatforms.setSources(ctx, FyPlatforms.sources.value.copy(sim = true, simIntensity = 5))
            DfyLive.start(ctx, SurfMode.SURF, "鲸鱼为什么会唱歌", setOf("sim"))
        }
        when (i.getStringExtra("ci_director")) {
            "card" -> FyDirector.pushTopicCard("你见过最大的动物是什么？")
            "pin" -> FyDirector.pin(LiveEvent(EvType.COMMENT, "sim:3", "鲸鱼饲养员", "鱼鱼能讲讲座头鲸的歌吗？", source = "sim"))
            "standby" -> FyDirector.setStandby(true, "去倒杯水，马上回来～")
            "clear" -> { FyDirector.setStandby(false); FyDirector.clearTopicCard(); FyDirector.unpin() }
        }
    }
}

private fun go(nav: NavHostController, r: String) {
    if (r in Routes.TABS.map { it.first }) nav.navigate(r) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true }
    else nav.navigate(r) { launchSingleTop = true }
}

@Composable
private fun AppShell(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showTabs = current == null || current in Routes.TABS.map { it.first }
    val back: () -> Unit = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) }
    val open: (String) -> Unit = { r -> go(nav, r) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { HomeScreen(open) }
                composable(Routes.LIFE) { LifeVoiceScreen(open) }
                composable(Routes.CONNECT) { LiveConnectScreen(null, open) }
                composable(Routes.CONTENT) { ContentScreen(null, open) }
                composable(DfyRoutes.PRELIVE) { LiveConnectScreen(back, open) }
                composable(DfyRoutes.VOICE) { VoiceScreen(back) }
                composable(DfyRoutes.DIRECTOR) { DirectorScreen(back, open) }
                composable(DfyRoutes.LIVE) { DfyLiveRoomScreen(back, open) }
                composable(Routes.PERSONA) { PersonaScreen(back) }
                composable(Routes.GROWTH) { GrowthScreen(back) }
                dafeiyuGraph(nav)
            }
        }
        if (showTabs) TabBar(current ?: Routes.HOME) { open(it) }
    }
}

@Composable
private fun TabBar(current: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(thickness = 1.dp, color = FyTokens.hairline())
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            Routes.TABS.forEachIndexed { i, (route, label) ->
                val sel = route == current
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { onSelect(route) }.semantics { contentDescription = label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (sel) FyTokens.accent() else androidx.compose.ui.graphics.Color.Transparent))
                    Spacer(Modifier.height(8.dp))
                    Text("0${i + 1}", fontFamily = FyType.Mono, fontSize = 10.sp, color = if (sel) FyTokens.accent() else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label, fontSize = 12.5.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (i < Routes.TABS.lastIndex) Box(Modifier.width(1.dp).fillMaxSize().padding(vertical = 14.dp).background(FyTokens.hairline()))
            }
        }
    }
}
