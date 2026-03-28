package r.r.refreezer;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ryanheise.audioservice.AudioServiceActivity;

import java.io.File;
import java.lang.ref.WeakReference;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;

public class MainActivity extends AudioServiceActivity {
    private static final String CHANNEL = "r.r.refreezer/native";
    private static final String EVENT_CHANNEL = "r.r.refreezer/downloads";

    EventChannel.EventSink eventSink;

    boolean serviceBound = false;
    Messenger serviceMessenger;
    Messenger activityMessenger;
    SQLiteDatabase db;
    StreamServer streamServer;

    // Data if started from intent
    String intentPreload;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        intentPreload = intent.getStringExtra("preload");
        super.onCreate(savedInstanceState);
    }

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);

        // Initialize ChangeIconPlugin
        ChangeIconPlugin changeIconPlugin = new ChangeIconPlugin(this);
        changeIconPlugin.initWith(flutterEngine.getDartExecutor().getBinaryMessenger());
        changeIconPlugin.tryFixLauncherIconIfNeeded();

        // Flutter method channel
        new MethodChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(),
                CHANNEL
        ).setMethodCallHandler(((call, result) -> {

            // Add downloads to DB, then refresh service
            if (call.method.equals("addDownloads")) {
                ArrayList<HashMap<?, ?>> downloads = call.arguments();

                if (downloads != null) {
                    db.beginTransaction();
                    for (int i = 0; i < downloads.size(); i++) {
                        Cursor cursor = db.rawQuery(
                                "SELECT id, state, quality FROM Downloads WHERE trackId == ? AND path == ?",
                                new String[]{
                                        (String) downloads.get(i).get("trackId"),
                                        (String) downloads.get(i).get("path")
                                }
                        );

                        if (cursor.getCount() > 0) {
                            cursor.moveToNext();
                            // If done or error, set state to NONE
                            if (cursor.getInt(1) >= 3) {
                                ContentValues values = new ContentValues();
                                values.put("state", 0);
                                values.put("quality", cursor.getInt(2));
                                db.update(
                                        "Downloads",
                                        values,
                                        "id == ?",
                                        new String[]{Integer.toString(cursor.getInt(0))}
                                );
                                Log.d("INFO", "Already exists in DB, updating to none state!");
                            } else {
                                Log.d("INFO", "Already exists in DB!");
                            }
                            cursor.close();
                            continue;
                        }
                        cursor.close();

                        ContentValues row = Download.flutterToSQL(downloads.get(i));
                        db.insert("Downloads", null, row);
                    }
                    db.setTransactionSuccessful();
                    db.endTransaction();

                    sendMessage(DownloadService.SERVICE_LOAD_DOWNLOADS, null);

                    result.success(null);
                    return;
                }
            }

            // Get all downloads from DB
            if (call.method.equals("getDownloads")) {
                Cursor cursor = db.query("Downloads", null, null, null, null, null, null);
                ArrayList<HashMap<?, ?>> downloads = new ArrayList<>();

                while (cursor.moveToNext()) {
                    Download download = Download.fromSQL(cursor);
                    downloads.add(download.toHashMap());
                }
                cursor.close();

                result.success(downloads);
                return;
            }

            // Update settings from UI
            if (call.method.equals("updateSettings")) {
                Bundle bundle = new Bundle();
                bundle.putString("json", call.argument("json").toString());
                sendMessage(DownloadService.SERVICE_SETTINGS_UPDATE, bundle);

                result.success(null);
                return;
            }

            // Load downloads from DB in service
            if (call.method.equals("loadDownloads")) {
                sendMessage(DownloadService.SERVICE_LOAD_DOWNLOADS, null);
                result.success(null);
                return;
            }

            // Start/Resume downloading
            if (call.method.equals("start")) {
                sendMessage(DownloadService.SERVICE_START_DOWNLOAD, null);
                result.success(serviceBound);
                return;
            }

            // Stop downloading
            if (call.method.equals("stop")) {
                sendMessage(DownloadService.SERVICE_STOP_DOWNLOADS, null);
                result.success(null);
                return;
            }

            // Remove download
            if (call.method.equals("removeDownload")) {
                Bundle bundle = new Bundle();
                bundle.putInt("id", (int) call.argument("id"));
                sendMessage(DownloadService.SERVICE_REMOVE_DOWNLOAD, bundle);
                result.success(null);
                return;
            }

            // Retry download
            if (call.method.equals("retryDownloads")) {
                sendMessage(DownloadService.SERVICE_RETRY_DOWNLOADS, null);
                result.success(null);
                return;
            }

            // Remove downloads by state
            if (call.method.equals("removeDownloads")) {
                Bundle bundle = new Bundle();
                bundle.putInt("state", (int) call.argument("state"));
                sendMessage(DownloadService.SERVICE_REMOVE_DOWNLOADS, bundle);
                result.success(null);
                return;
            }

            // If app was started with preload info (Android Auto)
            if (call.method.equals("getPreloadInfo")) {
                result.success(intentPreload);
                intentPreload = null;
                return;
            }

            // Get architecture
            if (call.method.equals("arch")) {
                result.success(System.getProperty("os.arch"));
                return;
            }

            // Start streaming server
            if (call.method.equals("startServer")) {
                if (streamServer == null) {
                    String offlinePath = getExternalFilesDir("offline").getAbsolutePath();
                    streamServer = new StreamServer(call.argument("arl"), offlinePath);
                    streamServer.start();
                }
                result.success(null);
                return;
            }

            // Get quality info from stream
            if (call.method.equals("getStreamInfo")) {
                if (streamServer == null) {
                    result.success(null);
                    return;
                }

                StreamServer.StreamInfo info =
                        streamServer.streams.get(call.argument("id").toString());

                if (info != null) {
                    result.success(info.toJSON());
                } else {
                    result.success(null);
                }
                return;
            }

            // ----------------------------
            // Surround helper methods
            // ----------------------------

            // Returns a surround output path and makes sure parent dirs exist
            if (call.method.equals("getSurroundPath")) {
                String trackId = call.argument("trackId");
                Boolean persistentArg = call.argument("persistent");
                Boolean externalArg = call.argument("external");
                String extension = call.argument("extension");

                boolean persistent = persistentArg != null && persistentArg;
                boolean external = externalArg != null && externalArg;

                if (extension == null || extension.trim().isEmpty()) {
                    extension = "ts";
                }

                File surroundFile = buildSurroundFile(trackId, extension, persistent, external);
                result.success(surroundFile != null ? surroundFile.getAbsolutePath() : null);
                return;
            }

            // Finds first existing surround file in temp -> docs -> external app dir
            if (call.method.equals("findExistingSurroundPath")) {
                String trackId = call.argument("trackId");
                String extension = call.argument("extension");

                if (extension == null || extension.trim().isEmpty()) {
                    extension = "ts";
                }

                String existing = findExistingSurroundPath(trackId, extension);
                result.success(existing);
                return;
            }

            // Check if surround file exists
            if (call.method.equals("surroundFileExists")) {
                String path = call.argument("path");
                String trackId = call.argument("trackId");
                String extension = call.argument("extension");

                if (extension == null || extension.trim().isEmpty()) {
                    extension = "ts";
                }

                boolean exists;
                if (path != null && !path.trim().isEmpty()) {
                    exists = new File(path).exists();
                } else {
                    exists = findExistingSurroundPath(trackId, extension) != null;
                }

                result.success(exists);
                return;
            }

            // Delete surround file by explicit path or by trackId
            if (call.method.equals("deleteSurroundFile")) {
                String path = call.argument("path");
                String trackId = call.argument("trackId");
                String extension = call.argument("extension");

                if (extension == null || extension.trim().isEmpty()) {
                    extension = "ts";
                }

                boolean deleted = false;

                if (path != null && !path.trim().isEmpty()) {
                    File file = new File(path);
                    if (file.exists()) {
                        deleted = file.delete();
                    }
                } else if (trackId != null && !trackId.trim().isEmpty()) {
                    deleted = deleteSurroundFilesForTrack(trackId, extension);
                }

                result.success(deleted);
                return;
            }

            // Clear surround cache dirs
            if (call.method.equals("clearSurroundCache")) {
                Boolean persistentArg = call.argument("persistent");
                boolean clearPersistent = persistentArg == null || persistentArg;

                boolean deleted = clearSurroundCache(clearPersistent);
                result.success(deleted);
                return;
            }

            // ----------------------------
            // FFmpeg helper methods (new)
            // ----------------------------

            if (call.method.equals("isFfmpegAvailable")) {
                result.success(resolveFfmpegBinaryPath() != null);
                return;
            }

            if (call.method.equals("getFfmpegPath")) {
                result.success(resolveFfmpegBinaryPath());
                return;
            }

            if (call.method.equals("generateSurroundNow")) {
                final String trackId = call.argument("trackId");
                final String inputPath = call.argument("inputPath");
                final String explicitOutputPath = call.argument("outputPath");
                final String outputModeRaw = call.argument("outputMode");
                final String presetRaw = call.argument("preset");

                final Boolean overwriteArg = call.argument("overwrite");
                final Boolean debugPassthroughArg = call.argument("debugPassthrough");
                final Boolean persistentArg = call.argument("persistent");

                final Integer bitrateArg = call.argument("bitrateKbps");
                final Integer sampleRateArg = call.argument("sampleRateHz");
                final Integer outputChannelsArg = call.argument("outputChannels");

                final boolean overwrite = overwriteArg == null || overwriteArg;
                final boolean debugPassthrough =
                        debugPassthroughArg != null && debugPassthroughArg;
                final boolean persistent = persistentArg == null || persistentArg;

                final SurroundProcessor.OutputMode outputMode =
                        parseOutputMode(outputModeRaw);
                final SurroundProcessor.Preset preset =
                        SurroundProcessor.Preset.fromString(presetRaw);

                final int bitrateKbps = bitrateArg != null ? bitrateArg : 448;
                final int sampleRateHz = sampleRateArg != null ? sampleRateArg : 48000;
                final int outputChannels = outputChannelsArg != null ? outputChannelsArg : 6;

                if (trackId == null || trackId.trim().isEmpty()) {
                    result.error("invalid_args", "trackId is required", null);
                    return;
                }

                if (inputPath == null || inputPath.trim().isEmpty()) {
                    result.error("invalid_args", "inputPath is required", null);
                    return;
                }

                final Handler mainHandler = new Handler(Looper.getMainLooper());

                new Thread(() -> {
                    try {
                        String ffmpegPath = resolveFfmpegBinaryPath();
                        if (ffmpegPath == null || ffmpegPath.trim().isEmpty()) {
                            mainHandler.post(() -> result.success(errorMap(
                                    "FFmpeg binary not found"
                            )));
                            return;
                        }

                        String resolvedOutputPath = explicitOutputPath;
                        if (resolvedOutputPath == null || resolvedOutputPath.trim().isEmpty()) {
                            String ext = outputMode == SurroundProcessor.OutputMode.AC3 ? "ac3" : "ts";
                            File autoOutput = buildSurroundFile(trackId, ext, persistent, false);
                            if (autoOutput == null) {
                                mainHandler.post(() -> result.success(errorMap(
                                        "Failed to resolve output path"
                                )));
                                return;
                            }
                            resolvedOutputPath = autoOutput.getAbsolutePath();
                        }

                        FFmpegSurroundProcessor processor =
                                new FFmpegSurroundProcessor(ffmpegPath);

                        FFmpegSurroundProcessor.Result processResult =
                                processor.processWithFfmpeg(
                                        new SurroundProcessor.Config.Builder()
                                                .setTrackId(trackId)
                                                .setInputPath(inputPath)
                                                .setOutputPath(resolvedOutputPath)
                                                .setOutputMode(outputMode)
                                                .setPreset(preset)
                                                .setOverwrite(overwrite)
                                                .setDebugPassthrough(debugPassthrough)
                                                .setBitrateKbps(bitrateKbps)
                                                .setSampleRateHz(sampleRateHz)
                                                .setOutputChannels(outputChannels)
                                                .build()
                                );

                        final HashMap<String, Object> out =
                                surroundResultToMap(processResult, ffmpegPath);

                        mainHandler.post(() -> result.success(out));
                    } catch (Exception e) {
                        Log.e("SURROUND", "generateSurroundNow failed", e);
                        final HashMap<String, Object> out = errorMap(
                                "generateSurroundNow exception: " + e.getMessage()
                        );
                        mainHandler.post(() -> result.success(out));
                    }
                }).start();

                return;
            }

            // Stop services
            if (call.method.equals("kill")) {
                Intent intent = new Intent(this, DownloadService.class);
                stopService(intent);
                if (streamServer != null) {
                    streamServer.stop();
                    streamServer = null;
                }
                result.success(null);
                return;
            }

            result.error("0", "Not implemented!", "Not implemented!");
        }));

        // Event channel (for download updates)
        EventChannel eventChannel = new EventChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(),
                EVENT_CHANNEL
        );
        eventChannel.setStreamHandler((new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                eventSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                eventSink = null;
            }
        }));
    }

    // Start/Bind/Reconnect to download service
    private void connectService() {
        if (serviceBound) return;

        activityMessenger = new Messenger(new IncomingHandler(this));

        Intent intent = new Intent(this, DownloadService.class);
        intent.putExtra("activityMessenger", activityMessenger);
        startService(intent);
        bindService(intent, connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();

        connectService();

        // Get DB (and leave open!)
        DownloadsDatabase dbHelper = new DownloadsDatabase(getApplicationContext());
        db = dbHelper.getWritableDatabase();

        // Trust all SSL Certs
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };

        SSLContext sc;
        try {
            sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            Log.e(this.getLocalClassName(), e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        connectService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        db.close();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (streamServer != null) {
            streamServer.stop();
        }

        if (serviceBound) {
            unbindService(connection);
            serviceBound = false;
        }
    }

    // Connection to download service
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            serviceMessenger = new Messenger(iBinder);
            serviceBound = true;
            Log.d("DD", "Service Bound!");
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceMessenger = null;
            serviceBound = false;
            Log.d("DD", "Service UnBound!");
        }
    };

    // Handler for incoming messages from service
    private static class IncomingHandler extends Handler {
        private final WeakReference<MainActivity> weakReference;

        IncomingHandler(MainActivity activity) {
            super(Looper.getMainLooper());
            this.weakReference = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            MainActivity activity = weakReference.get();

            if (activity != null) {
                EventChannel.EventSink eventSink = activity.eventSink;
                switch (msg.what) {
                    case DownloadService.SERVICE_ON_PROGRESS:
                        if (eventSink == null) break;

                        ArrayList<Bundle> downloads =
                                getParcelableArrayList(msg.getData(), "downloads", Bundle.class);

                        if (downloads != null && downloads.size() > 0) {
                            ArrayList<HashMap<String, Number>> data = new ArrayList<>();
                            for (Bundle bundle : downloads) {
                                HashMap<String, Number> out = new HashMap<>();
                                out.put("id", bundle.getInt("id"));
                                out.put("state", bundle.getInt("state"));
                                out.put("received", bundle.getLong("received"));
                                out.put("filesize", bundle.getLong("filesize"));
                                out.put("quality", bundle.getInt("quality"));
                                data.add(out);
                            }

                            HashMap<String, Object> out = new HashMap<>();
                            out.put("action", "onProgress");
                            out.put("data", data);
                            eventSink.success(out);
                        }
                        break;

                    case DownloadService.SERVICE_ON_STATE_CHANGE:
                        if (eventSink == null) break;

                        Bundle b = msg.getData();
                        HashMap<String, Object> out = new HashMap<>();
                        out.put("running", b.getBoolean("running"));
                        out.put("queueSize", b.getInt("queueSize"));
                        out.put("action", "onStateChange");
                        eventSink.success(out);
                        break;

                    default:
                        super.handleMessage(msg);
                }
            }
        }
    }

    // Send message to service
    void sendMessage(int type, Bundle data) {
        if (serviceBound && serviceMessenger != null) {
            Message msg = Message.obtain(null, type);
            msg.setData(data);
            try {
                serviceMessenger.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    // --------------------------------------
    // Surround file helpers
    // --------------------------------------

    @Nullable
    private File getFlutterDocumentsBaseDir() {
        try {
            File filesDir = getFilesDir();
            if (filesDir == null) return null;

            File parent = filesDir.getParentFile();
            if (parent == null) return null;

            File appFlutterDir = new File(parent, "app_flutter");
            if (!appFlutterDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                appFlutterDir.mkdirs();
            }
            return appFlutterDir;
        } catch (Exception e) {
            Log.e("SURROUND", "Failed to resolve app_flutter dir", e);
            return null;
        }
    }

    @Nullable
    private File getSurroundDirectory(boolean persistent, boolean external) {
        try {
            File baseDir;

            if (external) {
                baseDir = getExternalFilesDir(null);
            } else if (persistent) {
                baseDir = getFlutterDocumentsBaseDir();
            } else {
                baseDir = getCacheDir();
            }

            if (baseDir == null) return null;

            File surroundDir = new File(baseDir, "surround");
            if (!surroundDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                surroundDir.mkdirs();
            }

            return surroundDir;
        } catch (Exception e) {
            Log.e("SURROUND", "Failed to get surround directory", e);
            return null;
        }
    }

    @Nullable
    private File buildSurroundFile(
            @Nullable String trackId,
            @NonNull String extension,
            boolean persistent,
            boolean external
    ) {
        if (trackId == null || trackId.trim().isEmpty()) return null;

        File dir = getSurroundDirectory(persistent, external);
        if (dir == null) return null;

        String safeExtension = extension.trim().isEmpty() ? "ts" : extension.trim();
        return new File(dir, trackId + "." + safeExtension);
    }

    @Nullable
    private String findExistingSurroundPath(@Nullable String trackId, @NonNull String extension) {
        if (trackId == null || trackId.trim().isEmpty()) return null;

        File tempFile = buildSurroundFile(trackId, extension, false, false);
        if (tempFile != null && tempFile.exists()) {
            return tempFile.getAbsolutePath();
        }

        File docsFile = buildSurroundFile(trackId, extension, true, false);
        if (docsFile != null && docsFile.exists()) {
            return docsFile.getAbsolutePath();
        }

        File externalFile = buildSurroundFile(trackId, extension, false, true);
        if (externalFile != null && externalFile.exists()) {
            return externalFile.getAbsolutePath();
        }

        return null;
    }

    private boolean deleteSurroundFilesForTrack(@Nullable String trackId, @NonNull String extension) {
        if (trackId == null || trackId.trim().isEmpty()) return false;

        boolean deletedAny = false;

        File tempFile = buildSurroundFile(trackId, extension, false, false);
        if (tempFile != null && tempFile.exists()) {
            deletedAny = tempFile.delete() || deletedAny;
        }

        File docsFile = buildSurroundFile(trackId, extension, true, false);
        if (docsFile != null && docsFile.exists()) {
            deletedAny = docsFile.delete() || deletedAny;
        }

        File externalFile = buildSurroundFile(trackId, extension, false, true);
        if (externalFile != null && externalFile.exists()) {
            deletedAny = externalFile.delete() || deletedAny;
        }

        return deletedAny;
    }

    private boolean clearSurroundCache(boolean clearPersistent) {
        boolean deletedAny = false;

        File tempDir = getSurroundDirectory(false, false);
        if (tempDir != null && tempDir.exists()) {
            deletedAny = deleteRecursively(tempDir) || deletedAny;
        }

        if (clearPersistent) {
            File docsDir = getSurroundDirectory(true, false);
            if (docsDir != null && docsDir.exists()) {
                deletedAny = deleteRecursively(docsDir) || deletedAny;
            }

            File externalDir = getSurroundDirectory(false, true);
            if (externalDir != null && externalDir.exists()) {
                deletedAny = deleteRecursively(externalDir) || deletedAny;
            }
        }

        // Recreate dirs for future use
        getSurroundDirectory(false, false);
        if (clearPersistent) {
            getSurroundDirectory(true, false);
            getSurroundDirectory(false, true);
        }

        return deletedAny;
    }

    private boolean deleteRecursively(@Nullable File fileOrDirectory) {
        if (fileOrDirectory == null || !fileOrDirectory.exists()) {
            return false;
        }

        boolean deletedSomething = false;

        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deletedSomething = deleteRecursively(child) || deletedSomething;
                }
            }
        }

        return fileOrDirectory.delete() || deletedSomething;
    }

    // --------------------------------------
    // FFmpeg helpers
    // --------------------------------------

    @Nullable
    private String resolveFfmpegBinaryPath() {
        File[] candidates = new File[]{
                new File(getFilesDir(), "ffmpeg"),
                new File(new File(getFilesDir(), "bin"), "ffmpeg"),
                new File(getCacheDir(), "ffmpeg")
        };

        for (File candidate : candidates) {
            if (candidate.exists() && candidate.isFile()) {
                if (!candidate.canExecute()) {
                    //noinspection ResultOfMethodCallIgnored
                    candidate.setExecutable(true);
                }
                if (isExecutableFile(candidate)) {
                    return candidate.getAbsolutePath();
                }
            }
        }

        return null;
    }

    private boolean isExecutableFile(@Nullable File file) {
        return file != null && file.exists() && file.isFile() && file.canExecute();
    }

    private SurroundProcessor.OutputMode parseOutputMode(@Nullable String raw) {
        if (raw == null) return SurroundProcessor.OutputMode.TS;
        if ("ac3".equalsIgnoreCase(raw.trim())) {
            return SurroundProcessor.OutputMode.AC3;
        }
        return SurroundProcessor.OutputMode.TS;
    }

    private HashMap<String, Object> surroundResultToMap(
            FFmpegSurroundProcessor.Result result,
            String ffmpegPath
    ) {
        HashMap<String, Object> out = new HashMap<>();
        out.put("success", result.success);
        out.put("trackId", result.trackId);
        out.put("inputPath", result.inputPath);
        out.put("outputPath", result.outputPath);
        out.put("codec", result.codec);
        out.put("container", result.container);
        out.put("preset", result.preset);
        out.put("inputBytes", result.inputBytes);
        out.put("outputBytes", result.outputBytes);
        out.put("message", result.message);
        out.put("error", result.error);
        out.put("ffmpegPath", ffmpegPath);
        return out;
    }

    private HashMap<String, Object> errorMap(String error) {
        HashMap<String, Object> out = new HashMap<>();
        out.put("success", false);
        out.put("error", error);
        return out;
    }

    @Nullable
    public static <T extends Parcelable> ArrayList<T> getParcelableArrayList(
            @Nullable Bundle bundle,
            @Nullable String key,
            @NonNull Class<T> clazz
    ) {
        if (bundle != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return bundle.getParcelableArrayList(key, clazz);
            } else {
                return bundle.getParcelableArrayList(key);
            }
        }
        return null;
    }
}
