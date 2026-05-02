package org.jellyfin.androidtv.cloudflare

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

class CloudflareAccessAuthManager(context: Context) {
	private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
	private val json = Json { ignoreUnknownKeys = true }
	private val probeClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

	private val cookieJar = object : CookieJar {
		override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
			saveCookies(url.toString(), cookies)
		}

		override fun loadForRequest(url: HttpUrl): List<Cookie> = getCookiesForServer(url.toString())
	}

	private val requestInterceptor = Interceptor { chain ->
		val request = chain.request()
		val cookieHeader = getCookieHeader(request.url.toString())
		val requestWithCookie = if (!cookieHeader.isNullOrBlank()) {
			Timber.d("Applying Cloudflare Access cookie for host=%s", request.url.host)
			request.newBuilder().header("Cookie", cookieHeader).build()
		} else {
			request
		}

		val response = chain.proceed(requestWithCookie)
		handleResponse(requestWithCookie.url, response)
		response
	}

	fun createCookieJar(): CookieJar = cookieJar
	fun createRequestInterceptor(): Interceptor = requestInterceptor

	fun startLoginFlow(serverUrl: String): CloudflareAccessChallenge? {
		val parsedUrl = serverUrl.toHttpUrlOrNull() ?: return null
		val probeUrl = parsedUrl.newBuilder().encodedPath("/System/Info/Public").build()
		val request = Request.Builder().url(probeUrl).get().build()

		val response = try {
			probeClient.newCall(request).execute()
		} catch (err: IOException) {
			Timber.w(err, "Unable to probe Cloudflare Access challenge for %s", serverUrl)
			return null
		}

		response.use { rawResponse ->
			val bodySnippet = rawResponse.peekBody(MAX_CHALLENGE_BODY_BYTES).string()
			val challenge = detectAccessChallenge(
				url = probeUrl.toString(),
				statusCode = rawResponse.code,
				headers = rawResponse.headers,
				bodySnippet = bodySnippet,
			)
			if (!challenge) return null

			return CloudflareAccessChallenge(
				serverUrl = parsedUrl.newBuilder().encodedPath("/").build().toString(),
				loginUrl = CloudflareAccessDetector.extractLoginUrl(parsedUrl, rawResponse.headers),
			)
		}
	}

	fun detectAccessChallenge(url: String, statusCode: Int, headers: Headers, bodySnippet: String? = null): Boolean {
		val parsedUrl = url.toNormalizedHttpUrlOrNull() ?: return false
		return CloudflareAccessDetector.isCloudflareAccessChallenge(parsedUrl, statusCode, headers, bodySnippet)
	}

	fun detectExpiredAccessSession(url: String, statusCode: Int, headers: Headers, bodySnippet: String? = null): Boolean {
		val parsedUrl = url.toNormalizedHttpUrlOrNull() ?: return false
		return CloudflareAccessDetector.isExpiredSessionChallenge(parsedUrl, statusCode, headers, bodySnippet)
	}

	fun saveCookies(serverUrl: String, cookies: List<Cookie>) {
		val parsedUrl = serverUrl.toNormalizedHttpUrlOrNull() ?: return
		val accessCookie = cookies.firstOrNull { it.name.equals(CF_AUTHORIZATION, ignoreCase = true) } ?: return
		saveCookie(parsedUrl.host, accessCookie)
	}

	fun saveCookieHeader(serverUrl: String, cookieHeader: String) {
		val parsedUrl = serverUrl.toNormalizedHttpUrlOrNull() ?: return
		val cookie = parseCookieHeader(cookieHeader, parsedUrl.host) ?: return
		saveCookie(parsedUrl.host, cookie)
	}

	fun getCookiesForServer(serverUrl: String): List<Cookie> {
		val parsedUrl = serverUrl.toNormalizedHttpUrlOrNull() ?: return emptyList()
		val session = getStoredSession(parsedUrl.host) ?: return emptyList()
		if (session.isExpired) {
			clearCookies(serverUrl)
			return emptyList()
		}

		return listOf(
			Cookie.Builder()
				.name(session.name)
				.value(session.value)
				.domain(parsedUrl.host)
				.path(session.path)
				.apply { if (session.secure) secure() }
				.apply { if (session.httpOnly) httpOnly() }
				.apply { session.expiresAt?.let(::expiresAt) }
				.build()
		)
	}

	fun getCookieHeader(serverUrl: String): String? {
		val cookies = getCookiesForServer(serverUrl)
		if (cookies.isEmpty()) return null
		return cookies.joinToString(separator = "; ") { "${it.name}=${it.value}" }
	}

	fun clearCookies(serverUrl: String) {
		val parsedUrl = serverUrl.toNormalizedHttpUrlOrNull() ?: return
		preferences.edit().remove(cookieKey(parsedUrl.host)).apply()
	}

	fun isAccessCookieAvailable(serverUrl: String): Boolean = getCookiesForServer(serverUrl).isNotEmpty()

	fun saveLastEmail(serverUrl: String, email: String) {
		val parsedUrl = serverUrl.toNormalizedHttpUrlOrNull() ?: return
		val trimmed = email.trim()
		if (trimmed.isBlank()) return
		preferences.edit().putString(emailKey(parsedUrl.host), trimmed).apply()
	}

	fun getLastEmail(serverUrl: String): String? {
		val parsedUrl = serverUrl.toNormalizedHttpUrlOrNull() ?: return null
		return preferences.getString(emailKey(parsedUrl.host), null)
	}

	fun markSessionExpired(serverUrl: String) {
		val parsedUrl = serverUrl.toNormalizedHttpUrlOrNull() ?: return
		preferences.edit().putBoolean(expiredKey(parsedUrl.host), true).apply()
	}

	fun consumeSessionExpired(serverUrl: String): Boolean {
		val parsedUrl = serverUrl.toNormalizedHttpUrlOrNull() ?: return false
		val key = expiredKey(parsedUrl.host)
		val expired = preferences.getBoolean(key, false)
		if (expired) preferences.edit().remove(key).apply()
		return expired
	}

	private fun saveCookie(host: String, cookie: Cookie) {
		val serialized = StoredAccessCookie(
			name = cookie.name,
			value = cookie.value,
			expiresAt = if (cookie.persistent) cookie.expiresAt else null,
			path = cookie.path,
			secure = cookie.secure,
			httpOnly = cookie.httpOnly,
		)
		preferences.edit().putString(cookieKey(host), json.encodeToString(serialized)).apply()
		Timber.i("Stored Cloudflare Access cookie for host=%s", host)
	}

	private fun getStoredSession(host: String): StoredAccessCookie? {
		val data = preferences.getString(cookieKey(host), null) ?: return null
		return runCatching { json.decodeFromString<StoredAccessCookie>(data) }
			.onFailure { Timber.e(it, "Unable to decode Cloudflare cookie for host=%s", host) }
			.getOrNull()
	}

	private fun parseCookieHeader(cookieHeader: String, host: String): Cookie? {
		val cookie = cookieHeader.split(';')
			.map { it.trim() }
			.firstOrNull { it.startsWith("$CF_AUTHORIZATION=", ignoreCase = true) }
			?: return null
		val value = cookie.substringAfter('=', missingDelimiterValue = "")
		if (value.isBlank()) return null

		return Cookie.Builder()
			.name(CF_AUTHORIZATION)
			.value(value)
			.domain(host)
			.path("/")
			.secure()
			.httpOnly()
			.build()
	}

	private fun handleResponse(requestUrl: HttpUrl, response: Response) {
		if (!isAccessCookieAvailable(requestUrl.toString())) return

		val bodySnippet = response.peekBody(MAX_CHALLENGE_BODY_BYTES).string()
		val expired = CloudflareAccessDetector.isExpiredSessionChallenge(
			requestUrl = requestUrl,
			statusCode = response.code,
			headers = response.headers,
			bodySnippet = bodySnippet,
		)
		if (expired) {
			Timber.w("Cloudflare Access session expired for host=%s", requestUrl.host)
			clearCookies(requestUrl.toString())
			markSessionExpired(requestUrl.toString())
		}
	}

	private fun String.toNormalizedHttpUrlOrNull(): HttpUrl? {
		return Uri.parse(this).toString().toHttpUrlOrNull()
	}

	private fun cookieKey(host: String) = "cookie:$host"
	private fun expiredKey(host: String) = "expired:$host"
	private fun emailKey(host: String) = "email:$host"

	@Serializable
	private data class StoredAccessCookie(
		val name: String,
		val value: String,
		val expiresAt: Long?,
		val path: String,
		val secure: Boolean,
		val httpOnly: Boolean,
	) {
		val isExpired: Boolean get() = expiresAt?.let { it <= System.currentTimeMillis() } == true
	}

	data class CloudflareAccessChallenge(
		val serverUrl: String,
		val loginUrl: String,
	)

	companion object {
		private const val PREFERENCES_NAME = "cloudflare_access"
		private const val CF_AUTHORIZATION = "CF_Authorization"
		private const val MAX_CHALLENGE_BODY_BYTES = 2048L
	}
}
