package com.weto.booxcal.data.remote.google

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.weto.booxcal.BuildConfig
import com.weto.booxcal.data.remote.BackendAuthException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import com.weto.booxcal.R

/**
 * OAuth 2.0 + PKCE contra Google, vía AppAuth.
 *
 * Deliberadamente **sin Google Play Services**: los Boox no siempre lo traen, y
 * cuando lo traen es una instalación parcial en la que `GoogleSignIn` falla de
 * formas difíciles de diagnosticar. AppAuth solo necesita un navegador.
 */
class GoogleAuthManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val authService = AuthorizationService(appContext)

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse(AUTH_ENDPOINT),
        Uri.parse(TOKEN_ENDPOINT),
    )

    @Volatile
    private var authState: AuthState = readState()

    val isConfigured: Boolean get() = BuildConfig.OAUTH_CLIENT_ID.isNotBlank()

    val isAuthorized: Boolean get() = authState.isAuthorized

    /**
     * ¿La sesión actual concedió el permiso de Drive? Una cuenta conectada
     * antes de que existiera no lo tiene: hay que desconectar y conectar.
     */
    val hasDriveScope: Boolean
        get() = authState.scopeSet?.any { it.contains("auth/drive") } == true

    /**
     * La sesión tiene Drive completo (una cuenta conectada con el permiso
     * antiguo). Con `drive.file`, en cambio, todo lo que la app ve en Drive
     * lo creó ella misma.
     */
    val hasFullDriveScope: Boolean
        get() = authState.scopeSet?.any { it == "https://www.googleapis.com/auth/drive" } == true

    /** Correo de la cuenta, sacado del id_token. Null si aún no hay sesión. */
    val accountEmail: String?
        get() = authState.idToken?.let { claimFromIdToken(it, "email") }

    /**
     * Intent para abrir la pantalla de permiso de Google.
     *
     * Es un `ACTION_VIEW` normal con la URL de autorización, no el intent de
     * pestañas personalizadas de AppAuth. Aquel exige encontrar un navegador
     * que se declare "por defecto", y en el Boox no lo encontraba: lanzaba
     * `ActivityNotFoundException`, se tragaba en un `runCatching` y el botón
     * "no hacía nada". Con `ACTION_VIEW` abre el navegador que haya.
     *
     * La petición se guarda para poder reconstruir la respuesta cuando el
     * navegador vuelva por el esquema de redirección.
     */
    fun authorizationIntent(): Intent {
        check(isConfigured) {
            appContext.getString(R.string.auth_no_client_id)
        }
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            BuildConfig.OAUTH_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(redirectUri()),
        )
            .setScopes(SCOPES)
            // A una app instalada (cliente Android) Google le da refresh_token
            // por defecto en el primer consentimiento; `prompt=consent` lo
            // asegura al reconectar. `access_type=offline` es de clientes web
            // y aquí solo suma un parámetro sospechoso a la petición.
            //
            // `prompt` va por su setter: AppAuth lo tiene como campo propio y
            // lanza IllegalArgumentException si se cuela como parámetro
            // adicional.
            .setPrompt("consent")
            .build()
        prefs.edit().putString(KEY_PENDING_REQUEST, request.jsonSerializeString()).apply()
        return Intent(Intent.ACTION_VIEW, request.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** ¿Es esta URI la vuelta del navegador con el código de Google? */
    fun isRedirect(uri: Uri?): Boolean =
        uri != null && uri.scheme.equals(BuildConfig.OAUTH_REDIRECT_SCHEME, ignoreCase = true)

    /** La vuelta del navegador: reconstruye la respuesta y canjea el código. */
    suspend fun handleRedirect(uri: Uri): Result<Unit> {
        val pending = prefs.getString(KEY_PENDING_REQUEST, null)
            ?: return Result.failure(BackendAuthException(appContext.getString(R.string.auth_no_pending_request)))
        val request = runCatching { AuthorizationRequest.jsonDeserialize(pending) }
            .getOrElse { return Result.failure(BackendAuthException(appContext.getString(R.string.auth_request_unreadable), it)) }
        prefs.edit().remove(KEY_PENDING_REQUEST).apply()

        // `fromOAuthRedirect` no devuelve null nunca: sin parámetro `error`
        // fabrica igualmente una excepción genérica. Así que primero se mira
        // si Google ha vuelto con error o con código, como hace el propio
        // AppAuth en su actividad receptora. Antes se llamaba a ciegas y toda
        // vuelta, también la buena, acababa en "Autorización rechazada".
        val hasError = uri.queryParameterNames.contains("error")
        val error = if (hasError) AuthorizationException.fromOAuthRedirect(uri) else null
        val response = if (!hasError && uri.getQueryParameter("code") != null) {
            AuthorizationResponse.Builder(request).fromUri(uri).build()
        } else {
            null
        }
        if (response == null && error == null) {
            return Result.failure(BackendAuthException(appContext.getString(R.string.auth_no_code)))
        }
        if (response != null && response.state != request.state) {
            return Result.failure(BackendAuthException(appContext.getString(R.string.auth_state_mismatch)))
        }
        return exchange(response, error)
    }

    /** Camino antiguo, por si el resultado llega por AppAuth. */
    suspend fun handleAuthorizationResult(data: Intent?): Result<Unit> {
        if (data == null) return Result.failure(BackendAuthException(appContext.getString(R.string.auth_cancelled)))
        data.data?.takeIf { isRedirect(it) }?.let { return handleRedirect(it) }
        return exchange(AuthorizationResponse.fromIntent(data), AuthorizationException.fromIntent(data))
    }

    private suspend fun exchange(
        response: AuthorizationResponse?,
        error: AuthorizationException?,
    ): Result<Unit> {
        authState = AuthState(serviceConfig).also { it.update(response, error) }

        if (response == null) {
            persist()
            return Result.failure(
                BackendAuthException(
                    error?.errorDescription ?: error?.error ?: appContext.getString(R.string.auth_rejected)
                )
            )
        }

        return suspendCancellableCoroutine { cont ->
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResp, ex ->
                authState.update(tokenResp, ex)
                persist()
                if (tokenResp != null) {
                    cont.resume(Result.success(Unit))
                } else {
                    cont.resume(
                        Result.failure(
                            BackendAuthException(
                                ex?.errorDescription ?: appContext.getString(R.string.auth_exchange_failed), ex
                            )
                        )
                    )
                }
            }
        }
    }

    /**
     * Token de acceso fresco, bloqueando. Se llama desde el interceptor de
     * OkHttp, que ya corre fuera del hilo principal.
     */
    @Throws(BackendAuthException::class)
    fun freshAccessTokenBlocking(): String {
        val state = authState
        if (!state.isAuthorized) throw BackendAuthException(appContext.getString(R.string.auth_no_account))

        val latch = CountDownLatch(1)
        var token: String? = null
        var failure: AuthorizationException? = null

        state.performActionWithFreshTokens(authService) { accessToken, _, ex ->
            token = accessToken
            failure = ex
            latch.countDown()
        }

        if (!latch.await(TOKEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw BackendAuthException(appContext.getString(R.string.auth_refresh_timeout))
        }
        persist()

        failure?.let { throw BackendAuthException(it.errorDescription ?: appContext.getString(R.string.auth_token_not_renewable), it) }
        return token ?: throw BackendAuthException(appContext.getString(R.string.auth_no_access_token))
    }

    /**
     * Marca el access token como caducado. Se llama al recibir un 401: puede
     * que el token siguiera dentro de su ventana de validez pero haya sido
     * revocado desde la cuenta de Google.
     */
    fun invalidateAccessToken() {
        authState.needsTokenRefresh = true
        persist()
    }

    fun signOut() {
        authState = AuthState(serviceConfig)
        prefs.edit().remove(KEY_STATE).apply()
    }

    private fun redirectUri(): String = "${BuildConfig.OAUTH_REDIRECT_SCHEME}:/oauth2redirect"

    private fun readState(): AuthState {
        val json = prefs.getString(KEY_STATE, null) ?: return AuthState(serviceConfig)
        return runCatching { AuthState.jsonDeserialize(json) }
            .getOrElse {
                Log.w(TAG, "AuthState corrupto, se descarta", it)
                AuthState(serviceConfig)
            }
    }

    private fun persist() {
        prefs.edit().putString(KEY_STATE, authState.jsonSerializeString()).apply()
    }

    /**
     * Lee un claim del id_token sin verificar la firma. Es seguro: el token
     * llegó por TLS directo desde Google y solo lo usamos para mostrar el
     * correo en ajustes, nunca para autorizar nada.
     */
    private fun claimFromIdToken(idToken: String, claim: String): String? = runCatching {
        val payload = idToken.split('.').getOrNull(1) ?: return null
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(String(decoded, Charsets.UTF_8)).optString(claim).takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private const val TAG = "GoogleAuthManager"
        private const val PREFS_NAME = "booxcal_auth"
        private const val KEY_STATE = "auth_state"
        private const val KEY_PENDING_REQUEST = "pending_request"
        private const val TOKEN_TIMEOUT_SECONDS = 30L

        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        val SCOPES = listOf(
            "openid",
            "email",
            "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/auth/tasks",
            // `drive.file`, no Drive completo: solo ve lo que esta app crea
            // (su carpeta y sus PDF). Un PDF de otra app entra con «Importar»
            // desde el cuaderno, que lo copia a la carpeta de la app. Es el
            // ámbito que Google acepta sin auditoría de seguridad.
            "https://www.googleapis.com/auth/drive.file",
        )
    }
}
