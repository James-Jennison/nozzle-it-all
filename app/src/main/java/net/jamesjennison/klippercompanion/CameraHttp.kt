package net.jamesjennison.klippercompanion

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl

internal fun cameraRedirectUrl(address: String, current: HttpUrl, location: String): HttpUrl {
    val resolved = current.resolve(location) ?: throw ApiFailure("Invalid camera redirect.")
    if(current.scheme == "https" && resolved.scheme != "https") throw ApiFailure("Camera redirect cannot downgrade HTTPS.")
    return Moonraker.cameraUrl(address, resolved.toString())
}

/** Camera GETs alone may follow up to three validated redirects on the printer host. */
internal fun cameraResponse(client: OkHttpClient, address: String, path: String): Response {
    require(!client.followRedirects && !client.followSslRedirects)
    var target = Moonraker.cameraUrl(address, path)
    repeat(4) { hop ->
        val response = client.newCall(Request.Builder().url(target).header("Cache-Control", "no-cache").build()).execute()
        if(response.code !in setOf(301, 302, 303, 307, 308)) return response
        response.use {
            if(hop == 3) throw ApiFailure("Too many camera redirects.")
            val location = response.header("Location") ?: throw ApiFailure("Camera redirect has no destination.")
            target = cameraRedirectUrl(address, target, location)
        }
    }
    throw ApiFailure("Too many camera redirects.")
}
