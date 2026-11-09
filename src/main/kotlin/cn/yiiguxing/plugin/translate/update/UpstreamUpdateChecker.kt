package cn.yiiguxing.plugin.translate.update

import cn.yiiguxing.plugin.translate.Settings
import cn.yiiguxing.plugin.translate.TranslationPlugin
import cn.yiiguxing.plugin.translate.activity.BaseStartupActivity
import cn.yiiguxing.plugin.translate.message
import cn.yiiguxing.plugin.translate.util.Http
import cn.yiiguxing.plugin.translate.util.d
import cn.yiiguxing.plugin.translate.util.w
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import icons.TranslationIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 上游(官方仓库 YiiGuxing/TranslationPlugin)版本检查启动活动.
 *
 * <p>仅负责拉起 [UpstreamUpdateService] 周期调度(应用级服务懒加载, 需要显式触发实例化).
 */
class UpstreamUpdateChecker : BaseStartupActivity(true, false) {

    override suspend fun onRunActivity(project: Project) {
        service<UpstreamUpdateService>()
    }
}

/**
 * 上游(官方仓库 YiiGuxing/TranslationPlugin)版本周期检查服务.
 *
 * <p>fork 版使用了独立插件 id, IDE 不再与市场官方插件匹配, 因此收不到官方升级提示.
 * 本服务按设置面板配置的间隔轮询官方 GitHub 最新 Release, 与本地 fork 基线版本比较,
 * 发现官方有更新时弹通知, 提醒前往 GitHub 合并上游代码并重新发版.
 *
 * <p>约束与行为:
 * <ul>
 * <li>周期与开关读取自设置([Settings.upstreamUpdateCheckEnabled]/[Settings.upstreamUpdateCheckIntervalHours]),
 *     修改于下一个检测周期生效</li>
 * <li>版本比较只取稳定三元组(major.minor.patch), 忽略 SNAPSHOT 等预发布段,
 *     否则带预发布后缀的本地版本会永远被判定为落后</li>
 * <li>同一官方版本只通知一次, 记录于 PropertiesComponent</li>
 * <li>网络失败静默(仅记录日志), 不影响任何功能</li>
 * </ul>
 */
@Service(Service.Level.APP)
class UpstreamUpdateService(private val scope: CoroutineScope) {

    init {
        start()
    }

    /**
     * 立即执行一次上游检查(手动触发, 不受开关与间隔限制).
     * 无更新时弹出提示告知当前状态; 失败静默记日志.
     */
    fun checkNow() {
        scope.launch(Dispatchers.IO) {
            try {
                checkUpstreamUpdate(manual = true)
            } catch (e: Exception) {
                LOG.w("Upstream manual update check failed: ${e.message}")
            }
        }
    }

    /**
     * 启动周期检查循环: 首次延迟后执行检查, 之后按当前设置的间隔循环.
     */
    private fun start() {
        scope.launch(Dispatchers.IO) {
            delay(INITIAL_DELAY_MILLIS)
            while (isActive) {
                val settings = Settings.getInstance()
                if (settings.upstreamUpdateCheckEnabled) {
                    try {
                        checkUpstreamUpdate()
                    } catch (e: Exception) {
                        LOG.w("Upstream update check failed: ${e.message}")
                    }
                } else {
                    LOG.d("Upstream update check disabled.")
                }

                delay(settings.upstreamUpdateCheckIntervalHours * 3_600_000L)
            }
        }
    }

    private fun checkUpstreamUpdate(manual: Boolean = false) {
        val tagName = Http.defaultGson
            .fromJson(Http.get(UPSTREAM_LATEST_RELEASE_API), ReleaseInfo::class.java)
            .tagName
        if (tagName == null) {
            LOG.d("Upstream update check: no release tag found.")
            return
        }

        val upstreamVersion = try {
            Version(tagName.removePrefix("v"))
        } catch (e: Exception) {
            LOG.d("Upstream update check: unrecognized tag: $tagName")
            return
        }
        val localVersion = Version.current()

        // 只比较稳定三元组, 预发布段(SNAPSHOT/beta)一律忽略
        val upstreamStable = upstreamVersion.getStableVersion()
        val hasNewerUpstream = Version(upstreamStable) > Version(localVersion.getStableVersion())
        val properties = PropertiesComponent.getInstance()
        if (!hasNewerUpstream || properties.getValue(NOTIFIED_VERSION_KEY) == upstreamStable) {
            if (manual) {
                showUpToDateNotification(upstreamStable, localVersion.getStableVersion())
            }
            return
        }

        showUpstreamUpdateNotification(upstreamStable, localVersion.getStableVersion())
        properties.setValue(NOTIFIED_VERSION_KEY, upstreamStable)
    }

    /**
     * 手动检查无更新时的提示.
     */
    private fun showUpToDateNotification(upstream: String, local: String) {
        val content = message("upstream.update.notification.upToDate", upstream, local)
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(content, NotificationType.INFORMATION)
            .setTitle(message("upstream.update.notification.title"))
            .setIcon(TranslationIcons.Logo)
            .also(::notifyAppLevel)
    }

    /**
     * 弹出官方有新版本的提醒.
     */
    private fun showUpstreamUpdateNotification(upstream: String, local: String) {
        val content = message(
            "upstream.update.notification.message",
            upstream,
            local,
            UPSTREAM_RELEASES_URL
        )
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(content, NotificationType.INFORMATION)
            .setTitle(message("upstream.update.notification.title"))
            .setIcon(TranslationIcons.Logo)
            .addAction(BrowseUpstreamReleasesAction())

        notifyAppLevel(notification)
    }

    /**
     * 应用级通知派发: 优先绑定第一个打开的项目以保证气泡能正常显示,
     * 无项目时仍可派发(仅留档通知面板).
     */
    private fun notifyAppLevel(notification: Notification) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        notification.notify(project)
    }

    private class BrowseUpstreamReleasesAction :
        AnAction(message("upstream.update.action.view.releases")) {

        override fun actionPerformed(e: AnActionEvent) {
            BrowserUtil.browse(UPSTREAM_RELEASES_URL)
        }
    }

    private data class ReleaseInfo(val tagName: String?)

    companion object {
        internal const val NOTIFICATION_GROUP_ID = "Translation Upstream Update"

        /** 首次检查延迟, 避开 IDE 启动高峰 */
        private const val INITIAL_DELAY_MILLIS = 30_000L

        private const val UPSTREAM_LATEST_RELEASE_API =
            "https://api.github.com/repos/YiiGuxing/TranslationPlugin/releases/latest"

        private const val UPSTREAM_RELEASES_URL =
            "https://github.com/YiiGuxing/TranslationPlugin/releases"

        private const val NOTIFIED_VERSION_KEY = "${TranslationPlugin.STORAGE_ID}.upstream.version.notified"

        private val LOG = Logger.getInstance(UpstreamUpdateService::class.java)
    }
}
