package org.jellyfin.androidtv.cloudflare

import okhttp3.Headers
import okhttp3.HttpUrl

object CloudflareAccessDetector {
	private const val CLOUDFLARE_ACCESS_MARKER = "cloudflare access"
	private const val CLOUDFLARE_LOGIN_MARKER = "cdn-cgi/access"

	fun isCloudflareAccessChallenge(
		requestUrl: HttpUrl,
		statusCode: Int,
		headers: Headers,
		bodySnippet: String? = null,
	): Boolean {
		if (statusCode in 302..303) {
			val location = headers["Location"].orEmpty().lowercase()
			if (location.contains(CLOUDFLARE_LOGIN_MARKER) || location.contains("cloudflareaccess.com")) return true
		}

		if (statusCode !in setOf(401, 403) && !hasCloudflareHeaders(headers)) return false

		if (!isLikelyJellyfinPath(requestUrl.encodedPath)) {
			return hasCloudflareHeaders(headers) || bodySnippetContainsChallenge(bodySnippet)
		}

		return hasCloudflareHeaders(headers) || bodySnippetContainsChallenge(bodySnippet)
	}

	fun isExpiredSessionChallenge(
		requestUrl: HttpUrl,
		statusCode: Int,
		headers: Headers,
		bodySnippet: String? = null,
	): Boolean {
		if (statusCode !in setOf(401, 403, 302, 303)) return false
		return isCloudflareAccessChallenge(requestUrl, statusCode, headers, bodySnippet)
	}

	fun extractLoginUrl(baseUrl: HttpUrl, headers: Headers): String {
		val location = headers["Location"] ?: return baseUrl.newBuilder().encodedPath("/").build().toString()
		return baseUrl.resolve(location)?.toString() ?: location
	}

	private fun hasCloudflareHeaders(headers: Headers): Boolean {
		if (headers.names().any { it.startsWith("cf-access", ignoreCase = true) }) return true
		val server = headers["Server"].orEmpty()
		if (server.contains("cloudflare", ignoreCase = true)) return true
		val ray = headers["CF-RAY"]
		if (!ray.isNullOrBlank()) return true
		return false
	}

	private fun bodySnippetContainsChallenge(bodySnippet: String?): Boolean {
		if (bodySnippet.isNullOrBlank()) return false
		val normalized = bodySnippet.lowercase()
		return normalized.contains(CLOUDFLARE_ACCESS_MARKER) || normalized.contains(CLOUDFLARE_LOGIN_MARKER)
	}

	private fun isLikelyJellyfinPath(path: String): Boolean {
		return path.startsWith("/System", ignoreCase = true) ||
			path.startsWith("/Users", ignoreCase = true) ||
			path.startsWith("/Items", ignoreCase = true) ||
			path.startsWith("/Videos", ignoreCase = true) ||
			path.startsWith("/Audio", ignoreCase = true)
	}
}
