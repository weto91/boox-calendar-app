package com.weto.booxcal.data.remote.google

import com.weto.booxcal.BuildConfig
import com.weto.booxcal.data.remote.BackendAuthException
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

private const val CALENDAR_BASE_URL = "https://www.googleapis.com/"
private const val TASKS_BASE_URL = "https://tasks.googleapis.com/"

/**
 * Añade el bearer token y reintenta **una** vez ante un 401. Un token puede
 * seguir dentro de su ventana de validez y estar revocado desde la cuenta de
 * Google; ahí el único síntoma es el 401.
 */
class GoogleAuthInterceptor(private val auth: GoogleAuthManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = try {
            auth.freshAccessTokenBlocking()
        } catch (e: BackendAuthException) {
            throw java.io.IOException(e.message, e)
        }

        val response = chain.proceed(chain.request().withBearer(token))
        if (response.code != 401) return response

        response.close()
        auth.invalidateAccessToken()
        val retryToken = try {
            auth.freshAccessTokenBlocking()
        } catch (e: BackendAuthException) {
            throw java.io.IOException(e.message, e)
        }
        return chain.proceed(chain.request().withBearer(retryToken))
    }

    private fun Request.withBearer(token: String): Request =
        newBuilder()
            .header("Authorization", "Bearer $token")
            // Las bajadas de Drive piden bytes, no JSON: si la petición ya
            // trae su Accept, se respeta.
            .apply { if (header("Accept") == null) header("Accept", "application/json") }
            .build()
}

object GoogleNetwork {

    val json: Json = Json {
        ignoreUnknownKeys = true
        // Google manda a veces null donde el esquema dice booleano.
        coerceInputValues = true
        // explicitNulls se queda en su valor por defecto (true): los cuerpos de
        // escritura se construyen a mano con buildJsonObject y un `null`
        // explícito es justo como se borra un campo en un PATCH.
    }

    fun okHttpClient(auth: GoogleAuthManager): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(GoogleAuthInterceptor(auth))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        return builder.build()
    }

    fun calendarApi(client: OkHttpClient): GoogleCalendarApi =
        retrofit(client, CALENDAR_BASE_URL).create(GoogleCalendarApi::class.java)

    fun tasksApi(client: OkHttpClient): GoogleTasksApi =
        retrofit(client, TASKS_BASE_URL).create(GoogleTasksApi::class.java)

    fun driveClient(client: OkHttpClient): GoogleDriveClient = GoogleDriveClient(client, json)

    private fun retrofit(client: OkHttpClient, baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(JsonConverterFactory(json))
            .build()
}
