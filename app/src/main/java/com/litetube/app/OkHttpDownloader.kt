package com.litetube.app

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/**
 * NewPipeExtractor needs a Downloader implementation; this one is plain
 * OkHttp. Every extractor call runs on Dispatchers.IO in the repository,
 * so this blocking execute() never touches the main thread.
 */
class OkHttpDownloader(
    private val client: OkHttpClient = OkHttpClient()
) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
        for ((name, values) in request.headers()) {
            for (value in values) {
                builder.addHeader(name, value)
            }
        }
        when (request.httpMethod()) {
            "POST" -> builder.post((request.dataToSend() ?: ByteArray(0)).toRequestBody())
            "HEAD" -> builder.head()
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            val headers = mutableMapOf<String, List<String>>()
            for (name in response.headers.names()) {
                headers[name] = response.headers.values(name)
            }
            val body = response.body?.bytes() ?: ByteArray(0)
            return Response(
                response.code,
                response.message,
                headers,
                body,
                response.request.url.toString()
            )
        }
    }
}
