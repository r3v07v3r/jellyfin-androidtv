package org.jellyfin.androidtv.ui.cloudflare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.jellyfin.androidtv.cloudflare.CloudflareAccessAuthManager
import org.jellyfin.androidtv.databinding.ActivityCloudflareAccessLoginBinding
import org.koin.android.ext.android.inject
import timber.log.Timber
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import org.jellyfin.androidtv.cloudflare.CloudflareAccessAuthManager
import org.jellyfin.androidtv.databinding.ActivityCloudflareAccessLoginBinding
import org.koin.android.ext.android.inject

class CloudflareAccessLoginActivity : AppCompatActivity() {
	private lateinit var binding: ActivityCloudflareAccessLoginBinding
	private val cloudflareAccessAuthManager: CloudflareAccessAuthManager by inject()
	private var serverUrl: String = ""

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityCloudflareAccessLoginBinding.inflate(layoutInflater)
		setContentView(binding.root)

		serverUrl = intent.getStringExtra(EXTRA_SERVER_URL).orEmpty()
		val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL)
		if (serverUrl.isBlank() || loginUrl.isNullOrBlank()) {
		if (serverUrl.isBlank() || loginUrl.isNullOrBlank() || !isAllowedNavigation(loginUrl)) {
			setResult(Activity.RESULT_CANCELED)
			finish()
			return
		}

		val cookieManager = CookieManager.getInstance().apply {
			setAcceptCookie(true)
			setAcceptThirdPartyCookies(binding.webview, true)
		}

		with(binding.webview.settings) {
			javaScriptEnabled = true
			domStorageEnabled = true
		}
		binding.webview.addJavascriptInterface(WebAppBridge(), BRIDGE_NAME)

		binding.webview.webViewClient = object : WebViewClient() {
			override fun onPageFinished(view: WebView?, url: String?) {
				super.onPageFinished(view, url)
				injectEmailAutofillScript()
				val cookieHeader = cookieManager.getCookie(serverUrl).orEmpty()
				if (cookieHeader.contains("CF_Authorization=", ignoreCase = true)) {
					runCatching {
						cloudflareAccessAuthManager.saveCookieHeader(serverUrl, cookieHeader)
					}.onFailure { Timber.w(it, "Failed saving Cloudflare cookie header for %s", serverUrl) }
			setAcceptThirdPartyCookies(binding.webview, false)
		}

		configureWebView()
		binding.webview.webViewClient = object : WebViewClient() {
			override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
				return request?.url?.let { !isAllowedNavigation(it) } ?: true
			}

			override fun onPageFinished(view: WebView?, url: String?) {
				super.onPageFinished(view, url)
				val cookieHeader = sequenceOf(
					cookieManager.getCookie(serverUrl),
					url?.let(cookieManager::getCookie),
				)
					.filterNotNull()
					.firstOrNull { it.contains("CF_Authorization=", ignoreCase = true) }
					.orEmpty()
				if (cookieHeader.isNotBlank()) {
					cloudflareAccessAuthManager.saveCookieHeader(serverUrl, cookieHeader)
					setResult(Activity.RESULT_OK)
					finish()
				}
			}
		}

		binding.webview.loadUrl(loginUrl)
	}

	override fun onDestroy() {
		binding.webview.destroy()
		super.onDestroy()
	}

	private fun injectEmailAutofillScript() {
		val escapedEmail = cloudflareAccessAuthManager
			.getLastEmail(serverUrl)
			?.replace("\\", "\\\\")
			?.replace("'", "\\'")
			.orEmpty()
		val script = """
			(function() {
				const bridge = window.$BRIDGE_NAME;
				if (!bridge) return;
				const setListener = function(element) {
					if (!element || element.dataset.cfEmailBound === '1') return;
					element.dataset.cfEmailBound = '1';
					element.addEventListener('change', function() { bridge.saveEmail(element.value || ''); });
					element.addEventListener('input', function() { bridge.saveEmail(element.value || ''); });
				};
				const selectors = [
					"input[type='email']",
					"input[name='email']",
					"input[id='email']",
					"input[autocomplete='username']"
				];
				selectors.forEach(function(selector) {
					document.querySelectorAll(selector).forEach(function(input) {
						if ('$escapedEmail' !== '' && !input.value) input.value = '$escapedEmail';
						setListener(input);
					});
				});
			})();
		""".trimIndent()
		runCatching { binding.webview.evaluateJavascript(script, null) }
			.onFailure { Timber.w(it, "Cloudflare email autofill script injection failed for %s", serverUrl) }
	}

	private inner class WebAppBridge {
		@JavascriptInterface
		fun saveEmail(email: String) {
			cloudflareAccessAuthManager.saveLastEmail(serverUrl, email)
		}
	@SuppressLint("SetJavaScriptEnabled")
	private fun configureWebView() {
		with(binding.webview.settings) {
			// Cloudflare Access and common identity providers require JavaScript for the login challenge.
			javaScriptEnabled = true
			domStorageEnabled = true
			allowFileAccess = false
			allowContentAccess = false
			javaScriptCanOpenWindowsAutomatically = false
			setSupportMultipleWindows(false)
			mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
		}
	}

	private fun isAllowedNavigation(url: String): Boolean = isAllowedNavigation(url.toUri())

	private fun isAllowedNavigation(uri: Uri): Boolean {
		return uri.scheme.equals("https", ignoreCase = true)
	}

	companion object {
		private const val EXTRA_SERVER_URL = "server_url"
		private const val EXTRA_LOGIN_URL = "login_url"
		private const val BRIDGE_NAME = "CloudflareAccessBridge"

		fun createIntent(context: Context, serverUrl: String, loginUrl: String): Intent {
			return Intent(context, CloudflareAccessLoginActivity::class.java).apply {
				putExtra(EXTRA_SERVER_URL, serverUrl)
				putExtra(EXTRA_LOGIN_URL, loginUrl)
			}
		}
	}
}
