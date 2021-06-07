package com.getcapacitor;

import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.RequiresApi;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class BridgeWebViewClient extends WebViewClient {

  private Bridge bridge;

  private PostInterceptJavascriptInterface mJSSubmitIntercept = null;

  public BridgeWebViewClient(Bridge bridge) {
    this.bridge = bridge;
    mJSSubmitIntercept = new PostInterceptJavascriptInterface(this);
    bridge.getWebView().addJavascriptInterface(mJSSubmitIntercept, "interception");
  }

  @Override
  public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {

    WebResourceRequestWithBody wrappedRequest = new WebResourceRequestWithBody(request, mJSSubmitIntercept);

    return bridge.getLocalServer().shouldInterceptRequest(wrappedRequest);
  }

  @Override
  public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
    Uri url = request.getUrl();
    return bridge.launchIntent(url);
  }


  @Override
  public boolean shouldOverrideUrlLoading(WebView view, String url) {
    return bridge.launchIntent(Uri.parse(url));
  }

  @Override
  public void onPageStarted(WebView view, String url, Bitmap favicon) {
    Logger.error("onPageStarted: " + url);
  }

  @Override
  public void onPageFinished(WebView view, String url) {
    Logger.error("onPageFinished: " + url);
    super.onPageFinished(view, url);

    List<WebViewListener> webViewListeners = bridge.getWebViewListeners();

    if (webViewListeners != null && view.getProgress() == 100) {
      for (WebViewListener listener : bridge.getWebViewListeners()) {
        listener.onPageLoaded(view);
      }
    }
  }


  @Override
  public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
    Logger.error("onReceivedError 1");
  }


  @RequiresApi(api = Build.VERSION_CODES.M)
  @Override
  public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError rerr) {
    String url = req.getUrl().toString();
    Logger.error("onReceivedError 2: " + url);
  }


  @Override
  public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
    super.onReceivedSslError(view, handler, error);
  }

  @Override
  public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
    Logger.error("onReceivedHttpError: " + request.getUrl());
  }

  @Override
  public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
    //return true;
    return super.onRenderProcessGone(view, detail);
  }

}

