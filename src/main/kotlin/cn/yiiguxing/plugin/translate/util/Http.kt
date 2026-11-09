@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package cn.yiiguxing.plugin.translate.util

import cn.yiiguxing.plugin.translate.RegistryKeys
import cn.yiiguxing.plugin.translate.Settings
import cn.yiiguxing.plugin.translate.TranslationPlugin
import com.google.gson.Gson
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.registry.RegistryManager
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.RequestBuilder
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.Type
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import javax.net.ssl.HostnameVerifier

object Http {

    const val PLUGIN_USER_AGENT = "${TranslationPlugin.STORAGE_ID}.TranslationPlugin"

    const val MIME_TYPE_JSON = "application/json"

    const val MIME_TYPE_FORM = "application/x-www-form-urlencoded"

    const val DEFAULT_CHROMIUM_VERSION = "155.0.4283.45"

    const val DEFAULT_MAX_REDIRECTS = 5

    private val REDIRECT_STATUS_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM, // 301 Moved Permanently
        HttpURLConnection.HTTP_MOVED_TEMP, // 302 Found
        307, // Temporary Redirect
        308  // Permanent Redirect
    )

    private val CHROMIUM_VERSION_REGEX = Regex("^\\d+(\\.\\d+){3}$")
    private val DEFAULT_CHROMIUM_VERSION_PARTS = DEFAULT_CHROMIUM_VERSION.split('.')

    val defaultGson = Gson()

    /**
     * 获取插件设置中配置的 HTTP 代理.
     * 仅当代理已启用且主机与端口均有效时返回代理对象, 否则返回 [null], 调用方应走 IDE 默认网络配置.
     * 代理仅作用于本插件的请求, 不影响 IDE 全局代理设置.
     */
    @PublishedApi
    internal fun pluginProxy(): Proxy? {
        val settings = Settings.getInstance()
        if (!settings.proxyEnabled) {
            return null
        }

        val host = settings.proxyHost.trim()
        val port = settings.proxyPort
        if (host.isEmpty() || port !in 1..65535) {
            return null
        }

        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    /**
     * 当插件代理启用时, 通过代理手动执行请求并返回响应文本; 代理未启用时返回 [null], 由调用方回退到平台默认请求路径.
     *
     * 背景: 平台的 [RequestBuilder] 不支持按请求注入自定义 [Proxy](仅有切换 IDE 代理的 [RequestBuilder.useProxy]),
     * 因此代理路径下使用 [ProxiedRequestBuilder] 捕获 init 中的 tuner/UA/超时等配置, 重放到手动创建的连接上.
     *
     * 注意: init 会被应用到临时构建器上, 其中的 tuner 为纯配置回调, 可安全重复应用.
     *
     * @param url 请求地址
     * @param method 请求方法, 如 GET/POST
     * @param contentType 请求体类型, null 表示无请求体
     * @param data 请求体内容, null 表示无请求体
     * @param init 调用方对平台 [RequestBuilder] 的附加配置
     * @return 响应文本; 代理未启用时返回 null
     */
    @PublishedApi
    internal fun proxyExecute(
        url: String,
        method: String,
        contentType: String?,
        data: String?,
        init: RequestBuilder.() -> Unit
    ): String? {
        val proxy = pluginProxy() ?: return null
        val recorder = ProxiedRequestBuilder().apply(init)
        return executeViaProxy(proxy, url, method, contentType, data, recorder)
    }

    /**
     * 通过指定代理手动执行 HTTP 请求, 重放构建器中记录的配置.
     * 自动处理 gzip 响应解压与 4xx/5xx 状态码异常([StatusException]), 同协议重定向由 [HttpURLConnection] 自动跟随.
     */
    private fun executeViaProxy(
        proxy: Proxy,
        url: String,
        method: String,
        contentType: String?,
        data: String?,
        builder: ProxiedRequestBuilder
    ): String {
        // 不调用 disconnect: 保留底层连接给 JDK KeepAliveCache 复用, 避免每次请求重新握手;
        // 流关闭后连接由缓存管理, 无人复用时由 JDK 自行回收
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        builder.connectTimeout?.let { connection.connectTimeout = it }
        builder.readTimeout?.let { connection.readTimeout = it }
        connection.requestMethod = method
        builder.userAgent?.let { connection.setRequestProperty("User-Agent", it) }
        builder.accept?.let { connection.setRequestProperty("Accept", it) }
        connection.setRequestProperty("Accept-Encoding", "gzip")
        builder.tuners.forEach { it.tune(connection) }
        if (data != null) {
            contentType?.let { connection.setRequestProperty("Content-Type", it) }
            connection.doOutput = true
            connection.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
        }

        val statusCode = connection.responseCode
        if (statusCode >= 400) {
            throw StatusException(
                "Request failed with status code $statusCode",
                statusCode,
                url,
                connection.responseMessage,
                connection.getErrorText()
            )
        }

        return readResponseText(connection)
    }

    /**
     * 读取响应体文本, 依据响应头处理 gzip 解压与字符集(缺省 UTF-8).
     */
    private fun readResponseText(connection: HttpURLConnection): String {
        val stream: InputStream = if (connection.contentEncoding == "gzip") {
            GZIPInputStream(connection.inputStream)
        } else {
            connection.inputStream
        }

        return InputStreamReader(stream, responseCharset(connection)).use { it.readText() }
    }

    /**
     * 从 Content-Type 响应头解析字符集, 无法解析时回退 UTF-8.
     */
    private fun responseCharset(connection: HttpURLConnection): Charset {
        val charsetName = connection.contentType
            ?.substringAfter("charset=", "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return try {
            charsetName?.let { Charset.forName(it) } ?: Charsets.UTF_8
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    /**
     * 记录型请求构建器, 仅用于代理路径: 捕获调用方 init 中的配置(tuner/UA/超时等), 不发起真实请求.
     * connect 等执行类方法不可用, 会抛出 [UnsupportedOperationException].
     */
    private class ProxiedRequestBuilder : RequestBuilder() {
        var connectTimeout: Int? = null
            private set
        var readTimeout: Int? = null
            private set
        var userAgent: String? = null
            private set
        var accept: String? = null
            private set
        val tuners = mutableListOf<HttpRequests.ConnectionTuner>()

        override fun connectTimeout(value: Int): RequestBuilder = apply { connectTimeout = value }

        override fun readTimeout(value: Int): RequestBuilder = apply { readTimeout = value }

        override fun redirectLimit(value: Int): RequestBuilder = this

        override fun gzip(value: Boolean): RequestBuilder = this

        override fun forceHttps(value: Boolean): RequestBuilder = this

        override fun useProxy(value: Boolean): RequestBuilder = this

        override fun hostNameVerifier(verifier: HostnameVerifier?): RequestBuilder = this

        override fun userAgent(value: String?): RequestBuilder = apply { userAgent = value }

        override fun productNameAsUserAgent(): RequestBuilder = this

        override fun accept(value: String?): RequestBuilder = apply { accept = value }

        override fun tuner(tuner: HttpRequests.ConnectionTuner?): RequestBuilder = apply {
            if (tuner != null) {
                tuners.add(tuner)
            }
        }

        override fun isReadResponseOnError(value: Boolean): RequestBuilder = this

        override fun throwStatusCodeException(value: Boolean): RequestBuilder = this

        override fun <T> connect(processor: HttpRequests.RequestProcessor<T>): T {
            throw UnsupportedOperationException("ProxiedRequestBuilder does not execute requests")
        }
    }


    inline fun get(url: String, noinline init: RequestBuilder.() -> Unit = {}): String {
        return proxyExecute(url, "GET", null, null, init)
            ?: HttpRequests.request(url)
                .accept(MIME_TYPE_JSON)
                .apply(init)
                .readString()
    }

    inline fun <reified T> request(
        url: String,
        gson: Gson = defaultGson,
        typeOfT: Type = T::class.java,
        noinline init: RequestBuilder.() -> Unit = {}
    ): T {
        val proxiedResult = proxyExecute(url, "GET", null, null, init)
        if (proxiedResult != null) {
            return gson.fromJson(proxiedResult, typeOfT)
        }

        return HttpRequests.request(url)
            .accept(MIME_TYPE_JSON)
            .apply(init)
            .connect { gson.fromJson(it.reader, typeOfT) }
    }

    inline fun <reified T> post(
        url: String,
        vararg dataForm: Pair<String, String>,
        gson: Gson = defaultGson,
        typeOfT: Type = T::class.java,
        noinline init: RequestBuilder.() -> Unit = {}
    ): T {
        val result = post(url, dataForm.toMap(), init)
        return gson.fromJson(result, typeOfT)
    }

    fun post(
        url: String,
        vararg dataForm: Pair<String, String>,
        init: RequestBuilder.() -> Unit = {}
    ): String {
        return post(url, dataForm.toMap(), init)
    }

    fun post(
        url: String,
        dataForm: Map<String, String>,
        init: RequestBuilder.() -> Unit = {}
    ): String {
        val data = getFormUrlEncoded(dataForm)
        return post(url, MIME_TYPE_FORM, data, init)
    }

    inline fun <reified T> postJson(
        url: String,
        data: Any,
        gson: Gson = defaultGson,
        typeOfT: Type = T::class.java,
        noinline init: RequestBuilder.() -> Unit = {}
    ): T {
        val result = postJson(url, data, gson, init)
        return gson.fromJson(result, typeOfT)
    }

    fun postJson(url: String, data: Any, gson: Gson = defaultGson, init: RequestBuilder.() -> Unit = {}): String {
        val json = gson.toJson(data)
        return post(url, MIME_TYPE_JSON, json, init)
    }

    fun post(
        url: String,
        contentType: String,
        data: String,
        init: RequestBuilder.() -> Unit
    ): String {
        return proxyExecute(url, "POST", contentType, data, init)
            ?: HttpRequests.post(url, contentType)
                .accept(MIME_TYPE_JSON)
                .apply(init)
                .send(data) { it.readString() }
    }

    fun HttpRequests.Request.checkResponseCode() {
        val connection = connection as HttpURLConnection
        val responseCode = connection.responseCode
        if (responseCode >= 400) {
            throw StatusException(
                "Request failed with status code $responseCode",
                responseCode,
                url,
                connection.responseMessage,
                connection.getErrorText()
            )
        }
    }

    class StatusException(
        message: String,
        status: Int,
        url: String,
        val responseMessage: String?,
        val errorText: String?
    ) : HttpRequests.HttpStatusException(message, status, url)

    fun getFormUrlEncoded(dataForm: Map<String, String>): String {
        return dataForm.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
    }

    fun <T> RequestBuilder.send(data: String, dataReader: (HttpRequests.Request) -> T): T {
        var builder = this
        var redirectCount = DEFAULT_MAX_REDIRECTS

        while (true) {
            when (val result = builder.sendInternal(data, dataReader)) {
                is SendResult.Redirect -> {
                    if (redirectCount <= 0) {
                        throw IOException("Too many redirects: ${result.url}")
                    }
                    redirectCount--
                    builder = result.rebuildRequest()
                }

                is SendResult.Success -> return result.value
            }
        }
    }

    private fun <T> RequestBuilder.sendInternal(
        data: String,
        dataReader: (HttpRequests.Request) -> T
    ): SendResult<T> {
        throwStatusCodeException(false)
        return connect {
            val connection = it.connection as HttpURLConnection
            val requestHeaders = connection.requestProperties
            val contentType = connection.getRequestProperty("Content-Type")
            it.write(data)
            val redirect = connection.getRedirectOrNull(it.url, requestHeaders, contentType)
            if (redirect != null) {
                redirect
            } else {
                it.checkResponseCode()
                SendResult.Success(dataReader(it))
            }
        }
    }

    private fun HttpURLConnection.getRedirectOrNull(
        requestUrl: String,
        requestHeaders: Map<String, List<String>>,
        contentType: String?
    ): SendResult.Redirect? {
        val statusCode = responseCode
        if (statusCode !in REDIRECT_STATUS_CODES) {
            return null
        }
        return getHeaderField("Location")?.let { location ->
            SendResult.Redirect(
                resolveRedirectUrl(requestUrl, location),
                requestHeaders,
                contentType
            )
        }
    }

    private fun resolveRedirectUrl(baseUrl: String, location: String): String {
        return if (location.contains("://")) {
            location
        } else {
            URL(URL(baseUrl), location).toExternalForm()
        }
    }

    private fun SendResult.Redirect.rebuildRequest(): RequestBuilder {
        return HttpRequests.post(url, contentType)
            .accept(MIME_TYPE_JSON)
            .tuner { connection ->
                headers.forEach { (key, values) ->
                    if (key.equals("Content-Type", ignoreCase = true)) {
                        return@forEach
                    }
                    values.forEach { value -> connection.setRequestProperty(key, value) }
                }
            }
    }

    private sealed interface SendResult<out T> {
        data class Success<T>(val value: T) : SendResult<T>

        data class Redirect(
            val url: String,
            val headers: Map<String, List<String>>,
            val contentType: String?
        ) : SendResult<Nothing>
    }

    fun <T> RequestBuilder.sendForm(dataForm: Map<String, String>, dataReader: (HttpRequests.Request) -> T): T {
        return send(getFormUrlEncoded(dataForm), dataReader)
    }

    fun <T> RequestBuilder.sendJson(data: Any, dataReader: (HttpRequests.Request) -> T): T {
        return send(defaultGson.toJson(data), dataReader)
    }

    private fun HttpURLConnection.getErrorText(): String? {
        val errorStream = errorStream ?: return null
        val stream = if (contentEncoding == "gzip") GZIPInputStream(errorStream) else errorStream
        return InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
    }

    private fun isVersionGreaterThanDefault(version: String): Boolean {
        val versionParts = version.split('.')
        for (index in versionParts.indices) {
            val comparison = compareVersionPart(versionParts[index], DEFAULT_CHROMIUM_VERSION_PARTS[index])
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    private fun compareVersionPart(left: String, right: String): Int {
        val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
        val normalizedRight = right.trimStart('0').ifEmpty { "0" }
        return normalizedLeft.length.compareTo(normalizedRight.length).takeIf { it != 0 }
            ?: normalizedLeft.compareTo(normalizedRight)
    }

    fun getAgentChromiumVersion(): String = RegistryManager.getInstance()
        .stringValue(RegistryKeys.HTTP_AGENT_CHROMIUM_VERSION)
        ?.trim()
        ?.takeIf {
            it.isNotEmpty() && it.matches(CHROMIUM_VERSION_REGEX) && isVersionGreaterThanDefault(it)
        }
        ?: DEFAULT_CHROMIUM_VERSION

    fun getUserAgent(): String {
        val chromiumMajorVersion = getAgentChromiumVersion().substringBefore('.').toInt()
        val chrome = "Chrome/$chromiumMajorVersion.0.0.0"
        val edge = "Edg/$chromiumMajorVersion.0.0.0"
        val safari = "Safari/537.36"
        val appleWebKit = "AppleWebKit/537.36"
        val mozilla = "Mozilla/5.0"
        val systemInfo = "Windows NT ${if (SystemInfoRt.isWindows) SystemInfoRt.OS_VERSION else "10.0"}; Win64; x64"
        return "$mozilla ($systemInfo) $appleWebKit (KHTML, like Gecko) $chrome $safari $edge"
    }

    fun RequestBuilder.setUserAgent(): RequestBuilder = apply { userAgent(getUserAgent()) }

    fun RequestBuilder.pluginUserAgent(): RequestBuilder = apply { userAgent(PLUGIN_USER_AGENT) }
}