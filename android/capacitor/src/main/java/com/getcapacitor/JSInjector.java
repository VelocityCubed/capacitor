package com.getcapacitor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * JSInject is responsible for returning Capacitor's core
 * runtime JS and any plugin JS back into HTML page responses
 * to the client.
 */
class JSInjector {

  private String globalJS;
  private String bridgeJS;
  private String pluginJS;
  private String cordovaJS;
  private String cordovaPluginsJS;
  private String cordovaPluginsFileJS;
  private String localUrlJS;

  public JSInjector(
    String globalJS,
    String bridgeJS,
    String pluginJS,
    String cordovaJS,
    String cordovaPluginsJS,
    String cordovaPluginsFileJS,
    String localUrlJS
  ) {
    this.globalJS = globalJS;
    this.bridgeJS = bridgeJS;
    this.pluginJS = pluginJS;
    this.cordovaJS = cordovaJS;
    this.cordovaPluginsJS = cordovaPluginsJS;
    this.cordovaPluginsFileJS = cordovaPluginsFileJS;
    this.localUrlJS = localUrlJS;
  }

  /**
   * Generates injectable JS content.
   * This may be used in other forms of injecting that aren't using an InputStream.
   *
   * @return
   */
  public String getScriptString() {
    return (
      globalJS +
        "\n\n" +
        bridgeJS +
        "\n\n" +
        pluginJS +
        "\n\n" +
        cordovaJS +
        "\n\n" +
        cordovaPluginsFileJS +
        "\n\n" +
        cordovaPluginsJS +
        "\n\n" +
        localUrlJS
    );
  }

  /**
   * Given an InputStream from the web server, prepend it with
   * our JS stream
   *
   * @param responseStream
   * @return
   */
  public InputStream getInjectedStream(InputStream responseStream) {
    String js = getInjectorString() + "<script type=\"text/javascript\">" + getScriptString() + "</script>";
    String html = this.readAssetStream(responseStream);
    if (html.contains("<head>")) {
      html = html.replace("<head>", "<head>\n" + js + "\n");
    } else if (html.contains("</head>")) {
      html = html.replace("</head>", js + "\n" + "</head>");
    } else {
      Logger.error("Unable to inject Capacitor, Plugins won't work");
    }
    return new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
  }

  private String getInjectorString() {
    return "<script language=\"JavaScript\">\n" +
      "    function interceptor(e) {\n" +
      "        var frm = e ? e.target : this;\n" +
      "        interceptor_onsubmit(frm);\n" +
      "        frm._submit();\n" +
      "    }\n" +
      "\n" +
      "    function interceptor_onsubmit(f) {\n" +
      "        var jsonArr = [];\n" +
      "        for (i = 0; i < f.elements.length; i++) {\n" +
      "            var parName = f.elements[i].name;\n" +
      "            var parValue = f.elements[i].value;\n" +
      "            var parType = f.elements[i].type;\n" +
      "\n" +
      "            jsonArr.push({\n" +
      "                name : parName,\n" +
      "                value : parValue,\n" +
      "                type : parType\n" +
      "            });\n" +
      "        }\n" +
      "\n" +
      "        interception.customSubmit(JSON.stringify(jsonArr),\n" +
      "                f.attributes['method'] === undefined ? null\n" +
      "                        : f.attributes['method'].nodeValue,\n" +
      "                f.attributes['enctype'] === undefined ? null\n" +
      "                        : f.attributes['enctype'].nodeValue);\n" +
      "    }\n" +
      "if (!HTMLFormElement.prototype._submit) { \n" +
      "    HTMLFormElement.prototype._submit = HTMLFormElement.prototype.submit;\n" +
      "    HTMLFormElement.prototype.submit = interceptor;\n" +
      "\n" +
      "    window.addEventListener('submit', function(e) {\n" +
      "        interceptor(e);\n" +
      "    }, true);\n" +
      "\n" +
      "\n" +
      "} \n" +
      "if (!window.oldSend) { \n" +
      "   window.oldSend = XMLHttpRequest.prototype.send; \n" +
      "   XMLHttpRequest.prototype.send = function(data) {\n" +
      "     if (data) { interception.customAjax('method', data); }\n" +
      "     return window.oldSend.call(this, data);\n" +
      "} }" +
      "</script>";
  }

  public InputStream getInterceptStream(InputStream responseStream) {
    String js = getInjectorString();
    String html = this.readAssetStream(responseStream);
    if (html.contains("<head>")) {
      html = html.replace("<head>", "<head>\n" + js + "\n");
    } else if (html.contains("</head>")) {
      html = html.replace("</head>", js + "\n" + "</head>");
    } else {
      Logger.error("Unable to inject interceptor");
    }
    return new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
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
}
