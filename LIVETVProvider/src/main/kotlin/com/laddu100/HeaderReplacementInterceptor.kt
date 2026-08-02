package com.laddu100

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class HeaderReplacementInterceptor(
    private val customHeaders: Map<String, String>
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        customHeaders.keys.forEach { headerName ->
            requestBuilder.removeHeader(headerName)
        }

        customHeaders.forEach { (name, value) ->
            requestBuilder.addHeader(name, value)
        }

        return chain.proceed(requestBuilder.build())
    }
}
