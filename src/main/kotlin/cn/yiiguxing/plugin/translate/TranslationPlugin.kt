package cn.yiiguxing.plugin.translate

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

object TranslationPlugin {

    /**
     * 插件 id, 与 plugin.xml 中的 `<id>` 保持一致.
     * fork 版改用了独立 id, 使 IDE 不再与市场原插件(cn.yiiguxing.plugin.translate)匹配, 避免被提示升级覆盖本地修改.
     */
    const val PLUGIN_ID = "cn.yiiguxing.plugin.translate.fork"

    /**
     * 原插件 id, 仅作为历史存储键前缀使用(密钥/注册表/属性键), 保持不变以兼容原有本地数据.
     */
    const val STORAGE_ID = "cn.yiiguxing.plugin.translate"

    private const val PLUGIN_AD_NAME = "IntelliJ Translation Plugin"

    val descriptor: IdeaPluginDescriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))!!

    val name: String by lazy { descriptor.name }

    val adName: String get() = PLUGIN_AD_NAME

    val version: String by lazy { descriptor.version }

    /**
     * Generate an id with the specified [key].
     * The generated id is in the format of `<STORAGE_ID>.<key>`.
     */
    fun generateId(key: String): String = "$STORAGE_ID.$key"

}