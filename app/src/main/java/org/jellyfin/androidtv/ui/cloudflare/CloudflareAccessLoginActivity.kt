package org.jellyfin.androidtv.ui.cloudflare

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
		if (serverUrl.isBlank() || loginUrl.isNullOrBlank() || !isAllowedNavigation(loginUrl)) {
			setResult(Activity.RESULT_CANCELED)
			finish()
			return
		}

		val cookieManager = CookieManager.getInstance().apply {
			setAcceptCookie(true)
			setAcceptThirdPartyCookies(binding.webview, false)
		}

		configureWebView()
		binding.webview.webViewClient = object : WebViewClient() {
			override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
				return request?.url?.let { !isAllowedNavigation(it) } ?: true
			}

			override fun onPageFinished(view: WebView?, url: String?) {
				super.onPageFinished(view, url)
				val cookieHeader = cookieManager.getCookie(serverUrl).orEmpty()
				if (cookieHeader.contains("CF_Authorization=", ignoreCase = true)) {
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

		fun createIntent(context: Context, serverUrl: String, loginUrl: String): Intent {
			return Intent(context, CloudflareAccessLoginActivity::class.java).apply {
				putExtra(EXTRA_SERVER_URL, serverUrl)
				putExtra(EXTRA_LOGIN_URL, loginUrl)
			}
		}
	}
}
