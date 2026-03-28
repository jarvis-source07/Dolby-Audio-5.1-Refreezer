package r.r.refreezer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.text.DecimalFormat;
import java.util.ArrayList;

import javax.net.ssl.HttpsURLConnection;

import r.r.refreezer.models.Lyrics;
import r.r.refreezer.models.LyricsClassic;

public class DownloadService extends Service {

    // Message commands
    static final int SERVICE_LOAD_DOWNLOADS = 1;
    static final int SERVICE_START_DOWNLOAD = 2;
    static final int SERVICE_ON_PROGRESS = 3;
    static final int SERVICE_SETTINGS_UPDATE = 4;
    static final int SERVICE_STOP_DOWNLOADS = 5;
    static final int SERVICE_ON_STATE_CHANGE = 6;
    static final int SERVICE_REMOVE_DOWNLOAD = 7;
    static final int SERVICE_RETRY_DOWNLOADS = 8;
    static final int SERVICE_REMOVE_DOWNLOADS = 9;

    static final String NOTIFICATION_CHANNEL_ID = "refreezerdownloads";
    static final int NOTIFICATION_ID_START = 6969;

    boolean running = false;
    DownloadSettings settings;
    Context context;
    SQLiteDatabase db;
    Deezer deezer = new Deezer();

    Messenger serviceMessenger;
    Messenger activityMessenger;
    NotificationManagerCompat notificationManager;

    ArrayList<Download> downloads = new ArrayList<>();
    ArrayList<DownloadThread> threads = new ArrayList<>();
    ArrayList<Boolean> updateRequests = new ArrayList<>();
    boolean updating = false;
    Handler progressUpdateHandler = new Handler();
    DownloadLog logger = new DownloadLog();

    public DownloadService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Setup notifications
        context = this;
        notificationManager = NotificationManagerCompat.from(context);
        createNotificationChannel();
        createProgressUpdateHandler();

        // Setup logger, deezer api
        logger.open(context);
        deezer.init(logger, "");

        // Get DB
        DownloadsDatabase dbHelper = new DownloadsDatabase(getApplicationContext());
        db = dbHelper.getWritableDatabase();

        // Prepare surround directories
        ensureSurroundDirectories();
    }

    @Override
    public void onDestroy() {
        // Cancel notifications
        notificationManager.cancelAll();
        // Logger
        logger.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Set messengers
        serviceMessenger = new Messenger(new IncomingHandler(this));
        if (intent != null) {
            activityMessenger = intent.getParcelableExtra("activityMessenger");
        }

        return serviceMessenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Get messenger
        if (intent != null) {
            activityMessenger = intent.getParcelableExtra("activityMessenger");
        }

        return START_STICKY;
    }

    // Android O+ Notifications
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_MIN
            );
            NotificationManager nManager = getSystemService(NotificationManager.class);
            nManager.createNotificationChannel(channel);
        }
    }

    // Update download tasks
    private void updateQueue() {
        db.beginTransaction();

        // Clear downloaded tracks
        for (int i = threads.size() - 1; i >= 0; i--) {
            Download.DownloadState state = threads.get(i).download.state;
            if (state == Download.DownloadState.NONE ||
                    state == Download.DownloadState.DONE ||
                    state == Download.DownloadState.ERROR ||
                    state == Download.DownloadState.DEEZER_ERROR) {

                Download d = threads.get(i).download;

                // Update in queue
                for (int j = 0; j < downloads.size(); j++) {
                    if (downloads.get(j).id == d.id) {
                        downloads.set(j, d);
                    }
                }

                updateProgress();

                // Save to DB
                ContentValues row = new ContentValues();
                row.put("state", state.getValue());
                row.put("quality", d.quality);
                db.update("Downloads", row, "id == ?", new String[]{Integer.toString(d.id)});

                // Update library
                if (state == Download.DownloadState.DONE && !d.priv) {
                    Uri uri = Uri.fromFile(new File(threads.get(i).outFile.getPath()));
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
                }

                // Remove thread
                threads.remove(i);
            }
        }

        db.setTransactionSuccessful();
        db.endTransaction();

        // Create new download tasks
        if (running) {
            int nThreads = settings != null ? settings.downloadThreads - threads.size() : 0;
            for (int i = 0; i < nThreads; i++) {
                for (int j = 0; j < downloads.size(); j++) {
                    if (downloads.get(j).state == Download.DownloadState.NONE) {
                        Download d = downloads.get(j);
                        d.state = Download.DownloadState.DOWNLOADING;
                        downloads.set(j, d);

                        DownloadThread thread = new DownloadThread(d);
                        thread.start();
                        threads.add(thread);
                        break;
                    }
                }
            }

            if (threads.isEmpty()) {
                running = false;
            }
        }

        // Send updates to UI
        updateProgress();
        updateState();
    }

    // Send state change to UI
    private void updateState() {
        Bundle b = new Bundle();
        b.putBoolean("running", running);

        int queueSize = 0;
        for (int i = 0; i < downloads.size(); i++) {
            if (downloads.get(i).state == Download.DownloadState.NONE) {
                queueSize++;
            }
        }

        b.putInt("queueSize", queueSize);
        sendMessage(SERVICE_ON_STATE_CHANGE, b);
    }

    // Wrapper to prevent threads racing
    private void updateQueueWrapper() {
        updateRequests.add(true);
        if (!updating) {
            updating = true;
            while (!updateRequests.isEmpty()) {
                updateQueue();
                if (!updateRequests.isEmpty()) {
                    updateRequests.remove(0);
                }
            }
        }
        updating = false;
    }

    // Loads downloads from database
    private void loadDownloads() {
        Cursor cursor = db.query("Downloads", null, null, null, null, null, null);

        while (cursor.moveToNext()) {
            int downloadId = cursor.getInt(0);
            Download.DownloadState state = Download.DownloadState.values()[cursor.getInt(1)];
            boolean skip = false;

            for (int i = 0; i < downloads.size(); i++) {
                if (downloads.get(i).id == downloadId) {
                    if (downloads.get(i).state != state) {
                        if (downloads.get(i).state.getValue() >= 3) {
                            downloads.set(i, Download.fromSQL(cursor));
                        }
                    }
                    skip = true;
                    break;
                }
            }

            if (!skip) {
                downloads.add(Download.fromSQL(cursor));
            }
        }
        cursor.close();

        updateState();
    }

    // Stop downloads
    private void stop() {
        running = false;
        for (int i = 0; i < threads.size(); i++) {
            threads.get(i).stopDownload();
        }
        updateState();
    }

    public class DownloadThread extends Thread {

        Download download;
        File parentDir;
        File outFile;
        JSONObject trackJson;
        JSONObject albumJson;
        JSONObject privateJson;
        Lyrics lyricsData = null;
        boolean stopDownload = false;

        DownloadThread(Download download) {
            this.download = download;
        }

        @Override
        public void run() {
            // Set state
            download.state = Download.DownloadState.DOWNLOADING;

            // Authorize deezer api
            if (!deezer.authorized && !deezer.authorizing) {
                deezer.authorize();
            }

            while (deezer.authorizing) {
                try {
                    Thread.sleep(50);
                } catch (Exception ignored) {
                }
            }

            // Don't fetch meta if user uploaded mp3
            if (!download.isUserUploaded()) {
                try {
                    trackJson = deezer.callPublicAPI("track", download.trackId);
                    albumJson = deezer.callPublicAPI(
                            "album",
                            Integer.toString(trackJson.getJSONObject("album").getInt("id"))
                    );
                } catch (Exception e) {
                    logger.error("Unable to fetch track and album metadata! " + e, download);
                    e.printStackTrace();
                    download.state = Download.DownloadState.ERROR;
                    exit();
                    return;
                }
            }

            // Fallback
            Deezer.QualityInfo qualityInfo = new Deezer.QualityInfo(
                    this.download.quality,
                    this.download.streamTrackId,
                    this.download.trackToken,
                    this.download.md5origin,
                    this.download.mediaVersion,
                    logger
            );

            String sURL = null;
            if (!download.isUserUploaded()) {
                try {
                    sURL = qualityInfo.fallback(deezer);
                    if (sURL == null) {
                        throw new Exception("No more to fallback!");
                    }

                    download.quality = qualityInfo.quality;
                } catch (Exception e) {
                    logger.error("Fallback failed " + e.toString());
                    download.state = Download.DownloadState.DEEZER_ERROR;
                    exit();
                    return;
                }
            } else {
                // User uploaded MP3
                qualityInfo.quality = 3;
            }

            if (!download.priv) {
                // Check file
                try {
                    if (download.isUserUploaded()) {
                        outFile = new File(
                                Deezer.generateUserUploadedMP3Filename(download.path, download.title)
                        );
                    } else {
                        outFile = new File(
                                Deezer.generateFilename(download.path, trackJson, albumJson, qualityInfo.quality)
                        );
                    }
                    parentDir = new File(outFile.getParent());
                } catch (Exception e) {
                    logger.error("Error generating track filename (" + download.path + "): " + e, download);
                    e.printStackTrace();
                    download.state = Download.DownloadState.ERROR;
                    exit();
                    return;
                }
            } else {
                // Private track
                outFile = new File(download.path);
                parentDir = new File(outFile.getParent());
            }

            // File already exists
            if (outFile.exists()) {
                if (settings != null && settings.overwriteDownload) {
                    outFile.delete();
                } else {
                    download.state = Download.DownloadState.DONE;
                    exit();
                    return;
                }
            }

            // Temp encrypted file
            File tmpFile = new File(getCacheDir(), download.id + ".ENC");

            // Get start bytes offset
            long start = 0;
            if (tmpFile.exists()) {
                start = tmpFile.length();
            }

            // Download
            try {
                URL url = new URL(sURL);
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

                connection.setConnectTimeout(30000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.130 Safari/537.36"
                );
                connection.setRequestProperty("Accept-Language", "*");
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Range", "bytes=" + start + "-");
                connection.connect();

                BufferedInputStream inputStream = new BufferedInputStream(connection.getInputStream());
                OutputStream outputStream = new FileOutputStream(tmpFile.getPath(), true);

                download.filesize = start + connection.getContentLength();

                byte[] buffer = new byte[4096];
                long received = 0;
                int read;

                while ((read = inputStream.read(buffer, 0, 4096)) != -1) {
                    outputStream.write(buffer, 0, read);
                    received += read;
                    download.received = start + received;

                    if (stopDownload) {
                        download.state = Download.DownloadState.NONE;
                        try {
                            inputStream.close();
                            outputStream.close();
                            connection.disconnect();
                        } catch (Exception ignored) {
                        }
                        exit();
                        return;
                    }
                }

                inputStream.close();
                outputStream.close();
                connection.disconnect();

                download.state = Download.DownloadState.POST;
                updateProgress();
            } catch (Exception e) {
                logger.error("Download error: " + e, download);
                e.printStackTrace();
                download.state = Download.DownloadState.ERROR;
                exit();
                return;
            }

            // Post processing

            // Decrypt
            if (qualityInfo.encrypted) {
                try {
                    File decFile = new File(tmpFile.getPath() + ".DEC");
                    DeezerDecryptor decryptor = new DeezerDecryptor(download.streamTrackId);
                    decryptor.decryptFile(tmpFile.getPath(), decFile.getPath());
                    tmpFile.delete();
                    tmpFile = decFile;
                } catch (Exception e) {
                    logger.error("Decryption error: " + e, download);
                    e.printStackTrace();
                }
            }

            // If exists (duplicate download in DB), don't overwrite
            if (outFile.exists()) {
                download.state = Download.DownloadState.DONE;
                exit();
                return;
            }

            // Create dirs and copy
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                logger.error("Couldn't create output folder: " + parentDir.getPath() + "! ", download);
                download.state = Download.DownloadState.ERROR;
                exit();
                return;
            }

            if (!tmpFile.renameTo(outFile)) {
                try {
                    FileInputStream inputStream = new FileInputStream(tmpFile);
                    FileOutputStream outputStream = new FileOutputStream(outFile);
                    FileChannel inputChannel = inputStream.getChannel();
                    FileChannel outputChannel = outputStream.getChannel();
                    inputChannel.transferTo(0, inputChannel.size(), outputChannel);
                    inputStream.close();
                    outputStream.close();

                    tmpFile.delete();
                } catch (Exception e) {
                    try {
                        outFile.delete();
                        tmpFile.delete();
                    } catch (Exception ignored) {
                    }

                    logger.error("Error moving file! " + outFile.getPath() + ", " + e, download);
                    e.printStackTrace();
                    download.state = Download.DownloadState.ERROR;
                    exit();
                    return;
                }
            }

            // Cover & Tags, ignore on user uploaded
            if (!download.priv && !download.isUserUploaded()) {

                File coverFile = new File(
                        outFile.getPath().substring(0, outFile.getPath().lastIndexOf('.')) + ".jpg"
                );

                try {
                    URL url = new URL(
                            "http://e-cdn-images.deezer.com/images/cover/" +
                                    trackJson.getString("md5_image") +
                                    "/" +
                                    Integer.toString(settings.albumArtResolution) +
                                    "x" +
                                    Integer.toString(settings.albumArtResolution) +
                                    "-000000-80-0-0.jpg"
                    );
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.connect();

                    InputStream inputStream = connection.getInputStream();
                    OutputStream outputStream = new FileOutputStream(coverFile.getPath());

                    byte[] buffer = new byte[4096];
                    int read = 0;
                    while ((read = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, read);
                    }

                    try {
                        inputStream.close();
                        outputStream.close();
                        connection.disconnect();
                    } catch (Exception ignored) {
                    }

                } catch (Exception e) {
                    logger.error("Error downloading cover! " + e, download);
                    e.printStackTrace();
                }

                // Lyrics
                if (settings.downloadLyrics || settings.tags.lyrics) {
                    try {
                        lyricsData = deezer.getlyricsNew(download.trackId);

                        if (!lyricsData.isLoaded()) {
                            if (lyricsData.getErrorMessage() != null) {
                                logger.error(
                                        "Error getting lyrics from Pipe API: " + lyricsData.getErrorMessage(),
                                        download
                                );
                                logger.warn("Trying classic API for lyrics");
                            }

                            JSONObject privateRaw = deezer.callGWAPI(
                                    "deezer.pageTrack",
                                    "{\"sng_id\": \"" + download.trackId + "\"}"
                            );
                            privateJson = privateRaw.getJSONObject("results").getJSONObject("DATA");
                            if (privateRaw.getJSONObject("results").has("LYRICS")) {
                                lyricsData = new LyricsClassic(
                                        privateRaw.getJSONObject("results").getJSONObject("LYRICS")
                                );
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Unable to fetch lyrics data! " + e, download);
                    }
                }

                if (settings.downloadLyrics) {
                    if (lyricsData == null || !lyricsData.isSynced()) {
                        logger.warn("No synched lyrics for track, skipping lyrics file", download);
                    } else {
                        try {
                            String lrcData = Deezer.generateLRC(lyricsData, trackJson);

                            String lrcFilename =
                                    outFile.getPath().substring(0, outFile.getPath().lastIndexOf(".") + 1) + "lrc";
                            FileOutputStream fileOutputStream = new FileOutputStream(lrcFilename);
                            fileOutputStream.write(lrcData.getBytes());
                            fileOutputStream.close();

                        } catch (Exception e) {
                            logger.warn("Error downloading lyrics! " + e, download);
                        }
                    }
                }

                // Tag
                try {
                    deezer.tagTrack(
                            outFile.getPath(),
                            trackJson,
                            albumJson,
                            coverFile.getPath(),
                            lyricsData,
                            privateJson,
                            settings
                    );
                } catch (Exception e) {
                    Log.e("ERR", "Tagging error!");
                    e.printStackTrace();
                }

                // Delete cover if disabled
                if (!settings.trackCover) {
                    coverFile.delete();
                }

                // Album cover
                if (settings.albumCover) {
                    downloadAlbumCover(albumJson);
                }
            }

            // Generate surround AC3 artifact after the original file is ready.
            // This is cache-generation and should never fail the original download.
            maybeGenerateSurroundArtifact(download, outFile);

            download.state = Download.DownloadState.DONE;

            updateQueueWrapper();
            stopSelf();
        }

        // Each track has own album art, this is to download cover.jpg
        void downloadAlbumCover(JSONObject albumJson) {
            if (albumJson == null || !albumJson.has("md5_image")) return;

            File coverFile = new File(parentDir, "cover.jpg");
            if (coverFile.exists()) return;

            // Don't download if doesn't have album
            if (!download.path.matches(".*/.*%album%.*/.*")) return;

            try {
                coverFile.createNewFile();

                URL url = new URL(
                        "http://e-cdn-images.deezer.com/images/cover/" +
                                albumJson.getString("md5_image") +
                                "/" +
                                Integer.toString(settings.albumArtResolution) +
                                "x" +
                                Integer.toString(settings.albumArtResolution) +
                                "-000000-80-0-0.jpg"
                );
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.connect();

                InputStream inputStream = connection.getInputStream();
                OutputStream outputStream = new FileOutputStream(coverFile.getPath());

                byte[] buffer = new byte[4096];
                int read = 0;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }

                try {
                    inputStream.close();
                    outputStream.close();
                    connection.disconnect();
                } catch (Exception ignored) {
                }

                // Create .nomedia
                if (settings.nomediaFiles) {
                    new File(parentDir, ".nomedia").createNewFile();
                }
            } catch (Exception e) {
                logger.warn("Error downloading album cover! " + e, download);
                coverFile.delete();
            }
        }

        void stopDownload() {
            stopDownload = true;
        }

        // Clean stop/exit
        private void exit() {
            updateQueueWrapper();
            stopSelf();
        }
    }

    /**
     * Generate a surround AC3 artifact from the finished audio file.
     *
     * Important:
     * - This should never fail the original download
     * - AC3 is the PRIMARY generated artifact
     * - TS can be generated later/on-demand if ever needed, but not here
     */
    private void maybeGenerateSurroundArtifact(Download download, File sourceFile) {
        try {
            Log.i("SURROUND", "maybeGenerateSurroundArtifact called");

            if (settings == null) {
                Log.w("SURROUND", "Skipping surround generation: settings == null");
                return;
            }

            if (download == null || download.trackId == null || download.trackId.trim().isEmpty()) {
                Log.w("SURROUND", "Skipping surround generation: invalid download/trackId");
                return;
            }

            if (sourceFile == null || !sourceFile.exists() || !sourceFile.isFile()) {
                logger.warn("Skipping surround generation, source file missing", download);
                Log.w("SURROUND", "Skipping surround generation: source missing");
                return;
            }

            String ffmpegPath = resolveFfmpegBinaryPath();
            if (ffmpegPath == null || ffmpegPath.trim().isEmpty()) {
                logger.warn("Skipping surround generation, ffmpeg path unavailable", download);
                Log.w("SURROUND", "Skipping surround generation: ffmpeg path unavailable");
                return;
            }

            File surroundOutput = getSurroundOutputFile(download.trackId, true, "ac3");
            if (surroundOutput == null) {
                logger.warn("Skipping surround generation, surround output path unavailable", download);
                Log.w("SURROUND", "Skipping surround generation: output path unavailable");
                return;
            }

            Log.i(
                    "SURROUND",
                    "Generating AC3 surround for trackId=" + download.trackId
                            + ", source=" + sourceFile.getAbsolutePath()
                            + ", sourceSize=" + sourceFile.length()
                            + ", out=" + surroundOutput.getAbsolutePath()
                            + ", preset=" + settings.surroundPreset
                            + ", playbackMode=" + settings.playbackMode
            );

            // Delete old AC3/TS artifacts for this track before regenerating.
            deleteSurroundArtifacts(download.trackId);

            FFmpegSurroundProcessor processor = new FFmpegSurroundProcessor(ffmpegPath);

            FFmpegSurroundProcessor.Result result =
                    processor.processWithFfmpeg(
                            new SurroundProcessor.Config.Builder()
                                    .setTrackId(download.trackId)
                                    .setInputPath(sourceFile.getAbsolutePath())
                                    .setOutputPath(surroundOutput.getAbsolutePath())
                                    .setOutputMode(SurroundProcessor.OutputMode.AC3)
                                    .setPreset(
                                            SurroundProcessor.Preset.fromString(settings.surroundPreset)
                                    )
                                    .setOverwrite(true)
                                    .setDebugPassthrough(false)
                                    .setBitrateKbps(448)
                                    .setSampleRateHz(48000)
                                    .setOutputChannels(6)
                                    .build()
                    );

            boolean fileOk = surroundOutput.exists() && surroundOutput.length() > 0;

            Log.i(
                    "SURROUND",
                    "AC3 surround result trackId=" + download.trackId
                            + ", success=" + result.success
                            + ", fileOk=" + fileOk
                            + ", outExists=" + surroundOutput.exists()
                            + ", outSize=" + (surroundOutput.exists() ? surroundOutput.length() : -1)
                            + ", error=" + result.error
            );

            if (result.success && fileOk) {
                logger.warn(
                        "Surround AC3 artifact generated: " + surroundOutput.getAbsolutePath(),
                        download
                );
            } else {
                logger.warn(
                        "Surround AC3 generation failed: " + result.error,
                        download
                );
            }
        } catch (Exception e) {
            logger.warn("Unexpected surround generation error: " + e, download);
            Log.e("SURROUND", "Unexpected surround generation error", e);
        }
    }

    /**
     * Resolve ffmpeg executable path.
     * FFmpegKit is bundled through the local AAR, so no external binary path is required.
     */
    @Nullable
    private String resolveFfmpegBinaryPath() {
        return "ffmpeg-kit";
    }

    // 500ms loop to update notifications and UI
    private void createProgressUpdateHandler() {
        progressUpdateHandler.postDelayed(() -> {
            updateProgress();
            createProgressUpdateHandler();
        }, 500);
    }

    // Updates notification and UI
    private void updateProgress() {
        if (threads.size() > 0) {
            Bundle b = new Bundle();
            ArrayList<Bundle> down = new ArrayList<>();

            for (int i = 0; i < threads.size(); i++) {
                Download download = threads.get(i).download;
                down.add(createProgressBundle(download));
                updateNotification(download);
            }

            b.putParcelableArrayList("downloads", down);
            sendMessage(SERVICE_ON_PROGRESS, b);
        }
    }

    // Create bundle with download progress & state
    private Bundle createProgressBundle(Download download) {
        Bundle bundle = new Bundle();
        bundle.putInt("id", download.id);
        bundle.putLong("received", download.received);
        bundle.putLong("filesize", download.filesize);
        bundle.putInt("quality", download.quality);
        bundle.putInt("state", download.state.getValue());
        return bundle;
    }

    private void updateNotification(Download download) {
        // Cancel notification for done/none/error downloads
        if (download.state == Download.DownloadState.NONE || download.state.getValue() >= 3) {
            notificationManager.cancel(NOTIFICATION_ID_START + download.id);
            return;
        }

        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(context, DownloadService.NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(download.title)
                        .setSmallIcon(R.drawable.ic_logo)
                        .setPriority(NotificationCompat.PRIORITY_MIN);

        // Show progress when downloading
        if (download.state == Download.DownloadState.DOWNLOADING) {
            if (download.filesize <= 0) download.filesize = 1;
            notificationBuilder.setContentText(
                    String.format(
                            "%s / %s",
                            formatFilesize(download.received),
                            formatFilesize(download.filesize)
                    )
            );
            notificationBuilder.setProgress(
                    100,
                    (int) ((download.received / (float) download.filesize) * 100),
                    false
            );
        }

        // Indeterminate on PostProcess
        if (download.state == Download.DownloadState.POST) {
            notificationBuilder.setContentText("Post processing...");
            notificationBuilder.setProgress(1, 1, true);
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED) {
            notificationManager.notify(
                    NOTIFICATION_ID_START + download.id,
                    notificationBuilder.build()
            );
        }
    }

    // https://stackoverflow.com/questions/3263892/format-file-size-as-mb-gb-etc
    public static String formatFilesize(long size) {
        if (size <= 0) return "0B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.##").format(
                size / Math.pow(1024, digitGroups)
        ) + " " + units[digitGroups];
    }

    // Handler for incoming messages
    class IncomingHandler extends Handler {
        IncomingHandler(Context context) {
            context.getApplicationContext();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                // Load downloads from DB
                case SERVICE_LOAD_DOWNLOADS:
                    loadDownloads();
                    break;

                // Start/Resume
                case SERVICE_START_DOWNLOAD:
                    running = true;
                    if (downloads.isEmpty()) {
                        loadDownloads();
                    }
                    updateQueue();
                    updateState();
                    break;

                // Load settings
                case SERVICE_SETTINGS_UPDATE:
                    settings = DownloadSettings.fromBundle(msg.getData());
                    if (settings != null) {
                        deezer.arl = settings.arl;
                        deezer.contentLanguage = settings.deezerLanguage;

                        Log.i(
                                "SURROUND",
                                "Settings update: playbackMode=" + settings.playbackMode
                                        + ", surroundPreset=" + settings.surroundPreset
                                        + ", downloadThreads=" + settings.downloadThreads
                        );
                    }
                    break;

                // Stop downloads
                case SERVICE_STOP_DOWNLOADS:
                    stop();
                    break;

                // Remove download
                case SERVICE_REMOVE_DOWNLOAD:
                    int downloadId = msg.getData().getInt("id");
                    for (int i = 0; i < downloads.size(); i++) {
                        Download d = downloads.get(i);
                        if (d.id == downloadId) {
                            if (d.state == Download.DownloadState.DOWNLOADING ||
                                    d.state == Download.DownloadState.POST) {
                                return;
                            }
                            downloads.remove(i);
                            break;
                        }
                    }
                    db.delete("Downloads", "id == ?", new String[]{Integer.toString(downloadId)});
                    updateState();
                    break;

                // Retry failed downloads
                case SERVICE_RETRY_DOWNLOADS:
                    db.beginTransaction();
                    for (int i = 0; i < downloads.size(); i++) {
                        Download d = downloads.get(i);
                        if (d.state == Download.DownloadState.DEEZER_ERROR ||
                                d.state == Download.DownloadState.ERROR) {
                            d.state = Download.DownloadState.NONE;
                            downloads.set(i, d);

                            ContentValues values = new ContentValues();
                            values.put("state", 0);
                            db.update(
                                    "Downloads",
                                    values,
                                    "id == ?",
                                    new String[]{Integer.toString(d.id)}
                            );
                        }
                    }
                    db.setTransactionSuccessful();
                    db.endTransaction();
                    updateState();
                    break;

                // Remove downloads by state
                case SERVICE_REMOVE_DOWNLOADS:
                    Download.DownloadState state =
                            Download.DownloadState.values()[msg.getData().getInt("state")];

                    // Don't remove currently downloading
                    if (state == Download.DownloadState.DOWNLOADING ||
                            state == Download.DownloadState.POST) {
                        return;
                    }

                    db.beginTransaction();
                    int i = (downloads.size() - 1);
                    while (i >= 0) {
                        Download d = downloads.get(i);
                        if (d.state == state) {
                            db.delete(
                                    "Downloads",
                                    "id == ?",
                                    new String[]{Integer.toString(d.id)}
                            );
                            downloads.remove(i);
                        }
                        i--;
                    }

                    db.delete(
                            "Downloads",
                            "state == ?",
                            new String[]{Integer.toString(msg.getData().getInt("state"))}
                    );

                    db.setTransactionSuccessful();
                    db.endTransaction();
                    updateState();
                    break;

                default:
                    super.handleMessage(msg);
            }
        }
    }

    // Send message to MainActivity
    void sendMessage(int type, Bundle data) {
        if (activityMessenger != null) {
            Message msg = Message.obtain(null, type);
            msg.setData(data);
            try {
                activityMessenger.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    // --------------------------------------
    // Surround helper methods
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
    private File getSurroundDirectory(boolean persistent) {
        try {
            File baseDir = persistent ? getFlutterDocumentsBaseDir() : getCacheDir();
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
    private File getSurroundOutputFile(
            @Nullable String trackId,
            boolean persistent,
            @Nullable String extension
    ) {
        if (trackId == null || trackId.trim().isEmpty()) return null;

        File surroundDir = getSurroundDirectory(persistent);
        if (surroundDir == null) return null;

        String ext = (extension == null || extension.trim().isEmpty()) ? "ac3" : extension.trim();
        return new File(surroundDir, trackId + "." + ext);
    }

    private void deleteSurroundArtifacts(@Nullable String trackId) {
        if (trackId == null || trackId.trim().isEmpty()) return;

        deleteSurroundArtifact(trackId, false, "ac3");
        deleteSurroundArtifact(trackId, true, "ac3");

        deleteSurroundArtifact(trackId, false, "ts");
        deleteSurroundArtifact(trackId, true, "ts");
    }

    private void deleteSurroundArtifact(
            @Nullable String trackId,
            boolean persistent,
            @NonNull String extension
    ) {
        try {
            File file = getSurroundOutputFile(trackId, persistent, extension);
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception e) {
            Log.w("SURROUND", "Failed deleting surround artifact " + extension, e);
        }
    }

    private void ensureSurroundDirectories() {
        getSurroundDirectory(false);
        getSurroundDirectory(true);
    }

    static class DownloadSettings {

        int downloadThreads;
        boolean overwriteDownload;
        boolean downloadLyrics;
        boolean trackCover;
        String arl;
        boolean albumCover;
        boolean nomediaFiles;
        String artistSeparator;
        int albumArtResolution;
        String deezerLanguage = "en";
        String deezerCountry = "US";
        SelectedTags tags;

        // Surround-related settings
        String playbackMode = "normal";
        String surroundPreset = "balanced";

        private DownloadSettings(
                int downloadThreads,
                boolean overwriteDownload,
                boolean downloadLyrics,
                boolean trackCover,
                String arl,
                boolean albumCover,
                boolean nomediaFiles,
                String artistSeparator,
                int albumArtResolution,
                String deezerLanguage,
                String deezerCountry,
                SelectedTags tags,
                String playbackMode,
                String surroundPreset
        ) {
            this.downloadThreads = downloadThreads;
            this.overwriteDownload = overwriteDownload;
            this.downloadLyrics = downloadLyrics;
            this.trackCover = trackCover;
            this.arl = arl;
            this.albumCover = albumCover;
            this.nomediaFiles = nomediaFiles;
            this.artistSeparator = artistSeparator;
            this.albumArtResolution = albumArtResolution;
            this.deezerLanguage = deezerLanguage;
            this.deezerCountry = deezerCountry;
            this.tags = tags;
            this.playbackMode = playbackMode;
            this.surroundPreset = surroundPreset;
        }

        boolean isSurroundEnabled() {
            return "surround".equalsIgnoreCase(playbackMode);
        }

        // Parse settings from bundle sent from UI
        static DownloadSettings fromBundle(Bundle b) {
            JSONObject json;
            try {
                json = new JSONObject(b.getString("json"));

                return new DownloadSettings(
                        json.optInt("downloadThreads", 2),
                        json.optBoolean("overwriteDownload", false),
                        json.optBoolean("downloadLyrics", true),
                        json.optBoolean("trackCover", false),
                        json.optString("arl", ""),
                        json.optBoolean("albumCover", true),
                        json.optBoolean("nomediaFiles", false),
                        json.optString("artistSeparator", ", "),
                        json.optInt("albumArtResolution", 1400),
                        json.optString("deezerLanguage", "en"),
                        json.optString("deezerCountry", "US"),
                        new SelectedTags(json.optJSONArray("tags")),
                        json.optString("playbackMode", "normal"),
                        json.optString("surroundPreset", "balanced")
                );
            } catch (Exception e) {
                Log.e("ERR", "Error loading settings!", e);
                return null;
            }
        }
    }

    static class SelectedTags {
        boolean title = false;
        boolean album = false;
        boolean artist = false;
        boolean track = false;
        boolean disc = false;
        boolean albumArtist = false;
        boolean date = false;
        boolean label = false;
        boolean isrc = false;
        boolean upc = false;
        boolean trackTotal = false;
        boolean bpm = false;
        boolean lyrics = false;
        boolean genre = false;
        boolean contributors = false;
        boolean albumArt = false;

        SelectedTags(JSONArray json) {
            if (json == null) return;

            try {
                for (int i = 0; i < json.length(); i++) {
                    switch (json.getString(i)) {
                        case "title":
                            title = true;
                            break;
                        case "album":
                            album = true;
                            break;
                        case "artist":
                            artist = true;
                            break;
                        case "track":
                            track = true;
                            break;
                        case "disc":
                            disc = true;
                            break;
                        case "albumArtist":
                            albumArtist = true;
                            break;
                        case "date":
                            date = true;
                            break;
                        case "label":
                            label = true;
                            break;
                        case "isrc":
                            isrc = true;
                            break;
                        case "upc":
                            upc = true;
                            break;
                        case "trackTotal":
                            trackTotal = true;
                            break;
                        case "bpm":
                            bpm = true;
                            break;
                        case "lyrics":
                            lyrics = true;
                            break;
                        case "genre":
                            genre = true;
                            break;
                        case "contributors":
                            contributors = true;
                            break;
                        case "art":
                            albumArt = true;
                            break;
                    }
                }
            } catch (Exception e) {
                Log.e("ERR", "Error toggling tag: " + e);
            }
        }
    }
}
