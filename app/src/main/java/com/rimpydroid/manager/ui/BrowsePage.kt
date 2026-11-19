package com.rimpydroid.manager.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rimpydroid.manager.AppLog
import com.rimpydroid.manager.service.TaskStatus
import java.util.UUID

private const val BROWSE_HOME = "https://steamcommunity.com/app/294100/workshop/"
private const val BROWSE_POPULAR =
    "https://steamcommunity.com/workshop/browse/?appid=294100&browsesort=trend&section=readytouseitems&actualsort=trend&p=1&days=7"
private const val BROWSE_NEWEST =
    "https://steamcommunity.com/workshop/browse/?appid=294100&browsesort=mostrecent&section=readytouseitems&actualsort=mostrecent&p=1"
private const val BROWSE_COLLECTIONS =
    "https://steamcommunity.com/workshop/browse/?appid=294100&browsesort=trend&section=collections&actualsort=trend&p=1&days=7"

private val itemPage = Regex("""/(?:sharedfiles|workshop)/filedetails/?\?(?:[^#]*&)?id=(\d{6,12})""")

/** The Workshop id of the item whose page is [url], or null when the page is something else. */
private fun itemIdOf(url: String): Long? = itemPage.find(url)?.groupValues?.get(1)?.toLongOrNull()

/**
 * What a page of the Workshop may call: it hands over the id of a mod, nothing else. Android offers this object to
 * every frame of the page, also to other sites embedded in it, so a call only counts when it carries [secret], which
 * only the script of this app (see [buttonScript]) knows.
 */
class BrowseBridge(private val secret: String, private val onDownload: (Long) -> Unit) {
    @JavascriptInterface
    fun download(id: String?, key: String?) {
        if (key == null || key != secret) return
        val value = id?.trim()?.toLongOrNull() ?: return
        if (value > 0) onDownload(value)
    }
}

/** Adds a Download button under every mod and collection on a Workshop page (also to the ones that load later). */
private fun buttonScript(label: String, secret: String): String {
    val text = label.replace("'", "").replace("\\", "")
    return """
(function () {
  try {
    if (window.__rimpy) return;
    window.__rimpy = true;
    var add = function () {
      var nodes = document.querySelectorAll('[data-publishedfileid]');
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        var id = node.getAttribute('data-publishedfileid');
        var host = node.closest('.workshopItem, .workshopItemCollection, .collectionItem') || node.parentElement;
        if (!id || !host || host.getAttribute('data-rimpy') === '1') continue;
        host.setAttribute('data-rimpy', '1');
        var b = document.createElement('button');
        b.textContent = '\u2B07 ' + '$text';
        b.style.cssText = 'display:block;margin:6px auto;padding:6px 14px;border:0;border-radius:14px;background:#1b7f3b;color:#fff;font-size:13px;font-weight:600;';
        (function (btn, fileId) {
          btn.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            RimpyDroid.download(fileId, '$secret');
            btn.textContent = '\u2713';
          }, true);
        })(b, id);
        host.appendChild(b);
      }
    };
    add();
    setInterval(add, 1500);
  } catch (e) {}
})();
"""
}

@SuppressLint("SetJavaScriptEnabled")
private fun createBrowser(
    context: Context,
    label: () -> String,
    onUrl: (String) -> Unit,
    onProgress: (Int) -> Unit,
    onDownload: (Long) -> Unit,
    onGone: () -> Unit,
): WebView {
    // Steam asks for a birth date before it shows some mods; these cookies answer it, and keep the page in English.
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    for (c in listOf("birthtime=-2208988799", "lastagecheckage=1-January-1900", "wants_mature_content=1", "Steam_Language=english")) {
        cookies.setCookie("https://steamcommunity.com", "$c; Path=/; Secure")
    }
    val secret = UUID.randomUUID().toString()
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // a Workshop page needs nothing from the phone's files, other apps' content or the location
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setGeolocationEnabled(false)
        addJavascriptInterface(BrowseBridge(secret, onDownload), "RimpyDroid")
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return false
                val scheme = target.scheme.orEmpty()
                val host = target.host.orEmpty()
                val onSteam = host == "steamcommunity.com" || host.endsWith(".steamcommunity.com")
                if (scheme == "https" && onSteam) return false
                if (scheme == "http" && onSteam) {
                    // Steam is only browsed over its secure address
                    view?.loadUrl(target.buildUpon().scheme("https").build().toString())
                    return true
                }
                if (scheme == "https" || scheme == "http") {
                    // other sites open in the phone's browser, not in this page
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        // nothing can open it
                    }
                }
                // everything else (steam://, intent:, file:, ...) is not opened at all
                return true
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // the page's process died: close the browser instead of letting Android stop the whole app
                view?.destroy()
                onGone()
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url != null) onUrl(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(buttonScript(label(), secret), null)
            }
        }
        loadUrl(BROWSE_HOME)
    }
}

/** The Steam Workshop inside the app, like a browser. A Download button appears under every mod, and on the page of a mod. */
@Composable
fun BrowsePage(vm: AppViewModel) {
    val tasks by vm.downloads.collectAsState()
    var web by remember { mutableStateOf<WebView?>(null) }
    var url by remember { mutableStateOf(BROWSE_HOME) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    val label = tr("Download")
    val itemId = itemIdOf(url)
    val busy = tasks.count { it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED }

    // Back goes to the previous Workshop page first, and closes the browser when there is none.
    BackHandler(enabled = canGoBack) { web?.goBack() }
    DisposableEffect(Unit) {
        onDispose {
            web?.let {
                it.stopLoading()
                it.destroy()
            }
        }
    }

    PageScaffold(
        title = tr("Steam Workshop"),
        onBack = { vm.closePage() },
        actions = {
            IconButton(onClick = { web?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = tr("Refresh")) }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = { web?.loadUrl(BROWSE_POPULAR) }, label = { Text(tr("Popular")) })
                AssistChip(onClick = { web?.loadUrl(BROWSE_NEWEST) }, label = { Text(tr("Newest")) })
                AssistChip(onClick = { web?.loadUrl(BROWSE_COLLECTIONS) }, label = { Text(tr("Collections")) })
            }
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(4.dp))
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    try {
                        createBrowser(
                            context = ctx,
                            label = { label },
                            onUrl = { next ->
                                url = next
                                canGoBack = web?.canGoBack() == true
                            },
                            onProgress = { progress = it },
                            onDownload = { id -> vm.downloadMissing(listOf(id)) },
                            onGone = {
                                web = null
                                vm.closePage()
                            },
                        ).also { web = it }
                    } catch (e: Throwable) {
                        // Android's WebView is missing, switched off or being updated: the page closes instead of the app crashing
                        AppLog.w("Browser", "The Steam browser could not be created", e)
                        Handler(Looper.getMainLooper()).post { vm.browserUnavailable() }
                        View(ctx)
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (itemId != null) tr("Download this mod") else tr("Open a mod to download it, or tap Download under a mod."),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (busy > 0) {
                        Text(tr("{0} downloading", busy), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (itemId != null) {
                        Button(onClick = { vm.downloadMissing(listOf(itemId)) }) { Text(label) }
                    }
                }
            }
        }
    }
}
