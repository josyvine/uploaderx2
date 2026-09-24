package com.vineyard.uploaderx.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Size;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.multidex.MultiDex;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private WebView mainWebView;
    private String currentFileCallback;
    private static final int JSON_FILE_REQUEST_CODE = 1001;
    private static final int VIDEO_FILE_REQUEST_CODE = 1002;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    // --- DYNAMIC CHUNKING-RELATED VARIABLES ---
    private InputStream currentChunkingInputStream;
    private int chunkSize = 4 * 1024 * 1024; // Default 4MB (4,194,304 bytes - multiple of 256KB)
    private byte[] chunkBuffer = new byte[chunkSize];

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        MultiDex.install(newBase);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mainWebView = (WebView) findViewById(R.id.main_webview);
        WebSettings webSettings = mainWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        mainWebView.addJavascriptInterface(new WebAppInterface(this), "Android");
        mainWebView.setWebViewClient(new WebViewClient());
        mainWebView.setWebChromeClient(new WebChromeClient());
        mainWebView.loadUrl("file:///android_asset/index.html");

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getData() != null) {
            Uri data = intent.getData();
            String scheme = "com.vineyard.uploaderx.app.oauth2";
            String host = "callback";
            if (scheme.equals(data.getScheme()) && host.equals(data.getHost())) {
                String authCode = data.getQueryParameter("code");
                if (authCode != null) {
                    mainWebView.evaluateJavascript("javascript:deliverAuthCodeToWeb('" + authCode + "')", null);
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null) {

            if (requestCode == VIDEO_FILE_REQUEST_CODE) {
                final JSONArray videosJsonArray = new JSONArray();

                if (data.getClipData() != null) {
                    ClipData clipData = data.getClipData();
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        Uri videoUri = clipData.getItemAt(i).getUri();
                        if (videoUri != null) {
                            try {
                                String thumbnailB64 = generateThumbnailForVideo(videoUri);
                                JSONObject videoObject = new JSONObject();
                                videoObject.put("uri", videoUri.toString());
                                videoObject.put("thumbnailB64", thumbnailB64);
                                videosJsonArray.put(videoObject);
                            } catch (Exception e) {
                                logToTerminal("Failed to process video: " + videoUri.toString());
                            }
                        }
                    }
                }
                else if (data.getData() != null) {
                    Uri videoUri = data.getData();
                    try {
                        String thumbnailB64 = generateThumbnailForVideo(videoUri);
                        JSONObject videoObject = new JSONObject();
                        videoObject.put("uri", videoUri.toString());
                        videoObject.put("thumbnailB64", thumbnailB64);
                        videosJsonArray.put(videoObject);
                    } catch (Exception e) {
                        logToTerminal("Failed to process video: " + videoUri.toString());
                    }
                }

                logToTerminal("DEBUG: Sending this JSON to webview: " + videosJsonArray.toString());

                final String escapedJson = videosJsonArray.toString().replace("\\", "\\\\").replace("'", "\\'");
                mainWebView.post(new Runnable() {
                    @Override
                    public void run() {
                        mainWebView.evaluateJavascript("onVideosSelected('" + escapedJson + "')", null);
                    }
                });
                return;
            }

            if (requestCode == JSON_FILE_REQUEST_CODE) {
                try {
                    Uri uri = data.getData();
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    byte[] fileBytes = getBytes(inputStream);
                    final String base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
                    mainWebView.post(new Runnable() {
                        @Override
                        public void run() {
                            mainWebView.evaluateJavascript(currentFileCallback + "('" + base64Data + "')", null);
                        }
                    });
                } catch (Throwable t) {
                    logToTerminal(getStackTraceAsString(t));
                }
            }
        }
    }

    private String generateThumbnailForVideo(Uri videoUri) throws IOException {
        Bitmap thumbnailBitmap = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Size thumbnailSize = new Size(200, 200);
                thumbnailBitmap = getContentResolver().loadThumbnail(videoUri, thumbnailSize, null);
            } catch (Exception e) {
                thumbnailBitmap = null;
            }
        }

        if (thumbnailBitmap == null) {
            Cursor cursor = null;
            try {
                String[] projection = {MediaStore.Video.Media._ID};
                cursor = getContentResolver().query(videoUri, projection, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    long videoId = cursor.getLong(idColumn);
                    thumbnailBitmap = MediaStore.Video.Thumbnails.getThumbnail(
                        getContentResolver(),
                        videoId,
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null
                    );
                }
            } catch (Exception e) {
                thumbnailBitmap = null;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        if (thumbnailBitmap == null) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, videoUri);
                thumbnailBitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception e) {
                thumbnailBitmap = null;
            } finally {
                try {
                    retriever.release();
                } catch (Exception e) { 
                    // ignore cleanup error
                }
            }
        }

        if (thumbnailBitmap != null) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            thumbnailBitmap.compress(Bitmap.CompressFormat.WEBP, 85, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            return Base64.encodeToString(byteArray, Base64.NO_WRAP);
        }

        return null;
    }

    public class WebAppInterface {
        Context mContext;
        WebAppInterface(Context c) { mContext = c; }

        // --- DYNAMIC CHUNK SIZE SELECTOR (FROM HTML) ---
        @JavascriptInterface
        public void setChunkSize(int mb) {
            if (mb <= 0) mb = 4;
            chunkSize = mb * 1024 * 1024;
            chunkBuffer = new byte[chunkSize];
            logToTerminal("--> [Android] Upload chunk size set to " + mb + "MB.");
        }

        // --- NATIVELY LOAD ASSET FILES (BYPASSES WEBVIEW CORS) ---
        @JavascriptInterface
        public String loadAssetFile(String fileName) {
            try {
                InputStream is = mContext.getAssets().open(fileName);
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int length;
                while ((length = is.read(buffer)) != -1) {
                    result.write(buffer, 0, length);
                }
                is.close();
                return result.toString("UTF-8");
            } catch (Exception e) {
                logToTerminal("❌ Failed to load asset: " + fileName + ", error: " + e.getMessage());
                return null;
            }
        }

        // --- FIXED: ALLOWS ALL MIME TYPES SO client_secrets.json IS NEVER GREYED OUT ---
        @JavascriptInterface
        public void selectJsonFile(String callback) {
            currentFileCallback = callback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            String[] mimetypes = {"application/json", "text/plain", "application/octet-stream", "*/*"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, JSON_FILE_REQUEST_CODE);
        }

        @JavascriptInterface
        public void selectVideoFile(String callback) {
            currentFileCallback = callback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, VIDEO_FILE_REQUEST_CODE);
        }

        @JavascriptInterface
        public void getVideoAsBase64(final String videoUriString, final String taskId) {
            logToTerminal("WARNING: Deprecated function getVideoAsBase64 was called. Please use chunking functions.");
        }

        // --- CHUNKING LOGIC ---

        @JavascriptInterface
        public void startChunkingForTask(final String videoUriString, final String taskId) {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        Uri videoUri = Uri.parse(videoUriString);
                        long fileSize = 0;
                        try (Cursor cursor = getContentResolver().query(videoUri, null, null, null, null)) {
                            if (cursor != null && cursor.moveToFirst()) {
                                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                                if (sizeIndex != -1) {
                                    fileSize = cursor.getLong(sizeIndex);
                                }
                            }
                        }

                        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                        retriever.setDataSource(mContext, videoUri);
                        String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                        if (mimeType == null) { mimeType = "video/*"; }
                        retriever.release();

                        currentChunkingInputStream = getContentResolver().openInputStream(videoUri);

                        final String finalMimeType = mimeType;
                        final long finalFileSize = fileSize;

                        mainWebView.post(new Runnable() {
                            @Override
                            public void run() {
                                mainWebView.evaluateJavascript("javascript:onChunkingReadyForTask('" + taskId + "', '" + finalMimeType + "', " + finalFileSize + ")", null);
                            }
                        });

                    } catch (Exception e) {
                        logToTerminal("❌ ERROR starting chunking: " + getStackTraceAsString(e));
                    }
                }
            });
        }

        @JavascriptInterface
        public void requestNextChunk(final String taskId) {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (currentChunkingInputStream == null) {
                            return;
                        }

                        int bytesRead = currentChunkingInputStream.read(chunkBuffer);

                        if (bytesRead == -1) {
                            mainWebView.post(new Runnable() {
                                @Override
                                public void run() {
                                    mainWebView.evaluateJavascript("javascript:onChunkReceivedForTask('" + taskId + "', '')", null);
                                }
                            });
                            return;
                        }

                        byte[] actualChunk;
                        if (bytesRead < chunkSize) {
                            actualChunk = Arrays.copyOf(chunkBuffer, bytesRead);
                        } else {
                            actualChunk = chunkBuffer;
                        }

                        String chunkBase64 = Base64.encodeToString(actualChunk, Base64.NO_WRAP);
                        final String escapedChunk = chunkBase64.replace("\\", "\\\\").replace("'", "\\'");

                        mainWebView.post(new Runnable() {
                            @Override
                            public void run() {
                                mainWebView.evaluateJavascript("javascript:onChunkReceivedForTask('" + taskId + "', '" + escapedChunk + "')", null);
                            }
                        });

                    } catch (Exception e) {
                        logToTerminal("❌ ERROR reading chunk: " + getStackTraceAsString(e));
                    }
                }
            });
        }

        @JavascriptInterface
        public void endChunking() {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (currentChunkingInputStream != null) {
                            currentChunkingInputStream.close();
                            currentChunkingInputStream = null;
                            logToTerminal("--> [Android] Video file stream closed.");
                        }
                    } catch (IOException e) {
                        // Ignore any errors during cleanup
                    }
                }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(browserIntent);
        }
    }

    private String getStackTraceAsString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    private void logToTerminal(final String message) {
        if (message == null || message.isEmpty()) return;
        final String escapedMessage = message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
        mainWebView.post(new Runnable() {
            @Override
            public void run() {
                mainWebView.evaluateJavascript("javascript:logFromNative('" + escapedMessage + "\\n')", null);
            }
        });
    }

    private byte[] getBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            byteBuffer.write(buffer, 0, len);
        }
        return byteBuffer.toByteArray();
    }

    @Override
    public void onBackPressed() {
        if (mainWebView.canGoBack()) {
            mainWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}