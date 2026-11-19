package com.ramnoel91.cardwallet;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

public class MainActivity extends Activity {
    private static final int PICK_FILES = 1;
    private static final int SAVE_FILE = 2;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/www/index.html";

    private WebView web;
    private ValueCallback<Uri[]> pending;
    private File cameraFile;
    private Uri cameraUri;
    private File saveTemp;
    private Writer saveWriter;
    private TextRecognizer recognizer;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // Serve the app from a real https origin so saved data (IndexedDB) works normally
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        web.addJavascriptInterface(new Bridge(), "CardApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // tel:, sms:, mailto:, maps and websites open in the phone's own apps
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    if ("tel".equals(u.getScheme())) i = new Intent(Intent.ACTION_DIAL, u);
                    startActivity(i);
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                cameraFile = null;
                cameraUri = null;

                boolean images = false;
                String[] types = params.getAcceptTypes();
                if (types != null) for (String t : types) if (t != null && t.startsWith("image")) images = true;

                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType(images ? "image/*" : "*/*");

                if (images) {
                    Intent cam = cameraIntent();
                    if (cam != null && params.isCaptureEnabled()) {
                        // "Scan a card" goes straight to the camera
                        try {
                            startActivityForResult(cam, PICK_FILES);
                            return true;
                        } catch (Exception e) {
                            cameraFile = null;
                            cameraUri = null;
                            cam = null;
                        }
                    }
                    Intent chooser = Intent.createChooser(pick, "Add photo");
                    if (cam != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                    try {
                        startActivityForResult(chooser, PICK_FILES);
                        return true;
                    } catch (Exception e) {
                        pending = null;
                        return false;
                    }
                }

                try {
                    startActivityForResult(Intent.createChooser(pick, "Choose backup file"), PICK_FILES);
                } catch (Exception e) {
                    pending = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(web);
        if (state != null) web.restoreState(state);
        else web.loadUrl(START);
    }

    private Intent cameraIntent() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            cameraFile = new File(dir, "card_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraFile);
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            cam.setClipData(ClipData.newRawUri("photo", cameraUri));
            return cam;
        } catch (Exception e) {
            cameraFile = null;
            cameraUri = null;
            return null;
        }
    }

    private void js(final String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    /** Called from the page as window.CardApp */
    private class Bridge {
        @JavascriptInterface
        public boolean copyText(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Card Wallet", text));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void share(final String subject, final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, subject);
                i.putExtra(Intent.EXTRA_TEXT, text);
                try { startActivity(Intent.createChooser(i, "Share contact")); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void addContact(final String json) {
            runOnUiThread(() -> {
                try {
                    JSONObject o = new JSONObject(json);
                    Intent i = new Intent(ContactsContract.Intents.Insert.ACTION);
                    i.setType(ContactsContract.RawContacts.CONTENT_TYPE);
                    i.putExtra(ContactsContract.Intents.Insert.NAME, o.optString("name"));
                    i.putExtra(ContactsContract.Intents.Insert.COMPANY, o.optString("company"));
                    i.putExtra(ContactsContract.Intents.Insert.JOB_TITLE, o.optString("title"));
                    i.putExtra(ContactsContract.Intents.Insert.PHONE, o.optString("phone"));
                    i.putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_WORK);
                    i.putExtra(ContactsContract.Intents.Insert.EMAIL, o.optString("email"));
                    i.putExtra(ContactsContract.Intents.Insert.EMAIL_TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK);
                    i.putExtra(ContactsContract.Intents.Insert.POSTAL, o.optString("address"));
                    i.putExtra(ContactsContract.Intents.Insert.NOTES, o.optString("notes"));
                    startActivity(i);
                } catch (Exception e) {
                    js("window.__cwSaveDone && window.__cwSaveDone('error')");
                }
            });
        }

        @JavascriptInterface
        public void addCalendar(final String title, final String desc, final String ymd) {
            runOnUiThread(() -> {
                try {
                    String[] p = ymd.split("-");
                    Calendar c = Calendar.getInstance();
                    c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), 9, 0, 0);
                    Intent i = new Intent(Intent.ACTION_INSERT)
                            .setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, title)
                            .putExtra(CalendarContract.Events.DESCRIPTION, desc)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.getTimeInMillis())
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.getTimeInMillis() + 30 * 60 * 1000);
                    startActivity(i);
                } catch (Exception ignored) { }
            });
        }

        /** Calendar event with optional start/end times ("HH:mm"); all-day when no start time */
        @JavascriptInterface
        public void addCalendarAt(final String title, final String desc, final String ymd,
                                  final String start, final String end, final String location) {
            runOnUiThread(() -> {
                try {
                    String[] p = ymd.split("-");
                    int y = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]) - 1, d = Integer.parseInt(p[2]);
                    Intent i = new Intent(Intent.ACTION_INSERT)
                            .setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, title)
                            .putExtra(CalendarContract.Events.DESCRIPTION, desc)
                            .putExtra(CalendarContract.Events.EVENT_LOCATION, location);
                    Calendar b = Calendar.getInstance();
                    if (start != null && start.contains(":")) {
                        String[] s = start.split(":");
                        b.set(y, m, d, Integer.parseInt(s[0]), Integer.parseInt(s[1]), 0);
                        long endMs = b.getTimeInMillis() + 60 * 60 * 1000;
                        if (end != null && end.contains(":")) {
                            String[] e = end.split(":");
                            Calendar c = Calendar.getInstance();
                            c.set(y, m, d, Integer.parseInt(e[0]), Integer.parseInt(e[1]), 0);
                            if (c.getTimeInMillis() <= b.getTimeInMillis()) c.add(Calendar.DATE, 1);
                            endMs = c.getTimeInMillis();
                        }
                        i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, b.getTimeInMillis());
                        i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs);
                    } else {
                        b.set(y, m, d, 0, 0, 0);
                        i.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true);
                        i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, b.getTimeInMillis());
                    }
                    startActivity(i);
                } catch (Exception ignored) { }
            });
        }

        /** Reads the text on a card photo, on the phone, with no internet needed */
        @JavascriptInterface
        public void recognize(final String base64) {
            new Thread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp == null) { sendOcr(null); return; }
                    if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                    InputImage img = InputImage.fromBitmap(bmp, 0);
                    recognizer.process(img)
                            .addOnSuccessListener(MainActivity.this::sendOcr)
                            .addOnFailureListener(e -> sendOcr(null));
                } catch (Throwable t) {
                    sendOcr(null);
                }
            }).start();
        }

        @JavascriptInterface
        public void saveStart() {
            try { if (saveWriter != null) saveWriter.close(); } catch (Exception ignored) { }
            try {
                saveTemp = new File(getCacheDir(), "export.tmp");
                saveWriter = new OutputStreamWriter(new FileOutputStream(saveTemp), StandardCharsets.UTF_8);
            } catch (Exception e) {
                saveWriter = null;
            }
        }

        @JavascriptInterface
        public boolean saveAppend(String part) {
            if (saveWriter == null) return false;
            try { saveWriter.write(part); return true; } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void saveFinish(final String filename) {
            try { if (saveWriter != null) saveWriter.close(); } catch (Exception ignored) { }
            saveWriter = null;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(filename.endsWith(".csv") ? "text/csv" : "application/json");
                i.putExtra(Intent.EXTRA_TITLE, filename);
                try { startActivityForResult(i, SAVE_FILE); } catch (Exception e) { notifySave("error"); }
            });
        }

        @JavascriptInterface
        public void setDark(final boolean dark) {
            runOnUiThread(() -> {
                Window w = getWindow();
                w.setStatusBarColor(dark ? 0xFF13392F : 0xFF0E5A4A);
                w.setNavigationBarColor(dark ? 0xFF1A221F : 0xFFFFFFFF);
                w.getDecorView().setBackgroundColor(dark ? 0xFF0F1412 : 0xFFF4F1EA);
                if (Build.VERSION.SDK_INT >= 26) {
                    View d = w.getDecorView();
                    int f = d.getSystemUiVisibility();
                    if (dark) f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    else f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    d.setSystemUiVisibility(f);
                }
            });
        }
    }

    private void sendOcr(Text text) {
        String payload = "null";
        if (text != null) {
            try {
                JSONArray lines = new JSONArray();
                for (Text.TextBlock block : text.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        Rect r = line.getBoundingBox();
                        JSONObject o = new JSONObject();
                        o.put("text", line.getText());
                        o.put("h", r != null ? r.height() : 0);
                        o.put("y", r != null ? r.centerY() : 0);
                        o.put("x", r != null ? r.left : 0);
                        lines.put(o);
                    }
                }
                JSONObject out = new JSONObject();
                out.put("lines", lines);
                payload = out.toString();
            } catch (Exception ignored) { }
        }
        js("window.__cwOcrDone && window.__cwOcrDone(" + JSONObject.quote(payload) + ")");
    }

    private void notifySave(String status) {
        js("window.__cwSaveDone && window.__cwSaveDone('" + status + "')");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SAVE_FILE) {
            String status = "cancelled";
            if (resultCode == RESULT_OK && data != null && data.getData() != null && saveTemp != null) {
                try (InputStream in = new FileInputStream(saveTemp);
                     OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    status = "saved";
                } catch (Exception e) {
                    status = "error";
                }
            }
            if (saveTemp != null) saveTemp.delete();
            notifySave(status);
            return;
        }
        if (requestCode != PICK_FILES || pending == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            boolean fromCamera = cameraFile != null && cameraFile.exists() && cameraFile.length() > 0;
            if (fromCamera) {
                result = new Uri[]{cameraUri};
            } else if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        pending.onReceiveValue(result);
        pending = null;
    }

    @Override
    public void onBackPressed() {
        // Back closes an open panel or returns to the Cards tab before leaving the app
        web.evaluateJavascript("(window.__cwBack && window.__cwBack()) ? 'y' : 'n'",
                value -> { if (!"\"y\"".equals(value)) MainActivity.super.onBackPressed(); });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }
}
