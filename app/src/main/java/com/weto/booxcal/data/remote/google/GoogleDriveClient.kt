package com.weto.booxcal.data.remote.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.IOException
import java.time.Instant
import com.weto.booxcal.R
import com.weto.booxcal.di.Graph

@Serializable
data class GDriveFile(
    val id: String,
    val name: String = "",
    val mimeType: String? = null,
    val modifiedTime: String? = null,
    val parents: List<String> = emptyList(),
    val md5Checksum: String? = null,
    val trashed: Boolean = false,
) {
    val isFolder: Boolean get() = mimeType == GoogleDriveClient.FOLDER_MIME
    val isPdf: Boolean get() = mimeType == GoogleDriveClient.PDF_MIME || name.endsWith(".pdf", ignoreCase = true)
    val modifiedMillis: Long
        get() = modifiedTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}

@Serializable
data class GDriveFileList(
    val files: List<GDriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

/** Error de Drive con el código HTTP y lo que Google dijo. */
class DriveException(val code: Int, message: String) : IOException(message) {
    /** 403 con «insufficient»: la cuenta se conectó sin el permiso de Drive. */
    val missingScope: Boolean
        get() = code == 403 && (message?.contains("insufficient", ignoreCase = true) == true ||
            message?.contains("scope", ignoreCase = true) == true)
}

/**
 * Google Drive v3, a pelo con OkHttp: lo que hace falta para leer y escribir
 * las notas como PDF. Va sin Retrofit porque las subidas son multipart
 * «related» y las bajadas son bytes, que Retrofit no tipa bien.
 */
class GoogleDriveClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    // --- Lectura ------------------------------------------------------------

    /** Hijos directos de una carpeta, opcionalmente solo de un tipo. Sin papelera. */
    suspend fun listChildren(parentId: String, mimeType: String? = null): List<GDriveFile> {
        val q = buildString {
            append("'").append(parentId).append("' in parents and trashed = false")
            if (mimeType != null) append(" and mimeType = '").append(mimeType).append("'")
        }
        val all = mutableListOf<GDriveFile>()
        var token: String? = null
        do {
            val url = (API + "files").toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", LIST_FIELDS)
                .addQueryParameter("pageSize", "200")
                .addQueryParameter("orderBy", "name")
                .addQueryParameter("spaces", "drive")
                .apply { if (token != null) addQueryParameter("pageToken", token) }
                .build()
            val page = execute<GDriveFileList>(Request.Builder().url(url).get().build())
            all += page.files
            token = page.nextPageToken
        } while (token != null)
        return all
    }

    suspend fun listFolders(parentId: String): List<GDriveFile> = listChildren(parentId, FOLDER_MIME)

    /** Metadatos de un archivo; null si ya no existe. */
    suspend fun getFile(id: String): GDriveFile? {
        val url = (API + "files/" + id).toHttpUrl().newBuilder()
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        return try {
            execute<GDriveFile>(Request.Builder().url(url).get().build())
        } catch (e: DriveException) {
            if (e.code == 404) null else throw e
        }
    }

    suspend fun download(id: String): ByteArray {
        val url = (API + "files/" + id).toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()
        val request = Request.Builder().url(url).get().header("Accept", "*/*").build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw failure(response)
                response.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    // --- Escritura ----------------------------------------------------------

    suspend fun createFolder(parentId: String, name: String): GDriveFile {
        val body = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            putJsonArray("parents") { add(parentId) }
        }
        val url = (API + "files").toHttpUrl().newBuilder().addQueryParameter("fields", FILE_FIELDS).build()
        return execute<GDriveFile>(
            Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
        )
    }

    /**
     * La carpeta con ese nombre dentro de la dada, creándola si no está. Con
     * `drive.file` solo se ven las carpetas que creó esta app, así que la que
     * encuentre es suya de antes.
     */
    suspend fun findOrCreateFolder(parentId: String, name: String): GDriveFile =
        listFolders(parentId).firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: createFolder(parentId, name)

    /**
     * Crea el PDF en la carpeta dada: metadatos y contenido en una sola
     * subida. [onProgress] recibe (bytes enviados, total) mientras sube.
     */
    suspend fun createPdf(
        parentId: String,
        name: String,
        bytes: ByteArray,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): GDriveFile {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", PDF_MIME)
            putJsonArray("parents") { add(parentId) }
        }
        val content = bytes.toRequestBody(PDF).let { if (onProgress == null) it else ProgressBody(it, onProgress) }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(JSON))
            .addPart(content)
            .build()
        val url = (UPLOAD + "files").toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        return execute<GDriveFile>(Request.Builder().url(url).post(body).build())
    }

    /** Sustituye el contenido del PDF. Conserva id, nombre, carpeta y permisos. */
    suspend fun updatePdf(id: String, bytes: ByteArray): GDriveFile {
        val url = (UPLOAD + "files/" + id).toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        return execute<GDriveFile>(
            Request.Builder().url(url).patch(bytes.toRequestBody(PDF)).build()
        )
    }

    /** Cambia nombre y/o carpeta. Lo que va null se deja como está. */
    suspend fun updateMetadata(
        id: String,
        name: String? = null,
        addParent: String? = null,
        removeParent: String? = null,
    ): GDriveFile {
        val body: JsonObject = buildJsonObject { if (name != null) put("name", name) }
        val url = (API + "files/" + id).toHttpUrl().newBuilder()
            .addQueryParameter("fields", FILE_FIELDS)
            .apply {
                if (addParent != null) addQueryParameter("addParents", addParent)
                if (removeParent != null) addQueryParameter("removeParents", removeParent)
            }
            .build()
        return execute<GDriveFile>(
            Request.Builder().url(url).patch(body.toString().toRequestBody(JSON)).build()
        )
    }

    /** A la papelera, no borrado: desde Drive se puede recuperar. 404 se ignora. */
    suspend fun trash(id: String) {
        val body = buildJsonObject { put("trashed", true) }
        val url = (API + "files/" + id).toHttpUrl().newBuilder().addQueryParameter("fields", "id").build()
        try {
            execute<GDriveFile>(Request.Builder().url(url).patch(body.toString().toRequestBody(JSON)).build())
        } catch (e: DriveException) {
            if (e.code != 404) throw e
        }
    }

    // --- Plomería -----------------------------------------------------------

    /** Envuelve un cuerpo y va contando lo que se ha escrito en la red. */
    private class ProgressBody(
        private val inner: RequestBody,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = inner.contentType()
        override fun contentLength() = inner.contentLength()
        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            val counting = object : ForwardingSink(sink) {
                var written = 0L
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    onProgress(written, total)
                }
            }
            val buffered = counting.buffer()
            inner.writeTo(buffered)
            buffered.flush()
        }
    }

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw failure(response)
            val text = response.body?.string().orEmpty()
            json.decodeFromString<T>(text)
        }
    }

    private fun failure(response: Response): DriveException {
        val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val reason = runCatching {
            json.parseToJsonElement(text).let { root ->
                (root as? JsonObject)?.get("error")?.let { err ->
                    (err as? JsonObject)?.get("message")?.toString()?.trim('"')
                }
            }
        }.getOrNull()
        return DriveException(response.code, reason ?: Graph.appContext.getString(R.string.drive_responded, response.code))
    }

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val PDF_MIME = "application/pdf"
        const val ROOT = "root"

        private const val API = "https://www.googleapis.com/drive/v3/"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/"
        private const val FILE_FIELDS = "id,name,mimeType,modifiedTime,parents,md5Checksum,trashed"
        private const val LIST_FIELDS = "files($FILE_FIELDS),nextPageToken"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val PDF = "application/pdf".toMediaType()
    }
}
