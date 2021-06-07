package com.getcapacitor;

import android.net.Uri;
import android.os.Build;
import android.webkit.WebResourceRequest;

import androidx.annotation.RequiresApi;

import java.util.Map;


public class WebResourceRequestWithBody implements WebResourceRequest {

  private WebResourceRequest _inner;
  private PostInterceptJavascriptInterface _interceptor;

  public WebResourceRequestWithBody(WebResourceRequest request, PostInterceptJavascriptInterface interceptor) {
    _inner = request;
    _interceptor = interceptor;
  }

  public PostInterceptJavascriptInterface getInterceptor() {
    return _interceptor;
  }

  @Override
  public Uri getUrl() {
    return _inner.getUrl();
  }

  @Override
  public boolean isForMainFrame() {
    return _inner.isForMainFrame();
  }

  @RequiresApi(api = Build.VERSION_CODES.N)
  @Override
  public boolean isRedirect() {
    return _inner.isRedirect();
  }

  @Override
  public boolean hasGesture() {
    return _inner.hasGesture();
  }

  @Override
  public String getMethod() {
    return _inner.getMethod();
  }

  @Override
  public Map<String, String> getRequestHeaders() {
    return _inner.getRequestHeaders();
  }

}
