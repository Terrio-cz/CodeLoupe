package codeloupe.events

import java.net.URI

/**
 * Where a webhook may point: this machine (`127.0.0.1`, `localhost`, `[::1]`) on any port but the daemon's own, or an
 * https origin listed in `remoteWebhooks`. No credentials in the URL; redirects are never followed.
 */
class WebhookUrls(private val daemonPort: Int, private val remote: Set<String>) {
    /** Why [url] is refused, or null. */
    fun problem(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "not a URL"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "only http and https"
        if (uri.rawUserInfo != null) return "credentials in the URL are not allowed"
        val host = uri.host?.lowercase() ?: return "no host"
        val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
        if (host in LOCAL) return if (port == daemonPort) "that is the daemon itself" else null
        val origin = "$scheme://$host${if (uri.port != -1) ":${uri.port}" else ""}"
        if (scheme != "https") return "a remote webhook must use https"
        return if (remote.any { it.equals(origin, ignoreCase = true) }) null else "remote origin $origin is not in remoteWebhooks"
    }

    private companion object {
        val LOCAL = setOf("127.0.0.1", "localhost", "[::1]", "::1")
    }
}
