package com.alessandrolattao.lanotifica.network

import com.alessandrolattao.lanotifica.util.CryptoUtils
import com.alessandrolattao.lanotifica.util.PairingCode
import com.alessandrolattao.lanotifica.util.QrCodeParser
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

private data class PairRequest(val code: String, val name: String)

private data class PairResponse(val token: String?)

private interface PairingApi {
    @POST("/v1/pair") suspend fun pair(@Body request: PairRequest): PairResponse
}

object PairingClient {
    suspend fun exchange(pairing: PairingCode, deviceName: String): String {
        val client =
            Retrofit.Builder()
                .baseUrl("${pairing.url}/")
                .client(CryptoUtils.createPinnedOkHttpClient(pairing.fingerprint))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(PairingApi::class.java)
        val name =
            deviceName.filterNot { it.isISOControl() }.take(32).trim().ifBlank { "Android device" }
        val token = client.pair(PairRequest(pairing.code, name)).token
        check(token != null && QrCodeParser.isCredential(token)) { "Invalid pairing response" }
        return token
    }
}
