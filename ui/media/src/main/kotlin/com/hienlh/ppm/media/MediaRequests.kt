package com.hienlh.ppm.media

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.hienlh.ppm.client.ApiClient
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Only our raw/fMP4 endpoints; credentials never enter Media3's URI or diagnostic logs. */
internal fun mediaRequest(api: ApiClient, url: HttpUrl): Request {
    val base = api.baseUrl
    val prefix = base.encodedPath.trimEnd('/') + "/api/"
    val route = url.encodedPath.removePrefix(prefix)
    val allowedRoute = route == "fs/raw" || route == "fs/transcode" ||
        Regex("project/[^/]+/files/(raw|transcode)").matches(route)
    require(url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
        url.encodedPath.startsWith(prefix) && allowedRoute) { "Invalid media source." }
    return Request.Builder().url(url.newBuilder().removeAllQueryParameters("token").build())
        .apply { if (api.token.isNotEmpty()) header("Authorization", "Bearer ${api.token}") }.build()
}

/** Kept separate for a wire test. OkHttpDataSource skips locally on a 200 response to a seek. */
internal class WholeMediaResponse : Interceptor {
    override fun intercept(chain: Interceptor.Chain) = chain.proceed(chain.request().newBuilder()
        .removeHeader("Range").removeHeader("Accept-Encoding").header("Accept-Encoding", "identity").build())
}

internal val mediaHttp: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .readTimeout(30, TimeUnit.SECONDS)
    .addInterceptor(WholeMediaResponse())
    .build()

internal fun mediaStatus(error: Throwable): Int? {
    var cause: Throwable? = error
    val seen = mutableSetOf<Throwable>()
    while (cause != null && seen.add(cause)) {
        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return null
}

/** Whether [status] is the server refusing the request itself, a 4xx, which asking again will not change. */
internal fun isClientError(status: Int?): Boolean = status != null && status in 400..499

/**
 * Media3 asks again after a failed load, three times, 0, 1 and 2 seconds apart, whatever the failure was, so a file the server
 * answered 404 for showed no error for three seconds. A 4xx will be answered the same each time, so the first is the last, as
 * `<audio onError>` is; every other failure keeps the default's retries.
 */
internal class NoRetryOnClientError : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isClientError(mediaStatus(loadErrorInfo.exception))) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
}
