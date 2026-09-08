package com.weto.booxcal.data.remote.google

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

/**
 * Puente entre Retrofit y kotlinx.serialization.
 *
 * Son treinta líneas y evitan una dependencia de terceros más
 * (`retrofit2-kotlinx-serialization-converter`) en un proyecto que ya arrastra
 * un SDK propietario con su propio árbol de dependencias antiguas. Cuantas
 * menos piezas ajenas haya en el classpath, menos sorpresas al compilar.
 */
@OptIn(ExperimentalSerializationApi::class)
class JsonConverterFactory(
    private val json: Json,
    private val contentType: MediaType = "application/json; charset=UTF-8".toMediaType(),
) : Converter.Factory() {

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *> {
        // Los DELETE devuelven 204 sin cuerpo. Intentar deserializar una cadena
        // vacía fallaría, así que se atajan antes.
        if (type == Unit::class.java) {
            return Converter<ResponseBody, Unit> { body -> body.close() }
        }
        // serializer(Type) devuelve KSerializer<Any>, no KSerializer<Any?>: la
        // búsqueda reflexiva no admite tipos nulos.
        val loader: KSerializer<Any> = json.serializersModule.serializer(type)
        return Converter<ResponseBody, Any> { body ->
            body.use { json.decodeFromString(loader, it.string()) }
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody> {
        val saver: KSerializer<Any> = json.serializersModule.serializer(type)
        // Retrofit nunca entrega un @Body nulo, así que el no-nulo es exacto.
        return Converter<Any, RequestBody> { value ->
            json.encodeToString(saver, value).toRequestBody(contentType)
        }
    }
}
