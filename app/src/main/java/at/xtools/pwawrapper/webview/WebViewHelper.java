package at.xtools.pwawrapper.webview;

import static at.xtools.pwawrapper.Constants.WEBAPP_HOST;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Map;
import java.util.Objects;

import at.xtools.pwawrapper.Constants;
import at.xtools.pwawrapper.R;
import at.xtools.pwawrapper.ui.UIManager;

public class WebViewHelper {
    // Instance variables
    private Activity activity;
    private UIManager uiManager;
    private WebView webView;
    private WebSettings webSettings;

    public WebViewHelper(Activity activity, UIManager uiManager) {
        this.activity = activity;
        this.uiManager = uiManager;
        this.webView = activity.findViewById(R.id.webView);
        this.webSettings = webView.getSettings();
    }

    /**
     * Simple helper method checking if connected to Network.
     * Doesn't check for actual Internet connection!
     *
     * @return {boolean} True if connected to Network.
     */
    private boolean isNetworkAvailable() {
        ConnectivityManager manager =
            (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = manager.getActiveNetworkInfo();

        // Wifi or Mobile Network is present and connected
        return networkInfo != null && networkInfo.isConnected();
    }

    // manipulate cache settings to make sure our PWA gets updated
    private void useCache(Boolean use) {
        if (use) {
            webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        } else {
            webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        }
    }

    // public method changing cache settings according to network availability.
    // retrieve content from cache primarily if not connected,
    // allow fetching from web too otherwise to get updates.
    public void forceCacheIfOffline() {
        useCache(!isNetworkAvailable());
    }

    // handles initial setup of webview
    public void setupWebView() {
        // accept cookies
        CookieManager.getInstance().setAcceptCookie(true);
        // enable JS
        webSettings.setJavaScriptEnabled(true);
        // must be set for our js-popup-blocker:
        webSettings.setSupportMultipleWindows(true);

        // PWA settings
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);

        // enable mixed content mode conditionally
        if (Constants.ENABLE_MIXED_CONTENT) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        // retrieve content from cache primarily if not connected
        forceCacheIfOffline();

        // set User Agent
        if (Constants.OVERRIDE_USER_AGENT || Constants.POSTFIX_USER_AGENT) {
            String userAgent = webSettings.getUserAgentString();
            if (Constants.OVERRIDE_USER_AGENT) {
                userAgent = Constants.USER_AGENT;
            }
            if (Constants.POSTFIX_USER_AGENT) {
                userAgent = userAgent + " " + Constants.USER_AGENT_POSTFIX;
            }
            webSettings.setUserAgentString(userAgent);
        }

        // enable HTML5-support
        webView.setWebChromeClient(new WebChromeClient() {
            //simple yet effective redirect/popup blocker
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                Message href = view.getHandler().obtainMessage();
                view.requestFocusNodeHref(href);
                final String popupUrl = href.getData().getString("url");
                if (popupUrl != null) {
                    //it's null for most rouge browser hijack ads
                    webView.loadUrl(popupUrl);
                    return true;
                }
                return false;
            }

            // update ProgressBar
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                uiManager.setLoadingProgress(newProgress);
                super.onProgressChanged(view, newProgress);
            }
        });

        // Set up Webview client
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // new API method calls this on every error for each resource.
                // we only want to interfere if the page itself got problems.
                String url = request.getUrl().toString();
                if (Objects.equals(view.getUrl(), url)) {
                    handleLoadError(error.getErrorCode());
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return overrideUrlLoading(view, request);
            }
        });
    }

    private boolean overrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        Map<String, String> headers = request.getRequestHeaders();

        for (SocialNetworks socialNetwork : SocialNetworks.values()) {
            if (url.contains(socialNetwork.getUrl())) {
                openApp(socialNetwork.getPackageName(), url);
                return true;
            }
        }

        if (url.contains(WEBAPP_HOST)) {
            uiManager.setLoading(true);
            view.loadUrl(url, headers);
            return false;
        } else {
            openBrowser(url);
            return true;
        }
    }

    // Lifecycle callbacks
    public void onPause() {
        webView.onPause();
    }

    public void onResume() {
        webView.onResume();
    }

    // show "no app found" dialog
    private void showNoAppDialog(Activity thisActivity) {
        new AlertDialog.Builder(thisActivity)
            .setTitle(R.string.noapp_heading)
            .setMessage(R.string.noapp_description)
            .show();
    }

    // handle load errors
    private void handleLoadError(int errorCode) {
        if (errorCode != WebViewClient.ERROR_UNSUPPORTED_SCHEME) {
            uiManager.setOffline(true);
        } else {
            // Unsupported Scheme, recover
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    goBack();
                }
            }, 100);
        }
    }

    // handle back button press
    public boolean goBack() {
        if (webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }

    // load app startpage
    public void loadHome() {
        webView.loadUrl(Constants.WEBAPP_URL);
    }

    // load URL from intent
    public void loadIntentUrl(String url) {
        if (!url.isEmpty() && url.contains(WEBAPP_HOST)) {
            webView.loadUrl(url);
        } else {
            // Fallback
            loadHome();
        }
    }

    private void openApp(String packageName, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setPackage(packageName);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // App not installed, open in browser instead
            openBrowser(url);
        }
    }

    private void openBrowser(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            showNoAppDialog(activity);
        }
    }
}
