package r.r.refreezer;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
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

    // ----------------------------
    // Pending service message queue
    // ----------------------------

    private static class PendingServiceMessage {
        final int type;
        final Bundle data;

        PendingServiceMessage(int type, @Nullable Bundle data) {
            this.type = type;
            this.data = data == null ? null : new Bundle(data);
        }
    }

    final ArrayList<PendingServiceMessage> pendingServiceMessages = new ArrayList<>();
    Bundle lastSettingsBundle;

    // ----------------------------
    // Native AC3 player
    // ----------------------------

    private NativeAc3Player nativeAc3Player;

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

        if (nativeAc3Player == null) {
            nativeAc3Player = new NativeAc3Player();
        }

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

                    sendMessageOrQueue(DownloadService.SERVICE_LOAD_DOWNLOADS, null);

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
                sendMessageOrQueue(DownloadService.SERVICE_SETTINGS_UPDATE, bundle);

                result.success(null);
                return;
            }

            // Load downloads from DB in service
            if (call.method.equals("loadDownloads")) {
                sendMessageOrQueue(DownloadService.SERVICE_LOAD_DOWNLOADS, null);
                result.success(null);
                return;
            }

            // Start/Resume downloading
            if (call.method.equals("start")) {
                sendMessageOrQueue(DownloadService.SERVICE_START_DOWNLOAD, null);
                result.success(serviceBound);
                return;
            }

            // Stop downloading
            if (call.method.equals("stop")) {
                sendMessageOrQueue(DownloadService.SERVICE_STOP_DOWNLOADS, null);
                result.success(null);
                return;
            }

            // Remove download
            if (call.method.equals("removeDownload")) {
                Bundle bundle = new Bundle();
                bundle.putInt("id", (int) call.argument("id"));
                sendMessageOrQueue(DownloadService.SERVICE_REMOVE_DOWNLOAD, bundle);
                result.success(null);
                return;
            }

            // Retry download
            if (call.method.equals("retryDownloads")) {
                sendMessageOrQueue(DownloadService.SERVICE_RETRY_DOWNLOADS, null);
                result.success(null);
                return;
            }

            // Remove downloads by state
            if (call.method.equals("removeDownloads")) {
                Bundle bundle = new Bundle();
                bundle.putInt("state", (int) call.argument("state"));
                sendMessageOrQueue(DownloadService.SERVICE_REMOVE_DOWNLOADS, bundle);
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
                    extension = "ac3";
                }

                File surroundFile = buildSurroundFile(trackId, extension, persistent, external);
                result.success(surroundFile != null ? surroundFile.getAbsolutePath() : null);
                return;
            }

            // Finds first existing surround file in preferred order:
            // AC3 primary, TS fallback (when extension omitted)
            if (call.method.equals("findExistingSurroundPath")) {
                String trackId = call.argument("trackId");
                String extension = call.argument("extension");

                String existing = findExistingSurroundPathSmart(trackId, extension);
                result.success(existing);
                return;
            }

            // Check if surround file exists
            if (call.method.equals("surroundFileExists")) {
                String path = call.argument("path");
                String trackId = call.argument("trackId");
                String extension = call.argument("extension");

                boolean exists;
                if (path != null && !path.trim().isEmpty()) {
                    exists = new File(path).exists();
                } else {
                    exists = findExistingSurroundPathSmart(trackId, extension) != null;
                }

                result.success(exists);
                return;
            }

            // Delete surround file by explicit path or by trackId
            if (call.method.equals("deleteSurroundFile")) {
                String path = call.argument("path");
                String trackId = call.argument("trackId");
                String extension = call.argument("extension");

                boolean deleted = false;

                if (path != null && !path.trim().isEmpty()) {
                    File file = new File(path);
                    if (file.exists()) {
                        deleted = file.delete();
                    }
                } else if (trackId != null && !trackId.trim().isEmpty()) {
                    if (extension == null || extension.trim().isEmpty()) {
                        boolean deletedAc3 = deleteSurroundFilesForTrack(trackId, "ac3");
                        boolean deletedTs = deleteSurroundFilesForTrack(trackId, "ts");
                        deleted = deletedAc3 || deletedTs;
                    } else {
                        deleted = deleteSurroundFilesForTrack(trackId, extension);
                    }
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
            // FFmpeg helper methods
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
                            String ext = outputMode == SurroundProcessor.OutputMode.TS ? "ts" : "ac3";
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

            // ----------------------------
            // Native AC3 direct playback
            // ----------------------------

            if (call.method.equals("isDirectAc3PlaybackSupported")) {
                Integer sampleRateArg = call.argument("sampleRateHz");
                int sampleRateHz = sampleRateArg != null ? sampleRateArg : 48000;
                result.success(isDirectAc3PlaybackSupported(sampleRateHz));
                return;
            }

            if (call.method.equals("playNativeAc3")) {
                final String path = call.argument("path");
                final Integer sampleRateArg = call.argument("sampleRateHz");
                final int sampleRateHz = sampleRateArg != null ? sampleRateArg : 48000;

                if (path == null || path.trim().isEmpty()) {
                    result.error("invalid_args", "path is required", null);
                    return;
                }

                File file = new File(path);
                if (!file.exists() || !file.isFile()) {
                    result.error("file_missing", "AC3 file not found", null);
                    return;
                }

                if (!isDirectAc3PlaybackSupported(sampleRateHz)) {
                    result.error(
                            "direct_playback_unsupported",
                            "Direct AC3 playback is not supported on the current route/device",
                            null
                    );
                    return;
                }

                try {
                    if (nativeAc3Player == null) {
                        nativeAc3Player = new NativeAc3Player();
                    }
                    nativeAc3Player.start(path, sampleRateHz);
                    result.success(true);
                } catch (Exception e) {
                    Log.e("NATIVE_AC3", "playNativeAc3 failed", e);
                    result.error("native_ac3_error", e.getMessage(), null);
                }
                return;
            }

            if (call.method.equals("stopNativeAc3")) {
                try {
                    stopNativeAc3();
                    result.success(true);
                } catch (Exception e) {
                    Log.e("NATIVE_AC3", "stopNativeAc3 failed", e);
                    result.error("native_ac3_error", e.getMessage(), null);
                }
                return;
            }

            if (call.method.equals("isNativeAc3Playing")) {
                result.success(nativeAc3Player != null && nativeAc3Player.isPlaying());
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
                stopNativeAc3();
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
        if (db != null && db.isOpen()) {
            db.close();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (streamServer != null) {
            streamServer.stop();
        }

        stopNativeAc3();

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
            flushPendingServiceMessages();
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

    // ----------------------------
    // Send message to service
    // ----------------------------

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

    void sendMessageOrQueue(int type, @Nullable Bundle data) {
        if (serviceBound && serviceMessenger != null) {
            sendMessage(type, data);
            return;
        }

        if (type == DownloadService.SERVICE_SETTINGS_UPDATE && data != null) {
            lastSettingsBundle = new Bundle(data);
        }

        pendingServiceMessages.add(new PendingServiceMessage(type, data));
        connectService();
    }

    void flushPendingServiceMessages() {
        if (!serviceBound || serviceMessenger == null) return;

        if (lastSettingsBundle != null) {
            sendMessage(DownloadService.SERVICE_SETTINGS_UPDATE, lastSettingsBundle);
        }

        ArrayList<PendingServiceMessage> copy = new ArrayList<>(pendingServiceMessages);
        pendingServiceMessages.clear();

        for (PendingServiceMessage pm : copy) {
            if (pm.type == DownloadService.SERVICE_SETTINGS_UPDATE) {
                continue;
            }
            sendMessage(pm.type, pm.data);
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

        String safeExtension = extension.trim().isEmpty() ? "ac3" : extension.trim();
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

    @Nullable
    private String findExistingSurroundPathSmart(@Nullable String trackId, @Nullable String extension) {
        if (trackId == null || trackId.trim().isEmpty()) return null;

        if (extension != null && !extension.trim().isEmpty()) {
            String found = findExistingSurroundPath(trackId, extension.trim());
            if (found != null) return found;
        }

        String ac3 = findExistingSurroundPath(trackId, "ac3");
        if (ac3 != null) return ac3;

        return findExistingSurroundPath(trackId, "ts");
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
        // FFmpegKit is bundled through AAR, no external binary path required.
        return "ffmpeg-kit";
    }

    private SurroundProcessor.OutputMode parseOutputMode(@Nullable String raw) {
        if (raw == null) return SurroundProcessor.OutputMode.AC3;
        if ("ts".equalsIgnoreCase(raw.trim())) {
            return SurroundProcessor.OutputMode.TS;
        }
        return SurroundProcessor.OutputMode.AC3;
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

    // --------------------------------------
    // Native AC3 helpers
    // --------------------------------------

    private boolean isDirectAc3PlaybackSupported(int sampleRateHz) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }

        try {
            AudioFormat format = new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_AC3)
                    .setSampleRate(sampleRateHz)
                    .build();

            AudioAttributes attr = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            return AudioTrack.isDirectPlaybackSupported(format, attr);
        } catch (Exception e) {
            Log.e("NATIVE_AC3", "isDirectAc3PlaybackSupported failed", e);
            return false;
        }
    }

    private void stopNativeAc3() {
        if (nativeAc3Player != null) {
            nativeAc3Player.stop();
        }
    }

    // --------------------------------------
    // Native AC3 direct player
    // --------------------------------------

    private static class NativeAc3Player {
        private static final String TAG = "NativeAc3Player";

        private AudioTrack audioTrack;
        private Thread playbackThread;
        private volatile boolean stopRequested = false;
        private volatile boolean playing = false;
        private String currentPath;
        private int currentSampleRate = 48000;

        synchronized void start(@NonNull String path, int sampleRateHz) throws Exception {
            stop();

            File file = new File(path);
            if (!file.exists() || !file.isFile()) {
                throw new IllegalArgumentException("AC3 file not found: " + path);
            }

            currentPath = path;
            currentSampleRate = sampleRateHz;
            stopRequested = false;

            AudioFormat format = new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_AC3)
                    .setSampleRate(sampleRateHz)
                    .build();

            AudioAttributes attr = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            int bufferSize = AudioTrack.getMinBufferSize(
                    sampleRateHz,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_AC3
            );
            if (bufferSize <= 0) {
                bufferSize = 64 * 1024;
            } else {
                bufferSize = Math.max(bufferSize, 64 * 1024);
            }

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attr)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .build();

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                releaseTrack();
                throw new IllegalStateException("Failed to initialize AudioTrack for AC3 direct playback");
            }

            audioTrack.play();
            playing = true;

            playbackThread = new Thread(() -> {
                BufferedInputStream inputStream = null;
                try {
                    inputStream = new BufferedInputStream(new FileInputStream(currentPath));
                    byte[] buffer = new byte[4096];

                    while (!stopRequested) {
                        int read = inputStream.read(buffer);
                        if (read == -1) {
                            break;
                        }

                        int written = 0;
                        while (written < read && !stopRequested) {
                            int n = audioTrack.write(buffer, written, read - written);
                            if (n < 0) {
                                throw new RuntimeException("AudioTrack write failed: " + n);
                            }
                            written += n;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Native AC3 playback failed", e);
                } finally {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (Exception ignored) {
                        }
                    }
                    releaseTrack();
                    playing = false;
                }
            }, "NativeAc3PlaybackThread");

            playbackThread.start();
        }

        synchronized void stop() {
            stopRequested = true;

            if (playbackThread != null) {
                try {
                    playbackThread.interrupt();
                    playbackThread.join(500);
                } catch (Exception ignored) {
                }
                playbackThread = null;
            }

            releaseTrack();
            playing = false;
        }

        synchronized boolean isPlaying() {
            return playing;
        }

        private void releaseTrack() {
            if (audioTrack != null) {
                try {
                    audioTrack.pause();
                } catch (Exception ignored) {
                }
                try {
                    audioTrack.flush();
                } catch (Exception ignored) {
                }
                try {
                    audioTrack.stop();
                } catch (Exception ignored) {
                }
                try {
                    audioTrack.release();
                } catch (Exception ignored) {
                }
                audioTrack = null;
            }
        }
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
