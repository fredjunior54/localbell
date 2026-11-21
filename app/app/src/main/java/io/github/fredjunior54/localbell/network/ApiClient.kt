package io.github.fredjunior54.localbell.network

import io.github.fredjunior54.localbell.util.CryptoUtils
import io.github.fredjunior54.localbell.util.UrlUtils
import okhttp3.Interceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {

    private var currentBaseUrl: String? = null

    private var currentToken: String? = null

    private var currentFingerprint: String? = null

    private var api: NotificationApi? = null

    @Synchronized
    fun getApi(baseUrl: String, token: String, expectedFingerprint: String): NotificationApi {
        val normalizedUrl = UrlUtils.normalizeUrl(baseUrl)

        if (
            api != null &&
                currentBaseUrl == normalizedUrl &&
                currentToken == token &&
                currentFingerprint == expectedFingerprint
        ) {
            return api!!
        }

        val baseClient = CryptoUtils.createPinnedOkHttpClient(expectedFingerprint)
        val client = baseClient.newBuilder().addInterceptor(authInterceptor(token)).build()

        val retrofit =
            Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

        currentBaseUrl = normalizedUrl
        currentToken = token
        currentFingerprint = expectedFingerprint
        api = retrofit.create(NotificationApi::class.java)
        return api!!
    }

    private fun authInterceptor(token: String): Interceptor {
        return Interceptor { chain ->
            val request =
                chain.request().newBuilder().addHeader("Authorization", "Bearer $token").build()
            chain.proceed(request)
        }
    }
}
