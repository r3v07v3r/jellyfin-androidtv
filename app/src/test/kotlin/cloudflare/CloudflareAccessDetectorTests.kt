package org.jellyfin.androidtv.cloudflare

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl

class CloudflareAccessDetectorTests : FunSpec({
	test("detects redirect to Cloudflare Access login") {
		val headers = Headers.Builder()
			.add("Location", "https://example.com/cdn-cgi/access/login")
			.build()

		CloudflareAccessDetector.isCloudflareAccessChallenge(
			requestUrl = "https://example.com/System/Info/Public".toHttpUrl(),
			statusCode = 302,
			headers = headers,
		) shouldBe true
	}

	test("detects Cloudflare challenge headers") {
		val headers = Headers.Builder()
			.add("CF-Access-Aud", "aud")
			.add("CF-RAY", "abc")
			.build()

		CloudflareAccessDetector.isCloudflareAccessChallenge(
			requestUrl = "https://example.com/System/Info/Public".toHttpUrl(),
			statusCode = 403,
			headers = headers,
			bodySnippet = "forbidden",
		) shouldBe true
	}

	test("does not classify normal jellyfin login failure as cloudflare") {
		val headers = Headers.Builder().build()

		CloudflareAccessDetector.isCloudflareAccessChallenge(
			requestUrl = "https://example.com/Users/AuthenticateByName".toHttpUrl(),
			statusCode = 401,
			headers = headers,
			bodySnippet = "unauthorized",
		) shouldBe false
	}
})
