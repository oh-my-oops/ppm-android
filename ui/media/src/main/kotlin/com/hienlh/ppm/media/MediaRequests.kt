package com.hienlh.ppm.media

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
