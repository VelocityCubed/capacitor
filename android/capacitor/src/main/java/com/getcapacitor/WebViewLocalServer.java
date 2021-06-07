/*
Copyright 2015 Google Inc. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package com.getcapacitor;

import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;


/**
 * Helper class meant to be used with the android.webkit.WebView class to enable hosting assets,
 * resources and other data on 'virtual' https:// URL.
 * Hosting assets and resources on https:// URLs is desirable as it is compatible with the
 * Same-Origin policy.
 * <p>
 * This class is intended to be used from within the
 * {@link android.webkit.WebViewClient#shouldInterceptRequest(android.webkit.WebView, String)} and
 * {@link android.webkit.WebViewClient#shouldInterceptRequest(android.webkit.WebView,
 * android.webkit.WebResourceRequest)}
 * methods.
 */
public class WebViewLocalServer {

  private static final String capacitorFileStart = Bridge.CAPACITOR_FILE_START;
  private static final String capacitorContentStart = Bridge.CAPACITOR_CONTENT_START;
  private String basePath;

  private final UriMatcher uriMatcher;
  private final AndroidProtocolHandler protocolHandler;
  private final ArrayList<String> authorities;
  private boolean isAsset;
  // Whether to route all requests to paths without extensions back to `index.html`
  private final boolean html5mode;
  private final JSInjector jsInjector;
  private final Bridge bridge;

  /**
   * A handler that produces responses for paths on the virtual asset server.
   * <p>
   * Methods of this handler will be invoked on a background thread and care must be taken to
   * correctly synchronize access to any shared state.
   * <p>
   * On Android KitKat and above these methods may be called on more than one thread. This thread
   * may be different than the thread on which the shouldInterceptRequest method was invoke.
   * This means that on Android KitKat and above it is possible to block in this method without
   * blocking other resources from loading. The number of threads used to parallelize loading
   * is an internal implementation detail of the WebView and may change between updates which
   * means that the amount of time spend blocking in this method should be kept to an absolute
   * minimum.
   */
  public abstract static class PathHandler {

    protected String mimeType;
    private String encoding;
    private String charset;
    private int statusCode;
    private String reasonPhrase;
    private Map<String, String> responseHeaders;

    public PathHandler() {
      this(null, null, 200, "OK", null);
    }

    public PathHandler(String encoding, String charset, int statusCode, String reasonPhrase, Map<String, String> responseHeaders) {
      this.encoding = encoding;
      this.charset = charset;
      this.statusCode = statusCode;
      this.reasonPhrase = reasonPhrase;
      Map<String, String> tempResponseHeaders;
      if (responseHeaders == null) {
        tempResponseHeaders = new HashMap<>();
      } else {
        tempResponseHeaders = responseHeaders;
      }
      tempResponseHeaders.put("Cache-Control", "no-cache");
      this.responseHeaders = tempResponseHeaders;
    }

    public InputStream handle(WebResourceRequest request) {
      return handle(request.getUrl());
    }

    public abstract InputStream handle(Uri url);

    public String getEncoding() {
      return encoding;
    }

    public String getCharset() {
      return charset;
    }

    public int getStatusCode() {
      return statusCode;
    }

    public String getReasonPhrase() {
      return reasonPhrase;
    }

    public Map<String, String> getResponseHeaders() {
      return responseHeaders;
    }
  }

  WebViewLocalServer(Context context, Bridge bridge, JSInjector jsInjector, ArrayList<String> authorities, boolean html5mode) {
    uriMatcher = new UriMatcher(null);
    this.html5mode = html5mode;
    this.protocolHandler = new AndroidProtocolHandler(context.getApplicationContext());
    this.authorities = authorities;
    this.bridge = bridge;
    this.jsInjector = jsInjector;
  }

  private static Uri parseAndVerifyUrl(String url) {
    if (url == null) {
      return null;
    }
    Uri uri = Uri.parse(url);
    if (uri == null) {
      Logger.error("Malformed URL: " + url);
      return null;
    }
    String path = uri.getPath();
    if (path == null || path.isEmpty()) {
      Logger.error("URL does not have a path: " + url);
      return null;
    }
    return uri;
  }

  /**
   * Attempt to retrieve the WebResourceResponse associated with the given <code>request</code>.
   * This method should be invoked from within
   * {@link android.webkit.WebViewClient#shouldInterceptRequest(android.webkit.WebView,
   * android.webkit.WebResourceRequest)}.
   *
   * @param request the request to process.
   * @return a response if the request URL had a matching handler, null if no handler was found.
   */
  public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
    Uri loadingUrl = request.getUrl();
    Logger.debug("request: " + request.getUrl().toString());
    PathHandler handler;
    synchronized (uriMatcher) {
      handler = (PathHandler) uriMatcher.match(request.getUrl());
    }
    if (handler == null) {
      return null;
    }

    if (isLocalFile(loadingUrl) || isMainUrl(loadingUrl) || !isAllowedUrl(loadingUrl)) {
      Logger.debug("Handling local request: " + request.getUrl().toString());
      return handleLocalRequest(request, handler);
    } else {
      return handleProxyRequest(request, handler);
    }
  }

  private boolean isLocalFile(Uri uri) {
    String path = uri.getPath();
    return path.startsWith(capacitorContentStart) || path.startsWith(capacitorFileStart);
  }

  private boolean isMainUrl(Uri loadingUrl) {
    return (bridge.getServerUrl() == null && loadingUrl.getHost().equalsIgnoreCase(bridge.getHost()));
  }

  private boolean isAllowedUrl(Uri loadingUrl) {
    return true;
   // return !(bridge.getServerUrl() == null && !bridge.getAppAllowNavigationMask().matches(loadingUrl.getHost()));
  }

  private WebResourceResponse handleLocalRequest(WebResourceRequest request, PathHandler handler) {
    String path = request.getUrl().getPath();

    if (request.getRequestHeaders().get("Range") != null) {
      InputStream responseStream = new LollipopLazyInputStream(handler, request);
      String mimeType = getMimeType(path, responseStream);
      Map<String, String> tempResponseHeaders = handler.getResponseHeaders();
      int statusCode = 206;
      try {
        int totalRange = responseStream.available();
        String rangeString = request.getRequestHeaders().get("Range");
        String[] parts = rangeString.split("=");
        String[] streamParts = parts[1].split("-");
        String fromRange = streamParts[0];
        int range = totalRange - 1;
        if (streamParts.length > 1) {
          range = Integer.parseInt(streamParts[1]);
        }
        tempResponseHeaders.put("Accept-Ranges", "bytes");
        tempResponseHeaders.put("Content-Range", "bytes " + fromRange + "-" + range + "/" + totalRange);
      } catch (IOException e) {
        statusCode = 404;
      }
      return new WebResourceResponse(
        mimeType,
        handler.getEncoding(),
        statusCode,
        handler.getReasonPhrase(),
        tempResponseHeaders,
        responseStream
      );
    }

    if (isLocalFile(request.getUrl())) {
      InputStream responseStream = new LollipopLazyInputStream(handler, request);
      String mimeType = getMimeType(request.getUrl().getPath(), responseStream);
      int statusCode = getStatusCode(responseStream, handler.getStatusCode());
      return new WebResourceResponse(
        mimeType,
        handler.getEncoding(),
        statusCode,
        handler.getReasonPhrase(),
        handler.getResponseHeaders(),
        responseStream
      );
    }

    if (path.equals("/cordova.js")) {
      return new WebResourceResponse(
        "application/javascript",
        handler.getEncoding(),
        handler.getStatusCode(),
        handler.getReasonPhrase(),
        handler.getResponseHeaders(),
        null
      );
    }

    if (path.equals("/") || (!request.getUrl().getLastPathSegment().contains(".") && html5mode)) {
      InputStream responseStream;
      try {
        String startPath = this.basePath + "/index.html";
        if (isAsset) {
          responseStream = protocolHandler.openAsset(startPath);
        } else {
          responseStream = protocolHandler.openFile(startPath);
        }
      } catch (IOException e) {
        Logger.error("Unable to open index.html", e);
        return null;
      }

      responseStream = jsInjector.getInjectedStream(responseStream);

      bridge.reset();
      int statusCode = getStatusCode(responseStream, handler.getStatusCode());
      return new WebResourceResponse(
        "text/html",
        handler.getEncoding(),
        statusCode,
        handler.getReasonPhrase(),
        handler.getResponseHeaders(),
        responseStream
      );
    }

    if ("/favicon.ico".equalsIgnoreCase(path)) {
      try {
        return new WebResourceResponse("image/png", null, null);
      } catch (Exception e) {
        Logger.error("favicon handling failed", e);
      }
    }

    int periodIndex = path.lastIndexOf(".");
    if (periodIndex >= 0) {
      String ext = path.substring(path.lastIndexOf("."));

      InputStream responseStream = new LollipopLazyInputStream(handler, request);

      // TODO: Conjure up a bit more subtlety than this
      if (ext.equals(".html")) {
        responseStream = jsInjector.getInjectedStream(responseStream);
        bridge.reset();
      }

      String mimeType = getMimeType(path, responseStream);
      int statusCode = getStatusCode(responseStream, handler.getStatusCode());
      return new WebResourceResponse(
        mimeType,
        handler.getEncoding(),
        statusCode,
        handler.getReasonPhrase(),
        handler.getResponseHeaders(),
        responseStream
      );
    }

    return null;
  }

  private String readAssetStream(InputStream stream) {
    try {
      final int bufferSize = 1024;
      final char[] buffer = new char[bufferSize];
      final StringBuilder out = new StringBuilder();
      Reader in = new InputStreamReader(stream, StandardCharsets.UTF_8);
      for (; ; ) {
        int rsz = in.read(buffer, 0, buffer.length);
        if (rsz < 0) break;
        out.append(buffer, 0, rsz);
      }
      return out.toString();
    } catch (Exception e) {
      Logger.error("Unable to process HTML asset file. This is a fatal error", e);
    }

    return "";
  }

  private String getWebrtcExample(){
    return "<!DOCTYPE html>\n" +
      "<!--\n" +
      " *  Copyright (c) 2015 The WebRTC project authors. All Rights Reserved.\n" +
      " *\n" +
      " *  Use of this source code is governed by a BSD-style license\n" +
      " *  that can be found in the LICENSE file in the root of the source\n" +
      " *  tree.\n" +
      "-->\n" +
      "<html>\n" +
      "<head>\n" +
      "\n" +
      "    <meta charset=\"utf-8\">\n" +
      "    <meta name=\"description\" content=\"WebRTC code samples\">\n" +
      "    <meta name=\"viewport\" content=\"width=device-width, user-scalable=yes, initial-scale=1, maximum-scale=1\">\n" +
      "    <meta itemprop=\"description\" content=\"Client-side WebRTC code samples\">\n" +
      "    <meta itemprop=\"image\" content=\"../../../images/webrtc-icon-192x192.png\">\n" +
      "    <meta itemprop=\"name\" content=\"WebRTC code samples\">\n" +
      "    <meta name=\"mobile-web-app-capable\" content=\"yes\">\n" +
      "    <meta id=\"theme-color\" name=\"theme-color\" content=\"#ffffff\">\n" +
      "\n" +
      "    <base target=\"_blank\">\n" +
      "\n" +
      "    <title>getUserMedia</title>\n" +
      "\n" +
      "\n" +
      "</head>\n" +
      "\n" +
      "<body>\n" +
      "\n" +
      "<div id=\"container\">\n" +
      "    <h1><a href=\"//webrtc.github.io/samples/\" title=\"WebRTC samples homepage\">WebRTC samples</a>\n" +
      "        <span>getUserMedia</span></h1>\n" +
      "\n" +
      "    <video id=\"gum-local\" autoplay playsinline></video>\n" +
      "    <button id=\"showVideo\">Open camera</button>\n" +
      "\n" +
      "    <div id=\"errorMsg\"></div>\n" +
      "\n" +
      "    <p class=\"warning\"><strong>Warning:</strong> if you're not using headphones, pressing play will cause feedback.</p>\n" +
      "\n" +
      "    <p>Display the video stream from <code>getUserMedia()</code> in a video element.</p>\n" +
      "\n" +
      "    <p>The <code>MediaStream</code> object <code>stream</code> passed to the <code>getUserMedia()</code> callback is in\n" +
      "        global scope, so you can inspect it from the console.</p>\n" +
      "\n" +
      "    <a href=\"https://github.com/webrtc/samples/tree/gh-pages/src/content/getusermedia/gum\"\n" +
      "       title=\"View source for this page on GitHub\" id=\"viewSource\">View source on GitHub</a>\n" +
      "</div>\n" +
      "\n" +
      "<script src=\"https://webrtc.github.io/adapter/adapter-latest.js\"></script>\n" +
      "\n" +
      "<script>\n" +
      "const constraints = window.constraints = {\n" +
      "  audio: false,\n" +
      "  video: true\n" +
      "};\n" +
      "\n" +
      "function handleSuccess(stream) {\n" +
      "  const video = document.querySelector('video');\n" +
      "  const videoTracks = stream.getVideoTracks();\n" +
      "  console.log('Got stream with constraints:', constraints);\n" +
      "  console.log(`Using video device: ${videoTracks[0].label}`);\n" +
      "  window.stream = stream; // make variable available to browser console\n" +
      "  video.srcObject = stream;\n" +
      "}\n" +
      "\n" +
      "function handleError(error) {\n" +
      "  if (error.name === 'ConstraintNotSatisfiedError') {\n" +
      "    const v = constraints.video;\n" +
      "    errorMsg(`The resolution ${v.width.exact}x${v.height.exact} px is not supported by your device.`);\n" +
      "  } else if (error.name === 'PermissionDeniedError') {\n" +
      "    errorMsg('Permissions have not been granted to use your camera and ' +\n" +
      "      'microphone, you need to allow the page access to your devices in ' +\n" +
      "      'order for the demo to work.');\n" +
      "  }\n" +
      "  errorMsg(`getUserMedia error: ${error.name}`, error);\n" +
      "}\n" +
      "\n" +
      "function errorMsg(msg, error) {\n" +
      "  const errorElement = document.querySelector('#errorMsg');\n" +
      "  errorElement.innerHTML += `<p>${msg}</p>`;\n" +
      "  if (typeof error !== 'undefined') {\n" +
      "    console.error(error);\n" +
      "  }\n" +
      "}\n" +
      "\n" +
      "async function init(e) {\n" +
      "  try {\n" +
      "    const stream = await navigator.mediaDevices.getUserMedia(constraints);\n" +
      "    handleSuccess(stream);\n" +
      "    e.target.disabled = true;\n" +
      "  } catch (e) {\n" +
      "    handleError(e);\n" +
      "  }\n" +
      "}\n" +
      "\n" +
      "document.querySelector('#showVideo').addEventListener('click', e => init(e));\n" +
      "</script>\n" +
      "\n" +
      "</body>\n" +
      "</html>\n" +
      "\n";
  }

  private WebResourceResponse getRedirectResponse(String location, PathHandler handler) {
    String js = "<script type=\"text/javascript\">" + jsInjector.getScriptString() + "</script>";
    String html = "<!DOCTYPE html>\n" +
      "<html>\n" +
      "<body><head></head>\n" +
      "\n" +
      "<h2>Redirect to a Webpage</h2>\n" +
      "<p>The replace() method replaces the current document with a new one:</p>\n" +
      "\n" +
      "<button onclick=\"myFunction()\">Replace document</button>\n" +
      "\n" +
      "<script type=\"text/javascript\">\n" +
      "  window.location.href = \"" + location + "\";\n" +
      "</script>\n" +
      "\n" +
      "</body>\n" +
      "</html> \n";
    if (html.contains("<head>")) {
      html = html.replace("<head>", "<head>\n" + js + "\n");
    } else if (html.contains("</head>")) {
      html = html.replace("</head>", js + "\n" + "</head>");
    } else {
      Logger.error("Unable to inject Capacitor, Plugins won't work");
    }

    ByteArrayInputStream newStream = new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
    Logger.error("MODIFIED RESPONSE: ");
    return new WebResourceResponse("text/html", handler.getEncoding(), 200, "OK", handler.getResponseHeaders(), newStream);
  }


  private void trustEveryone() {
    try {
      HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
        public boolean verify(String hostname, SSLSession session) {
          return true;
        }
      });
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new X509TrustManager[]{new X509TrustManager() {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
        }

        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0];
        }
      }}, new SecureRandom());
      HttpsURLConnection.setDefaultSSLSocketFactory(
        context.getSocketFactory());
    } catch (Exception e) { // should never happen
      e.printStackTrace();
    }
  }

  /**
   * Instead of reading files from the filesystem/assets, proxy through to the URL
   * and let an external server handle it.
   *
   * @param request
   * @param handler
   * @return
   */
  private WebResourceResponse handleProxyRequest(WebResourceRequest request, PathHandler handler) {

    String url = request.getUrl().toString();
    final String method = request.getMethod();

    Logger.error("handleProxyRequest: " + url);

    if (url.endsWith("/cancelled?")) {
      Logger.error("TOKEN");
    }

    try {
      Map<String, String> headers = request.getRequestHeaders();
      boolean isHtmlText = false;

      trustEveryone();

      HttpsURLConnection conn = (HttpsURLConnection) new URL(url).openConnection();

      // HEADERS
      for (Map.Entry<String, String> header : headers.entrySet()) {
        conn.setRequestProperty(header.getKey(), header.getValue());
      }
      conn.setInstanceFollowRedirects(true);
      conn.setRequestMethod(method);
      conn.setReadTimeout(30 * 1000);
      conn.setConnectTimeout(30 * 1000);
      conn.setInstanceFollowRedirects(false);

   /*   if (url.contains("/confirmed?")) {

      } */

      // COOKIES
      String getCookie = CookieManager.getInstance().getCookie(url);
      if (getCookie != null) {
        conn.setRequestProperty("Cookie", getCookie);
      }

      //BODY
      if (request instanceof WebResourceRequestWithBody) {
        WebResourceRequestWithBody wwwb = ((WebResourceRequestWithBody) request);
        PostInterceptJavascriptInterface.AjaxRequestContents content = wwwb.getInterceptor().mNextAjaxRequestContents;
        if (!method.equals("GET") && content != null && content.body != null) {
          Logger.error("handleProxyRequest: " + content.body);
          conn.setDoOutput(true);
          try (OutputStream os = conn.getOutputStream()) {
            byte[] input = content.body.getBytes("utf-8");
            os.write(input, 0, input.length);
          }
        }
      }


      int responseCode = conn.getResponseCode();
      String responseReason = conn.getResponseMessage();

      // RESULT
      Map<String, List<String>> responseHeadersRaw = conn.getHeaderFields();
      Map<String, String> responseHeaders = new HashMap<>();
      for (Map.Entry<String, List<String>> header : responseHeadersRaw.entrySet()) {
        for (String value : header.getValue()) {
          String key = header.getKey();
          if (key != null) {
            if (key.toLowerCase().contains("set-cookie"))
              CookieManager.getInstance().setCookie(url, value);
            else
              responseHeaders.put(key.toLowerCase(), value);
          }
        }
      }

      InputStream responseStream = null;
      try {
        int responseLength = conn.getContentLength();
        if (responseLength > 0)
          responseStream = conn.getInputStream();
      } catch (FileNotFoundException fex) {
      }
      if (responseStream == null)
        responseStream = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));

      String contentType = responseHeaders.get("content-type");
      String responseMimeType = null;
      if (contentType != null) {
        responseMimeType = contentType.split(";")[0];
      } else {
        responseMimeType = "text/json";
        //getMimeType(url, responseStream);
      }

      if (responseCode == 302) {
        String location = responseHeaders.get("location");
        return getRedirectResponse(location, handler);
      }

      responseStream = jsInjector.getInterceptStream(responseStream);
      //bridge.reset();


      return new WebResourceResponse(responseMimeType, handler.getEncoding(), responseCode, responseReason, responseHeaders, responseStream);

    } catch (
      Exception ex) {
      bridge.handleAppUrlLoadError(ex);

    }

    return null;

  }

  private String getMimeType(String path, InputStream stream) {
    String mimeType = null;
    try {
      mimeType = URLConnection.guessContentTypeFromName(path); // Does not recognize *.js
      if (mimeType != null && path.endsWith(".js") && mimeType.equals("image/x-icon")) {
        Logger.debug("We shouldn't be here");
      }
      if (mimeType == null) {
        if (path.endsWith(".js") || path.endsWith(".mjs")) {
          // Make sure JS files get the proper mimetype to support ES modules
          mimeType = "application/javascript";
        } else if (path.endsWith(".wasm")) {
          mimeType = "application/wasm";
        } else {
          mimeType = URLConnection.guessContentTypeFromStream(stream);
        }
      }
    } catch (Exception ex) {
      Logger.error("Unable to get mime type" + path, ex);
    }
    return mimeType;
  }

  private int getStatusCode(InputStream stream, int defaultCode) {
    int finalStatusCode = defaultCode;
    try {
      if (stream.available() == -1) {
        finalStatusCode = 404;
      }
    } catch (IOException e) {
      finalStatusCode = 500;
    }
    return finalStatusCode;
  }

  /**
   * Registers a handler for the given <code>uri</code>. The <code>handler</code> will be invoked
   * every time the <code>shouldInterceptRequest</code> method of the instance is called with
   * a matching <code>uri</code>.
   *
   * @param uri     the uri to use the handler for. The scheme and authority (domain) will be matched
   *                exactly. The path may contain a '*' element which will match a single element of
   *                a path (so a handler registered for /a/* will be invoked for /a/b and /a/c.html
   *                but not for /a/b/b) or the '**' element which will match any number of path
   *                elements.
   * @param handler the handler to use for the uri.
   */
  void register(Uri uri, PathHandler handler) {
    synchronized (uriMatcher) {
      uriMatcher.addURI(uri.getScheme(), uri.getAuthority(), uri.getPath(), handler);
    }
  }

  /**
   * Hosts the application's assets on an https:// URL. Assets from the local path
   * <code>assetPath/...</code> will be available under
   * <code>https://{uuid}.androidplatform.net/assets/...</code>.
   *
   * @param assetPath the local path in the application's asset folder which will be made
   *                  available by the server (for example "/www").
   * @return prefixes under which the assets are hosted.
   */
  public void hostAssets(String assetPath) {
    this.isAsset = true;
    this.basePath = assetPath;
    createHostingDetails();
  }

  /**
   * Hosts the application's files on an https:// URL. Files from the basePath
   * <code>basePath/...</code> will be available under
   * <code>https://{uuid}.androidplatform.net/...</code>.
   *
   * @param basePath the local path in the application's data folder which will be made
   *                 available by the server (for example "/www").
   * @return prefixes under which the assets are hosted.
   */
  public void hostFiles(final String basePath) {
    this.isAsset = false;
    this.basePath = basePath;
    createHostingDetails();
  }

  private void createHostingDetails() {
    final String assetPath = this.basePath;

    if (assetPath.indexOf('*') != -1) {
      throw new IllegalArgumentException("assetPath cannot contain the '*' character.");
    }

    PathHandler handler = new PathHandler() {
      @Override
      public InputStream handle(Uri url) {
        InputStream stream = null;
        String path = url.getPath();
        try {
          if (path.startsWith(capacitorContentStart)) {
            stream = protocolHandler.openContentUrl(url);
          } else if (path.startsWith(capacitorFileStart) || !isAsset) {
            if (!path.startsWith(capacitorFileStart)) {
              path = basePath + url.getPath();
            }
            stream = protocolHandler.openFile(path);
          } else {
            stream = protocolHandler.openAsset(assetPath + path);
          }
        } catch (IOException e) {
          Logger.error("Unable to open asset URL: " + url);
          return null;
        }

        return stream;
      }
    };

    for (String authority : authorities) {
      registerUriForScheme(Bridge.CAPACITOR_HTTP_SCHEME, handler, authority);
      registerUriForScheme(Bridge.CAPACITOR_HTTPS_SCHEME, handler, authority);

      String customScheme = this.bridge.getScheme();
      if (!customScheme.equals(Bridge.CAPACITOR_HTTP_SCHEME) && !customScheme.equals(Bridge.CAPACITOR_HTTPS_SCHEME)) {
        registerUriForScheme(customScheme, handler, authority);
      }
    }
  }

  private void registerUriForScheme(String scheme, PathHandler handler, String authority) {
    Uri.Builder uriBuilder = new Uri.Builder();
    uriBuilder.scheme(scheme);
    uriBuilder.authority(authority);
    uriBuilder.path("");
    Uri uriPrefix = uriBuilder.build();

    register(Uri.withAppendedPath(uriPrefix, "/"), handler);
    register(Uri.withAppendedPath(uriPrefix, "**"), handler);
  }

  /**
   * The KitKat WebView reads the InputStream on a separate threadpool. We can use that to
   * parallelize loading.
   */
  private abstract static class LazyInputStream extends InputStream {

    protected final PathHandler handler;
    private InputStream is = null;

    public LazyInputStream(PathHandler handler) {
      this.handler = handler;
    }

    private InputStream getInputStream() {
      if (is == null) {
        is = handle();
      }
      return is;
    }

    protected abstract InputStream handle();

    @Override
    public int available() throws IOException {
      InputStream is = getInputStream();
      return (is != null) ? is.available() : -1;
    }

    @Override
    public int read() throws IOException {
      InputStream is = getInputStream();
      return (is != null) ? is.read() : -1;
    }

    @Override
    public int read(byte[] b) throws IOException {
      InputStream is = getInputStream();
      return (is != null) ? is.read(b) : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      InputStream is = getInputStream();
      return (is != null) ? is.read(b, off, len) : -1;
    }

    @Override
    public long skip(long n) throws IOException {
      InputStream is = getInputStream();
      return (is != null) ? is.skip(n) : 0;
    }
  }

  // For L and above.
  private static class LollipopLazyInputStream extends LazyInputStream {

    private WebResourceRequest request;
    private InputStream is;

    public LollipopLazyInputStream(PathHandler handler, WebResourceRequest request) {
      super(handler);
      this.request = request;
    }

    @Override
    protected InputStream handle() {
      return handler.handle(request);
    }

  }

  public String getBasePath() {
    return this.basePath;
  }
}

