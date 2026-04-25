package org.jellyfin.androidtv.ui.cloudflare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.jellyfin.androidtv.cloudflare.CloudflareAccessAuthManager
import org.jellyfin.androidtv.databinding.ActivityCloudflareAccessLoginBinding
import org.koin.android.ext.android.inject

class CloudflareAccessLoginActivity : AppCompatActivity() {
	private lateinit var binding: ActivityCloudflareAccessLoginBinding
	private val cloudflareAccessAuthManager: CloudflareAccessAuthManager by inject()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityCloudflareAccessLoginBinding.inflate(layoutInflater)
		setContentView(binding.root)

		val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
		val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL)
		if (serverUrl.isNullOrBlank() || loginUrl.isNullOrBlank()) {
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

		binding.webview.webViewClient = object : WebViewClient() {
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
