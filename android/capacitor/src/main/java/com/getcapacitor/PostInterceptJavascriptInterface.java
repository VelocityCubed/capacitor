package com.getcapacitor;


import android.content.Context;
import android.webkit.JavascriptInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class PostInterceptJavascriptInterface {
  public static final String TAG = "PostInterceptJavascriptInterface";

  private static String mInterceptHeader = null;
  private BridgeWebViewClient mWebViewClient = null;

  public PostInterceptJavascriptInterface(BridgeWebViewClient webViewClient) {
    mWebViewClient = webViewClient;
  }

  private static byte[] readFully(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    for (int count; (count = in.read(buffer)) != -1; ) {
      out.write(buffer, 0, count);
    }
    return out.toByteArray();
  }

  public static String enableIntercept(Context context, byte[] data) throws IOException {


    return "";
  }

  public class FormRequestContents {
    public String method = null;
    public String json = null;
    public String enctype = null;

    public FormRequestContents(String method, String json, String enctype) {
      this.method = method;
      this.json = json;
      this.enctype = enctype;
    }
  }

  public class AjaxRequestContents {
    public String method = null;
    public String body = null;

    public AjaxRequestContents(String method, String body) {
      this.method = method;
      this.body = body;
    }
  }

  public PostInterceptJavascriptInterface.FormRequestContents mNextFormRequestContents = null;


  public PostInterceptJavascriptInterface.AjaxRequestContents mNextAjaxRequestContents = null;


  @JavascriptInterface
  public void customAjax(final String method, final String body) {
    Logger.info(TAG, "Ajax data: " + method + " " + body);
    mNextAjaxRequestContents = new AjaxRequestContents(method, body);
  }

  @JavascriptInterface
  public void customSubmit(String json, String method, String enctype) {
    Logger.info(TAG, "Submit data: " + json + "\t" + method + "\t" + enctype);
    mNextFormRequestContents = new FormRequestContents(method, json, enctype);
  }
}
