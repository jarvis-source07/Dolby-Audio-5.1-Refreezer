import 'dart:async';
import 'dart:io';

import 'package:disk_space_plus/disk_space_plus.dart';
import 'package:filesize/filesize.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_cache_manager/flutter_cache_manager.dart';
import 'package:fluttertoast/fluttertoast.dart';
import 'package:logging/logging.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart';

import '../api/deezer.dart';
import '../api/definitions.dart';
import '../settings.dart';
import '../translations.i18n.dart';
import '../utils/file_utils.dart';
import '../utils/navigator_keys.dart';

DownloadManager downloadManager = DownloadManager();

class DownloadManager {
  // Platform channels
  static const MethodChannel platform = MethodChannel('r.r.refreezer/native');
  static const EventChannel eventChannel =
      EventChannel('r.r.refreezer/downloads');

  bool running = false;
  int queueSize = 0;

  StreamController serviceEvents = StreamController.broadcast();
  String? offlinePath;
  Database? db;

  bool _initialized = false;
  StreamSubscription? _eventSubscription;

  // Start/Resume downloads
  Future<void> start() async {
    await updateServiceSettings();
    await platform.invokeMethod('start');
  }

  // Stop/Pause downloads
  Future<void> stop() async {
    await platform.invokeMethod('stop');
  }

  Future<void> init() async {
    if (_initialized) return;

    // Remove old DB
    final File oldDbFile = File(p.join((await getDatabasesPath()), 'offline.db'));
    if (await oldDbFile.exists()) {
      await oldDbFile.delete();
    }

    final String dbPath = p.join((await getDatabasesPath()), 'offline2.db');

    // Open db
    db = await openDatabase(
      dbPath,
      version: 1,
      onCreate: (Database db, int version) async {
        final Batch b = db.batch();
        b.execute('''CREATE TABLE Tracks (
          id TEXT PRIMARY KEY, title TEXT, album TEXT, artists TEXT, duration INTEGER, albumArt TEXT, trackNumber INTEGER, offline INTEGER, lyrics TEXT, favorite INTEGER, diskNumber INTEGER, explicit INTEGER, fallback INTEGER)''');
        b.execute('''CREATE TABLE Albums (
          id TEXT PRIMARY KEY, title TEXT, artists TEXT, tracks TEXT, art TEXT, fans INTEGER, offline INTEGER, library INTEGER, type INTEGER, releaseDate TEXT)''');
        b.execute('''CREATE TABLE Artists (
          id TEXT PRIMARY KEY, name TEXT, albums TEXT, topTracks TEXT, picture TEXT, fans INTEGER, albumCount INTEGER, offline INTEGER, library INTEGER, radio INTEGER)''');
        b.execute('''CREATE TABLE Playlists (
          id TEXT PRIMARY KEY, title TEXT, tracks TEXT, image TEXT, duration INTEGER, userId TEXT, userName TEXT, fans INTEGER, library INTEGER, description TEXT)''');
        await b.commit();
      },
    );

    // Create offline directory
    final directory = await getExternalStorageDirectory();
    if (directory != null) {
      offlinePath = p.join(directory.path, 'offline/');
      await Directory(offlinePath!).create(recursive: true);
    }

    // Precreate surround directories
    await getSurroundDirectory();
    await getSurroundDirectory(persistent: true);

    // Update settings safely
    await updateServiceSettings();

    // Listen to state change event only once
    _eventSubscription ??= eventChannel.receiveBroadcastStream().listen((e) {
      if (e['action'] == 'onStateChange') {
        running = e['running'];
        queueSize = e['queueSize'];
      }

      // Forward
      serviceEvents.add(e);
    });

    await platform.invokeMethod('loadDownloads');
    _initialized = true;
  }

  // ----------------------------
  // Surround helpers
  // ----------------------------

  /// Returns the directory used for generated surround files.
  /// [persistent=false] => temp dir
  /// [persistent=true]  => app documents dir
  Future<Directory> getSurroundDirectory({bool persistent = false}) async {
    final Directory baseDir = persistent
        ? await getApplicationDocumentsDirectory()
        : await getTemporaryDirectory();

    final Directory surroundDir = Directory(
      p.join(baseDir.path, 'surround'),
    );

    if (!await surroundDir.exists()) {
      await surroundDir.create(recursive: true);
    }

    return surroundDir;
  }

  /// Builds a surround output path for a track.
  Future<String> getSurroundPathForTrack(
    String trackId, {
    String extension = 'ac3',
    bool persistent = false,
  }) async {
    final Directory dir = await getSurroundDirectory(persistent: persistent);
    return p.join(dir.path, '$trackId.$extension');
  }

  /// Finds an already existing surround file.
  ///
  /// Preference:
  /// - if [extension] is provided => search that extension only
  /// - otherwise AC3 first, then TS
  Future<String?> findExistingSurroundPath(
    String trackId, {
    String? extension,
  }) async {
    final List<String> extensions;

    if (extension != null && extension.trim().isNotEmpty) {
      extensions = [extension.trim()];
    } else {
      extensions = const ['ac3', 'ts'];
    }

    // Ask native side first because it already knows the same lookup strategy
    // and current native path prefers AC3 as primary artifact.
    for (final ext in extensions) {
      try {
        final String? nativePath =
            await platform.invokeMethod<String>('findExistingSurroundPath', {
          'trackId': trackId,
          'extension': ext,
        });

        if (nativePath != null && nativePath.trim().isNotEmpty) {
          final file = File(nativePath);
          if (await file.exists()) {
            return file.path;
          }
        }
      } catch (_) {}
    }

    // Fallback local lookup
    for (final ext in extensions) {
      final List<String> candidates = [];

      try {
        final tempDir = await getTemporaryDirectory();
        candidates.add(p.join(tempDir.path, 'surround', '$trackId.$ext'));
      } catch (_) {}

      try {
        final docsDir = await getApplicationDocumentsDirectory();
        candidates.add(p.join(docsDir.path, 'surround', '$trackId.$ext'));
      } catch (_) {}

      try {
        final extDir = await getExternalStorageDirectory();
        if (extDir != null) {
          candidates.add(p.join(extDir.path, 'surround', '$trackId.$ext'));
        }
      } catch (_) {}

      for (final candidate in candidates) {
        final file = File(candidate);
        if (await file.exists()) {
          return file.path;
        }
      }
    }

    return null;
  }

  Future<bool> hasSurroundFile(
    String trackId, {
    String? extension,
  }) async {
    return (await findExistingSurroundPath(trackId, extension: extension)) !=
        null;
  }

  Future<File?> getSurroundFileForTrack(
    String trackId, {
    String? extension,
  }) async {
    final String? path =
        await findExistingSurroundPath(trackId, extension: extension);
    if (path == null) return null;
    return File(path);
  }

  /// Returns a copy of the track with surround metadata attached if a file exists.
  Future<Track> attachSurroundMetadata(
    Track track, {
    String preset = 'balanced',
    String? extension,
  }) async {
    if ((track.id ?? '').isEmpty) return track;

    final String? existingPath =
        await findExistingSurroundPath(track.id!, extension: extension);

    if (existingPath == null) {
      return track.copyWith(
        surroundTsPath: null,
        surroundReady: false,
        surroundPreset: track.surroundPreset,
      );
    }

    return track.copyWith(
      surroundTsPath: existingPath,
      surroundReady: true,
      surroundPreset: track.surroundPreset ?? preset,
    );
  }

  /// Removes surround files for a track.
  ///
  /// If [extension] is omitted/null/empty, deletes both AC3 and TS variants.
  Future<void> removeSurroundFilesForTrack(
    String trackId, {
    String? extension,
  }) async {
    final List<String> extensions;

    if (extension != null && extension.trim().isNotEmpty) {
      extensions = [extension.trim()];
    } else {
      extensions = const ['ac3', 'ts'];
    }

    for (final ext in extensions) {
      final List<String> candidates = [];

      try {
        final tempDir = await getTemporaryDirectory();
        candidates.add(p.join(tempDir.path, 'surround', '$trackId.$ext'));
      } catch (_) {}

      try {
        final docsDir = await getApplicationDocumentsDirectory();
        candidates.add(p.join(docsDir.path, 'surround', '$trackId.$ext'));
      } catch (_) {}

      try {
        final extDir = await getExternalStorageDirectory();
        if (extDir != null) {
          candidates.add(p.join(extDir.path, 'surround', '$trackId.$ext'));
        }
      } catch (_) {}

      for (final candidate in candidates) {
        try {
          final file = File(candidate);
          if (await file.exists()) {
            await file.delete();
          }
        } catch (e) {
          Logger.root.warning('Failed to delete surround file: $candidate', e);
        }
      }

      // Also ask native side to delete its known paths
      try {
        await platform.invokeMethod('deleteSurroundFile', {
          'trackId': trackId,
          'extension': ext,
        });
      } catch (_) {}
    }
  }

  /// Clears generated surround cache folders.
  /// [persistent=true] clears docs-based surround cache too.
  Future<void> clearSurroundCache({bool persistent = true}) async {
    final List<Directory> dirs = [];

    try {
      dirs.add(await getSurroundDirectory(persistent: false));
    } catch (_) {}

    if (persistent) {
      try {
        dirs.add(await getSurroundDirectory(persistent: true));
      } catch (_) {}
    }

    for (final dir in dirs) {
      try {
        if (await dir.exists()) {
          await dir.delete(recursive: true);
        }
      } catch (e) {
        Logger.root.warning('Failed to clear surround cache: ${dir.path}', e);
      }
    }

    // Also clear native side known cache folders
    try {
      await platform.invokeMethod('clearSurroundCache', {
        'persistent': persistent,
      });
    } catch (_) {}

    // Recreate dirs for future use
    try {
      await getSurroundDirectory(persistent: false);
    } catch (_) {}
    if (persistent) {
      try {
        await getSurroundDirectory(persistent: true);
      } catch (_) {}
    }
  }

  // ----------------------------
  // Native AC3 direct playback helpers
  // ----------------------------

  Future<bool> isDirectAc3PlaybackSupported({
    int sampleRateHz = 48000,
  }) async {
    try {
      return await platform.invokeMethod<bool>(
            'isDirectAc3PlaybackSupported',
            {'sampleRateHz': sampleRateHz},
          ) ??
          false;
    } catch (e) {
      Logger.root.warning('isDirectAc3PlaybackSupported failed', e);
      return false;
    }
  }

  Future<bool> playNativeAc3(
    String path, {
    int sampleRateHz = 48000,
  }) async {
    try {
      return await platform.invokeMethod<bool>(
            'playNativeAc3',
            {
              'path': path,
              'sampleRateHz': sampleRateHz,
            },
          ) ??
          false;
    } catch (e) {
      Logger.root.warning('playNativeAc3 failed', e);
      return false;
    }
  }

  Future<bool> stopNativeAc3() async {
    try {
      return await platform.invokeMethod<bool>('stopNativeAc3') ?? false;
    } catch (e) {
      Logger.root.warning('stopNativeAc3 failed', e);
      return false;
    }
  }

  Future<bool> isNativeAc3Playing() async {
    try {
      return await platform.invokeMethod<bool>('isNativeAc3Playing') ?? false;
    } catch (e) {
      Logger.root.warning('isNativeAc3Playing failed', e);
      return false;
    }
  }

  // Get all downloads from db
  Future<List<Download>> getDownloads() async {
    final List raw = await platform.invokeMethod('getDownloads');
    return raw.map((d) => Download.fromJson(d)).toList();
  }

  // Insert track and metadata to DB
  Future<Batch> _addTrackToDB(
    Batch batch,
    Track track,
    bool overwriteTrack,
  ) async {
    batch.insert(
      'Tracks',
      track.toSQL(off: true),
      conflictAlgorithm: overwriteTrack
          ? ConflictAlgorithm.replace
          : ConflictAlgorithm.ignore,
    );

    batch.insert(
      'Albums',
      track.album?.toSQL(off: false) as Map<String, dynamic>,
      conflictAlgorithm: ConflictAlgorithm.ignore,
    );

    // Artists
    if (track.artists != null) {
      for (final Artist a in track.artists!) {
        batch.insert(
          'Artists',
          a.toSQL(off: false),
          conflictAlgorithm: ConflictAlgorithm.ignore,
        );
      }
    }

    return batch;
  }

  // Quality selector for custom quality
  Future<AudioQuality?> qualitySelect() async {
    AudioQuality? quality;
    await showModalBottomSheet(
      context: mainNavigatorKey.currentContext!,
      builder: (context) {
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(0, 12, 0, 2),
              child: Text(
                'Quality'.i18n,
                style: const TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 20.0,
                ),
              ),
            ),
            ListTile(
              title: const Text('MP3 128kbps'),
              onTap: () {
                quality = AudioQuality.MP3_128;
                mainNavigatorKey.currentState?.pop();
              },
            ),
            ListTile(
              title: const Text('MP3 320kbps'),
              onTap: () {
                quality = AudioQuality.MP3_320;
                mainNavigatorKey.currentState?.pop();
              },
            ),
            ListTile(
              title: const Text('FLAC'),
              onTap: () {
                quality = AudioQuality.FLAC;
                mainNavigatorKey.currentState?.pop();
              },
            ),
          ],
        );
      },
    );
    return quality;
  }

  Future<bool> openStoragePermissionSettingsDialog() async {
    final Completer<bool> completer = Completer<bool>();

    await showDialog(
      context: mainNavigatorKey.currentContext!,
      builder: (context) {
        return AlertDialog(
          title: Text('Storage Permission Required'.i18n),
          content: Text(
            'Storage permission is required to download content.\nPlease open system settings and grant storage permission to ReFreezer.'
                .i18n,
          ),
          actions: <Widget>[
            TextButton(
              child: Text('Cancel'.i18n),
              onPressed: () {
                Navigator.of(context).pop();
                completer.complete(false);
              },
            ),
            TextButton(
              child: Text('Open system settings'.i18n),
              onPressed: () {
                Navigator.of(context).pop();
                completer.complete(true);
              },
            ),
          ],
        );
      },
    );

    return completer.future;
  }

  Future<bool> openSAFPermissionDialog() async {
    final Completer<bool> completer = Completer<bool>();

    await showDialog(
      context: mainNavigatorKey.currentContext!,
      builder: (context) {
        return AlertDialog(
          title: Text('External Storage Access Required'.i18n),
          content: Text(
            'To download files to the external storage, please grant access to the SD card or USB root directory in the following screen.'
                .i18n,
          ),
          actions: <Widget>[
            TextButton(
              child: Text('Cancel'.i18n),
              onPressed: () {
                Navigator.of(context).pop();
                completer.complete(false);
              },
            ),
            TextButton(
              child: Text('Continue'.i18n),
              onPressed: () {
                Navigator.of(context).pop();
                completer.complete(true);
              },
            ),
          ],
        );
      },
    );

    return completer.future;
  }

  Future<bool> addOfflineTrack(
    Track track, {
    bool private = true,
    bool isSingleton = false,
  }) async {
    // Permission
    if (!private && !(await checkPermission())) return false;

    // Ask for quality
    AudioQuality? quality;
    if (!private && settings.downloadQuality == AudioQuality.ASK) {
      quality = await qualitySelect();
      if (quality == null) return false;
    }

    // Fetch track if missing meta
    if (track.artists == null || track.artists!.isEmpty) {
      track = await deezerAPI.track(track.id!);
    }

    // Add to DB
    if (private) {
      Batch b = db!.batch();
      b = await _addTrackToDB(b, track, true);
      await b.commit();

      // Cache art
      DefaultCacheManager().getSingleFile(track.albumArt?.thumb ?? '');
      DefaultCacheManager().getSingleFile(track.albumArt?.full ?? '');
    }

    // Get path
    final String path = _generatePath(track, private, isSingleton: isSingleton);
    await platform.invokeMethod('addDownloads', [
      await Download.jsonFromTrack(
        track,
        path,
        private: private,
        quality: quality,
      )
    ]);
    await start();
    return true;
  }

  Future<bool> addOfflineAlbum(Album album, {bool private = true}) async {
    // Permission
    if (!private && !(await checkPermission())) return false;

    // Ask for quality
    AudioQuality? quality;
    if (!private && settings.downloadQuality == AudioQuality.ASK) {
      quality = await qualitySelect();
      if (quality == null) return false;
    }

    // Get from API if no tracks
    if (album.tracks == null || album.tracks!.isEmpty) {
      album = await deezerAPI.album(album.id ?? '');
    }

    // Add to DB
    if (private) {
      // Cache art
      DefaultCacheManager().getSingleFile(album.art?.thumb ?? '');
      DefaultCacheManager().getSingleFile(album.art?.full ?? '');

      Batch b = db!.batch();
      b.insert(
        'Albums',
        album.toSQL(off: true),
        conflictAlgorithm: ConflictAlgorithm.replace,
      );

      for (final Track t in album.tracks ?? []) {
        b = await _addTrackToDB(b, t, false);
      }
      await b.commit();
    }

    // Create downloads
    final List<Map> out = [];
    for (final Track t in (album.tracks ?? [])) {
      out.add(
        await Download.jsonFromTrack(
          t,
          _generatePath(t, private),
          private: private,
          quality: quality,
        ),
      );
    }

    await platform.invokeMethod('addDownloads', out);
    await start();
    return true;
  }

  Future<bool> addOfflinePlaylist(
    Playlist playlist, {
    bool private = true,
    AudioQuality? quality,
  }) async {
    // Permission
    if (!private && !(await checkPermission())) return false;

    // Ask for quality
    if (!private &&
        settings.downloadQuality == AudioQuality.ASK &&
        quality == null) {
      quality = await qualitySelect();
      if (quality == null) return false;
    }

    // Get tracks if missing
    if ((playlist.tracks == null) ||
        (playlist.tracks?.length ?? 0) < (playlist.trackCount ?? 0)) {
      playlist = await deezerAPI.fullPlaylist(playlist.id ?? '');
    }

    // Add to DB
    if (private) {
      Batch b = db!.batch();
      b.insert(
        'Playlists',
        playlist.toSQL(),
        conflictAlgorithm: ConflictAlgorithm.replace,
      );

      for (final Track t in (playlist.tracks ?? [])) {
        b = await _addTrackToDB(b, t, false);

        // Cache art
        DefaultCacheManager().getSingleFile(t.albumArt?.thumb ?? '');
        DefaultCacheManager().getSingleFile(t.albumArt?.full ?? '');
      }
      await b.commit();
    }

    // Generate downloads
    final List<Map> out = [];
    for (int i = 0; i < (playlist.tracks?.length ?? 0); i++) {
      final Track t = playlist.tracks![i];
      out.add(
        await Download.jsonFromTrack(
          t,
          _generatePath(
            t,
            private,
            playlistName: playlist.title,
            playlistTrackNumber: i,
          ),
          private: private,
          quality: quality,
        ),
      );
    }

    await platform.invokeMethod('addDownloads', out);
    await start();
    return true;
  }

  // Get track and meta from offline DB
  Future<Track?> getOfflineTrack(
    String id, {
    Album? album,
    List<Artist>? artists,
  }) async {
    final List tracks = await db!.query(
      'Tracks',
      where: 'id == ?',
      whereArgs: [id],
    );
    if (tracks.isEmpty) return null;

    final Track track = Track.fromSQL(tracks[0]);

    // Get album
    if (album == null) {
      final List rawAlbums = await db!.query(
        'Albums',
        where: 'id == ?',
        whereArgs: [track.album?.id],
      );
      if (rawAlbums.isNotEmpty) track.album = Album.fromSQL(rawAlbums[0]);
    } else {
      track.album = album;
    }

    // Get artists
    if (artists == null) {
      final List<Artist> newArtists = [];
      for (final Artist artist in (track.artists ?? [])) {
        final List rawArtist = await db!.query(
          'Artists',
          where: 'id == ?',
          whereArgs: [artist.id],
        );
        if (rawArtist.isNotEmpty) {
          newArtists.add(Artist.fromSQL(rawArtist[0]));
        }
      }
      if (newArtists.isNotEmpty) track.artists = newArtists;
    } else {
      track.artists = artists;
    }

    return track;
  }

  // Get offline library tracks
  Future<List<Track>> getOfflineTracks() async {
    final List rawTracks = await db!.query(
      'Tracks',
      where: 'library == 1 AND offline == 1',
      columns: ['id'],
    );

    final List<Track> out = [];
    for (final Map rawTrack in rawTracks) {
      final offlineTrack = await getOfflineTrack(rawTrack['id']);
      if (offlineTrack != null) out.add(offlineTrack);
    }
    return out;
  }

  // Get all offline available tracks
  Future<List<Track>> allOfflineTracks() async {
    final List rawTracks = await db!.query(
      'Tracks',
      where: 'offline == 1',
      columns: ['id'],
    );

    final List<Track> out = [];
    for (final Map rawTrack in rawTracks) {
      final offlineTrack = await getOfflineTrack(rawTrack['id']);
      if (offlineTrack != null) out.add(offlineTrack);
    }
    return out;
  }

  // Get all offline albums
  Future<List<Album>> getOfflineAlbums() async {
    final List rawAlbums = await db!.query(
      'Albums',
      where: 'offline == 1',
      columns: ['id'],
    );

    final List<Album> out = [];
    for (final Map rawAlbum in rawAlbums) {
      final offlineAlbum = await getOfflineAlbum(rawAlbum['id']);
      if (offlineAlbum != null) out.add(offlineAlbum);
    }
    return out;
  }

  // Get offline album with meta
  Future<Album?> getOfflineAlbum(String id) async {
    final List rawAlbums = await db!.query(
      'Albums',
      where: 'id == ?',
      whereArgs: [id],
    );
    if (rawAlbums.isEmpty) return null;

    final Album album = Album.fromSQL(rawAlbums[0]);

    final List<Track> tracks = [];
    for (int i = 0; i < (album.tracks?.length ?? 0); i++) {
      final offlineTrack = await getOfflineTrack(album.tracks![i].id!);
      if (offlineTrack != null) tracks.add(offlineTrack);
    }
    album.tracks = tracks;

    final List<Artist> artists = [];
    for (int i = 0; i < (album.artists?.length ?? 0); i++) {
      artists.add(
        (await getOfflineArtist(album.artists![i].id ?? '')) ??
            album.artists![i],
      );
    }
    album.artists = artists;

    return album;
  }

  // Get offline artist METADATA, not tracks
  Future<Artist?> getOfflineArtist(String id) async {
    final List rawArtists = await db!.query(
      'Artists',
      where: 'id == ?',
      whereArgs: [id],
    );
    if (rawArtists.isEmpty) return null;
    return Artist.fromSQL(rawArtists[0]);
  }

  // Get all offline playlists
  Future<List<Playlist>> getOfflinePlaylists() async {
    final List rawPlaylists = await db!.query('Playlists', columns: ['id']);
    final List<Playlist> out = [];
    for (final Map rawPlaylist in rawPlaylists) {
      final offlinePlayList = await getOfflinePlaylist(rawPlaylist['id']);
      if (offlinePlayList != null) out.add(offlinePlayList);
    }
    return out;
  }

  // Get offline playlist
  Future<Playlist?> getOfflinePlaylist(String id) async {
    final List rawPlaylists = await db!.query(
      'Playlists',
      where: 'id == ?',
      whereArgs: [id],
    );
    if (rawPlaylists.isEmpty) return null;

    final Playlist playlist = Playlist.fromSQL(rawPlaylists[0]);

    final List<Track> tracks = [];
    if (playlist.tracks != null) {
      for (final Track t in playlist.tracks!) {
        final offlineTrack = await getOfflineTrack(t.id!);
        if (offlineTrack != null) tracks.add(offlineTrack);
      }
    }
    playlist.tracks = tracks;
    return playlist;
  }

  Future<void> removeOfflineTracks(List<Track> tracks) async {
    for (final Track t in tracks) {
      // Check if library
      final List rawTrack = await db!.query(
        'Tracks',
        where: 'id == ?',
        whereArgs: [t.id],
        columns: ['favorite'],
      );

      if (rawTrack.isNotEmpty) {
        // Count occurrences in playlists and albums
        final List albums = await db!
            .rawQuery('SELECT (id) FROM Albums WHERE tracks LIKE "%${t.id}%"');
        final List playlists = await db!.rawQuery(
          'SELECT (id) FROM Playlists WHERE tracks LIKE "%${t.id}%"',
        );

        if (albums.length + playlists.length == 0 &&
            rawTrack[0]['favorite'] == 0) {
          await db!.delete('Tracks', where: 'id == ?', whereArgs: [t.id]);
        } else {
          await db!.update(
            'Tracks',
            {'offline': 0},
            where: 'id == ?',
            whereArgs: [t.id],
          );
        }
      }

      // Remove file
      try {
        File(p.join(offlinePath!, t.id)).delete();
      } catch (e) {
        Logger.root.severe('Error deleting offline track: ${t.id}', e);
      }

      // Also remove generated surround artifacts tied to the same track id
      if ((t.id ?? '').isNotEmpty) {
        await removeSurroundFilesForTrack(t.id!);
      }
    }
  }

  Future<void> removeOfflineAlbum(String id) async {
    final List rawAlbums = await db!.query(
      'Albums',
      where: 'id == ?',
      whereArgs: [id],
    );
    if (rawAlbums.isEmpty) return;

    final Album album = Album.fromSQL(rawAlbums[0]);

    await db!.delete('Albums', where: 'id == ?', whereArgs: [id]);
    await removeOfflineTracks(album.tracks!);
  }

  Future<void> removeOfflinePlaylist(String id) async {
    final List rawPlaylists = await db!.query(
      'Playlists',
      where: 'id == ?',
      whereArgs: [id],
    );
    if (rawPlaylists.isEmpty) return;

    final Playlist playlist = Playlist.fromSQL(rawPlaylists[0]);

    await db!.delete('Playlists', where: 'id == ?', whereArgs: [id]);
    await removeOfflineTracks(playlist.tracks!);
  }

  // Check if album, track or playlist is offline
  Future<bool> checkOffline({
    Album? album,
    Track? track,
    Playlist? playlist,
  }) async {
    if (track != null) {
      final List res = await db!.query(
        'Tracks',
        where: 'id == ? AND offline == 1',
        whereArgs: [track.id],
      );
      return res.isNotEmpty;
    } else if (album != null) {
      final List res = await db!.query(
        'Albums',
        where: 'id == ? AND offline == 1',
        whereArgs: [album.id],
      );
      return res.isNotEmpty;
    } else if (playlist != null) {
      final List res = await db!.query(
        'Playlists',
        where: 'id == ?',
        whereArgs: [playlist.id],
      );
      return res.isNotEmpty;
    }
    return false;
  }

  // Offline search
  Future<SearchResults> search(String query) async {
    final SearchResults results =
        SearchResults(tracks: [], albums: [], artists: [], playlists: []);

    // Tracks
    final List tracksData = await db!.rawQuery(
      'SELECT * FROM Tracks WHERE offline == 1 AND title like "%$query%"',
    );
    for (final Map trackData in tracksData) {
      final offlineTrack = await getOfflineTrack(trackData['id']);
      if (offlineTrack != null) results.tracks!.add(offlineTrack);
    }

    // Albums
    final List albumsData = await db!.rawQuery(
      'SELECT (id) FROM Albums WHERE offline == 1 AND title like "%$query%"',
    );
    for (final Map rawAlbum in albumsData) {
      final offlineAlbum = await getOfflineAlbum(rawAlbum['id']);
      if (offlineAlbum != null) results.albums!.add(offlineAlbum);
    }

    // Playlists
    final List playlists = await db!
        .rawQuery('SELECT * FROM Playlists WHERE title like "%$query%"');
    for (final Map playlist in playlists) {
      final offlinePlaylist = await getOfflinePlaylist(playlist['id']);
      if (offlinePlaylist != null) results.playlists!.add(offlinePlaylist);
    }

    return results;
  }

  // Sanitize filename
  String sanitize(String input) {
    final RegExp sanitize = RegExp(r'[\/\\\?\%\*\:\|\"\<\>]');
    return input.replaceAll(sanitize, '');
  }

  // Generate track download path
  String _generatePath(
    Track track,
    bool private, {
    String? playlistName,
    int? playlistTrackNumber,
    bool isSingleton = false,
  }) {
    String path;
    if (private) {
      path = p.join(offlinePath!, track.id);
    } else {
      path = settings.downloadPath ?? '';

      if ((settings.playlistFolder) && playlistName != null) {
        path = p.join(path, sanitize(playlistName));
      }

      if (settings.artistFolder) path = p.join(path, '%albumArtist%');

      // Album folder / with disk number
      if (settings.albumFolder) {
        if (settings.albumDiscFolder) {
          path = p.join(
            path,
            '%album%' + ' - Disk ' + (track.diskNumber ?? 1).toString(),
          );
        } else {
          path = p.join(path, '%album%');
        }
      }

      path = p.join(
        path,
        isSingleton ? settings.singletonFilename : settings.downloadFilename,
      );

      // Playlist track number variable
      if (playlistTrackNumber != null) {
        path = path.replaceAll(
          '%playlistTrackNumber%',
          playlistTrackNumber.toString(),
        );
        path = path.replaceAll(
          '%0playlistTrackNumber%',
          playlistTrackNumber.toString().padLeft(2, '0'),
        );
      } else {
        path = path.replaceAll('%playlistTrackNumber%', '');
        path = path.replaceAll('%0playlistTrackNumber%', '');
      }
    }
    return path;
  }

  // Get stats for library screen
  Future<List<String>> getStats() async {
    final int? trackCount = Sqflite.firstIntValue(
      (await db!.rawQuery('SELECT COUNT(*) FROM Tracks WHERE offline == 1')),
    );
    final int? albumCount = Sqflite.firstIntValue(
      (await db!.rawQuery('SELECT COUNT(*) FROM Albums WHERE offline == 1')),
    );
    final int? playlistCount = Sqflite.firstIntValue(
      (await db!.rawQuery('SELECT COUNT(*) FROM Playlists')),
    );

    // Free space
    final double diskSpace = await DiskSpacePlus.getFreeDiskSpace ?? 0;

    // Used space
    final List<FileSystemEntity> offlineStat =
        await Directory(offlinePath!).list().toList();
    int offlineSize = 0;
    for (final fs in offlineStat) {
      offlineSize += (await fs.stat()).size;
    }

    return [
      trackCount.toString(),
      albumCount.toString(),
      playlistCount.toString(),
      filesize(offlineSize),
      filesize((diskSpace * 1000000).floor()),
    ];
  }

  // Send settings to download service
  Future<void> updateServiceSettings([Settings? overrideSettings]) async {
    Settings effectiveSettings;

    try {
      effectiveSettings = overrideSettings ?? settings;
    } catch (e, st) {
      Logger.root.warning(
        'Skipping updateServiceSettings because settings is not ready yet.',
        e,
        st,
      );
      return;
    }

    try {
      Logger.root.info(
        'Sending download service settings: '
        'playbackMode=${effectiveSettings.playbackMode.name}, '
        'surroundPreset=${effectiveSettings.surroundPreset}',
      );

      await platform.invokeMethod(
        'updateSettings',
        effectiveSettings.getServiceSettings(),
      );
    } catch (e, st) {
      Logger.root.warning('updateServiceSettings failed', e, st);
    }
  }

  // Check storage permission
  Future<bool> checkPermission() async {
    if (await FileUtils.checkExternalStoragePermissions(
      openStoragePermissionSettingsDialog,
    )) {
      return true;
    } else {
      Fluttertoast.showToast(
        msg: 'Storage permission denied!'.i18n,
        toastLength: Toast.LENGTH_SHORT,
        gravity: ToastGravity.BOTTOM,
      );
      return false;
    }
  }

  // Remove download from queue/finished
  Future<void> removeDownload(int id) async {
    await platform.invokeMethod('removeDownload', {'id': id});
  }

  // Restart failed downloads
  Future<void> retryDownloads() async {
    if (!(await checkPermission())) return;
    await platform.invokeMethod('retryDownloads');
  }

  // Delete downloads by state
  Future<void> removeDownloads(DownloadState state) async {
    await platform.invokeMethod(
      'removeDownloads',
      {'state': DownloadState.values.indexOf(state)},
    );
  }
}

class Download {
  int? id;
  String? path;
  bool? private;
  String? trackId;
  String? streamTrackId;
  String? trackToken;
  String? md5origin;
  String? mediaVersion;
  String? title;
  String? image;
  int? quality;

  // Dynamic
  DownloadState? state;
  int? received;
  int? filesize;

  Download({
    this.id,
    this.path,
    this.private,
    this.trackId,
    this.streamTrackId,
    this.trackToken,
    this.md5origin,
    this.mediaVersion,
    this.title,
    this.image,
    this.state,
    this.received,
    this.filesize,
    this.quality,
  });

  // Get progress between 0 - 1
  double get progress {
    return ((received?.toDouble() ?? 0.0) / (filesize?.toDouble() ?? 1.0))
        .toDouble();
  }

  factory Download.fromJson(Map<dynamic, dynamic> data) {
    return Download(
      path: data['path'],
      image: data['image'],
      private: data['private'],
      trackId: data['trackId'],
      id: data['id'],
      state: DownloadState.values[data['state']],
      title: data['title'],
      quality: data['quality'],
    );
  }

  // Change values from "update json"
  void updateFromJson(Map<dynamic, dynamic> data) {
    quality = data['quality'];
    received = data['received'] ?? 0;
    state = DownloadState.values[data['state']];
    filesize = ((data['filesize'] ?? 0) <= 0) ? 1 : (data['filesize'] ?? 1);
  }

  // Track to download JSON for service
  static Future<Map> jsonFromTrack(
    Track t,
    String path, {
    bool private = true,
    AudioQuality? quality,
  }) async {
    // Get download info
    if (t.playbackDetails?.isEmpty ?? true) {
      t = await deezerAPI.track(t.id ?? '');
    }

    // Select playbackDetails for audio stream
    final List<dynamic>? playbackDetails =
        t.playbackDetailsFallback?.isNotEmpty == true
            ? t.playbackDetailsFallback
            : t.playbackDetails;

    return {
      'private': private,
      'trackId': t.id,
      'streamTrackId': t.fallback?.id ?? t.id,
      'md5origin': playbackDetails?[0],
      'mediaVersion': playbackDetails?[1],
      'trackToken': playbackDetails?[2],
      'quality': private
          ? settings.getQualityInt(settings.offlineQuality)
          : settings.getQualityInt((quality ?? settings.downloadQuality)),
      'title': t.title,
      'path': path,
      'image': t.albumArt?.thumb
    };
  }
}

// Has to be same order as in java
enum DownloadState { NONE, DOWNLOADING, POST, DONE, DEEZER_ERROR, ERROR }
