package live.citymall.facecapture

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * `POST {spec.url}` as `multipart/form-data`: every `spec.fields` entry as a
 * text part, the capture-quality JSON under `spec.metaDataField`, then the
 * JPEG under `spec.fileField`. Headers are the host's verbatim, on top of
 * `Accept: application/json`.
 *
 * Blocking — call it off the main thread.
 */
class FaceCaptureUploader(
    private val client: OkHttpClient = defaultClient,
) {

    sealed class Outcome {
        /** 2xx. `body` is the response text, capped at [MAX_BODY_CHARS], handed back to JS unparsed. */
        data class Success(val httpCode: Int, val body: String) : Outcome()
        data class Failure(
            val message: String,
            val httpCode: Int? = null,
            val body: String? = null,
        ) : Outcome()
    }

    /** @param metaData JSON object string for the meta-data field — see FaceCaptureActivity.metaDataJson(). */
    fun upload(file: File, spec: UploadSpec, metaData: String = "{}"): Outcome {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .apply {
                    spec.fields.forEach { (name, value) -> addFormDataPart(name, value) }
                    addFormDataPart(spec.metaDataField, metaData)
                    addFormDataPart(spec.fileField, spec.fileName, file.asRequestBody(JPEG))
                }
                .build()

            val request = Request.Builder()
                .url(spec.url)
                .header("Accept", "application/json")
                .apply { spec.headers.forEach { (name, value) -> header(name, value) } }
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
                if (response.isSuccessful) {
                    Outcome.Success(response.code, text.take(MAX_BODY_CHARS))
                } else {
                    Outcome.Failure(
                        "HTTP ${response.code} ${text.take(200)}".trim(),
                        response.code,
                        text.take(MAX_BODY_CHARS),
                    )
                }
            }
        } catch (e: IOException) {
            Outcome.Failure(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            // Request.Builder.url() rejects anything that is not an http(s) URL.
            Outcome.Failure("bad upload url: ${spec.url}")
        }
    }

    companion object {
        const val MAX_BODY_CHARS = 64 * 1024
        private val JPEG = "image/jpeg".toMediaTypeOrNull()

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
