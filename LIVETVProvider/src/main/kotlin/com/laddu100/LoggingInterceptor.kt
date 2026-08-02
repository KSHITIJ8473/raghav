package com.laddu100

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class LoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()

        val bodyCopy = req.body
        val buffer = Buffer()
        bodyCopy?.writeTo(buffer)
        buffer.readUtf8()

        return chain.proceed(req)
    }
}
