package fr.globaldev.controlephotos;

import android.app.Activity;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
  private WebView web;
  private ValueCallback<Uri[]> pendingFiles;
  private String pendingBackup;
  private static final int PICK_PHOTO = 41, SAVE_BACKUP = 42;
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    web = new WebView(this);
    setContentView(web);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(true);
    WebViewAssetLoader loader = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
    web.setWebViewClient(new WebViewClientCompat() {
      @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) { return loader.shouldInterceptRequest(request.getUrl()); }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
        if (pendingFiles != null) pendingFiles.onReceiveValue(null);
        pendingFiles = callback;
        try {
          Intent intent = params.createIntent();
          startActivityForResult(intent, PICK_PHOTO);
          return true;
        } catch (Exception e) {
          pendingFiles = null;
          callback.onReceiveValue(null);
          Toast.makeText(MainActivity.this, "Impossible d'ouvrir l'appareil photo", Toast.LENGTH_LONG).show();
          return true;
        }
      }
    });
    web.addJavascriptInterface(new Object() {
      @JavascriptInterface public void saveBackup(String json) {
        runOnUiThread(() -> {
          pendingBackup = json;
          Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
          i.addCategory(Intent.CATEGORY_OPENABLE);
          i.setType("application/json");
          i.putExtra(Intent.EXTRA_TITLE, "controle-photos.json");
          startActivityForResult(i, SAVE_BACKUP);
        });
      }
    }, "AndroidBridge");
    web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
  }
  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == PICK_PHOTO && pendingFiles != null) {
      pendingFiles.onReceiveValue(result == RESULT_OK ? WebChromeClient.FileChooserParams.parseResult(result, data) : null);
      pendingFiles = null;
    }
    if (request == SAVE_BACKUP && result == RESULT_OK && data != null && pendingBackup != null) {
      try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
        stream.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
        Toast.makeText(this, "Sauvegarde exportée", Toast.LENGTH_SHORT).show();
      } catch (Exception e) { Toast.makeText(this, "Erreur de sauvegarde", Toast.LENGTH_LONG).show(); }
      pendingBackup = null;
    }
  }
  @Override public void onBackPressed() { if(web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
