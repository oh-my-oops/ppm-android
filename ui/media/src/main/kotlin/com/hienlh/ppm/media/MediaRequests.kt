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

/**
 * Whether the server answered with an error status, which is all [mediaStatus] ever finds: a 4xx, a 5xx, and a 3xx too, as the
 * transport follows no redirect. A failure with no status is a connection that broke or timed out.
 */
internal fun isHttpError(status: Int?): Boolean = status != null

/**
 * Media3 asks again after a failed load, three times, 1, 2 and 3 seconds apart (its delay is `min((errorCount - 1) * 1000, 5000)`,
 * with the count already at 2 at the first retry, as the gaps measured on a device show), whatever the failure was, so a file the
 * server answered 404 or 500 for showed no error until 6.6 seconds after the first request. A server that has answered with an
 * error status has said what it will say, so the first answer is the last, as `<audio onError>` is: Chrome on a 500 asked once or
 * twice (the second a same-Range twin within 0.3 seconds, not a back-off) and reported the error within a millisecond of the last
 * answer. A failure with no answer (a connection reset, a timeout) keeps the default's retries; Chrome was not measured there.
 */
internal class NoRetryOnHttpError : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isHttpError(mediaStatus(loadErrorInfo.exception))) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
}
