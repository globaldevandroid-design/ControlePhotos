package fr.globaldev.controlephotos;

import android.app.Activity;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import android.content.ClipData;
import android.content.Intent;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
  public static class CameraFileProvider extends FileProvider {}

  private WebView web;
  private ValueCallback<Uri[]> pendingFiles;
  private String pendingBackup;
  private Uri cameraOutput;
  private static final int PICK_FILE = 41, SAVE_BACKUP = 42, TAKE_PHOTO = 43;
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
          if (params.isCaptureEnabled()) {
            launchCamera();
          } else {
            startActivityForResult(params.createIntent(), PICK_FILE);
          }
          return true;
        } catch (Exception e) {
          pendingFiles = null;
          cameraOutput = null;
          callback.onReceiveValue(null);
          Toast.makeText(MainActivity.this, "Impossible d’ouvrir la source de la photo ou du fichier.", Toast.LENGTH_LONG).show();
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
  private void launchCamera() throws IOException {
    File folder = new File(getCacheDir(), "camera");
    if (!folder.isDirectory() && !folder.mkdirs()) {
      throw new IOException("Impossible de créer le dossier de capture");
    }
    File image = new File(folder, "capture.jpg");
    if (image.exists() && !image.delete()) {
      throw new IOException("Impossible de remplacer la capture précédente");
    }
    cameraOutput = FileProvider.getUriForFile(this, getPackageName() + ".camera", image);
    Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
    intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutput);
    intent.setClipData(ClipData.newRawUri("capture", cameraOutput));
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    startActivityForResult(intent, TAKE_PHOTO);
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == PICK_FILE || request == TAKE_PHOTO) {
      ValueCallback<Uri[]> callback = pendingFiles;
      Uri capture = cameraOutput;
      pendingFiles = null;
      cameraOutput = null;
      if (callback != null) {
        Uri[] files = null;
        if (result == RESULT_OK) {
          files = request == TAKE_PHOTO
              ? (capture == null ? null : new Uri[] {capture})
              : WebChromeClient.FileChooserParams.parseResult(result, data);
        }
        callback.onReceiveValue(files);
      }
    }
    if (request == SAVE_BACKUP && result == RESULT_OK && data != null && pendingBackup != null) {
      try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
        stream.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
        Toast.makeText(this, "Sauvegarde exportée", Toast.LENGTH_SHORT).show();
      } catch (Exception e) { Toast.makeText(this, "Erreur de sauvegarde", Toast.LENGTH_LONG).show(); }
      pendingBackup = null;
    }
  }
  @Override public void onBackPressed() {
    web.evaluateJavascript("(function(){const editor = document.getElementById('editor');"
        + "if (!editor || !editor.open) return false;"
        + "document.getElementById('cancel').click(); return true;})()", handled -> {
      if (!"true".equals(handled)) {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
      }
    });
  }
}
