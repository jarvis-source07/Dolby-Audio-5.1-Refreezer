package r.r.refreezer;

import android.content.ContentValues;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

public class Download {
    int id;
    String path;
    boolean priv;
    int quality;
    String trackId;
    String streamTrackId;
    String trackToken;
    String md5origin;
    String mediaVersion;
    DownloadState state;
    String title;
    String image;

    // Dynamic
    long received;
    long filesize;

    Download(
            int id,
            String path,
            boolean priv,
            int quality,
            DownloadState state,
            String trackId,
            String md5origin,
            String mediaVersion,
            String title,
            String image,
            String trackToken
