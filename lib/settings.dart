import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui';

import 'package:external_path/external_path.dart';
import 'package:flutter/material.dart';
import 'package:get_it/get_it.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:logging/logging.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'api/download.dart';
import 'main.dart';
import 'service/audio_service.dart';
import 'ui/cached_image.dart';
import 'utils/app_icon_changer.dart';

part 'settings.g.dart';

late Settings settings;

@JsonSerializable()
class Settings {
  // Language
  @JsonKey(defaultValue: null)
  String? language;

  // Main
  @JsonKey(defaultValue: false)
  late bool ignoreInterruptions;

  @JsonKey(defaultValue: false)
  late bool enableEqualizer;

  // Playback mode
  @JsonKey(defaultValue: PlaybackMode.normal)
  late PlaybackMode playbackMode;

  // Surround preset
  // Keep annotation unchanged to avoid requiring build_runner regeneration.
  // Default is normalized at runtime.
  @JsonKey(defaultValue: 'balanced')
  late String surroundPreset;

  // Account
  String? arl;

  @JsonKey(includeFromJson: false, includeToJson: false)
  bool offlineMode = false;

  // Quality
  @JsonKey(defaultValue: AudioQuality.MP3_320)
  late AudioQuality wifiQuality;

  @JsonKey(defaultValue: AudioQuality.MP3_128)
  late AudioQuality mobileQuality;

  @JsonKey(defaultValue: AudioQuality.FLAC)
  late AudioQuality offlineQuality;

  @JsonKey(defaultValue: AudioQuality.FLAC)
  late AudioQuality downloadQuality;

  // Download options
  String? downloadPath;

  @JsonKey(defaultValue: '%artist% - %title%')
  late String downloadFilename;

  @JsonKey(defaultValue: true)
  late bool albumFolder;

  @JsonKey(defaultValue: true)
  late bool artistFolder;

  @JsonKey(defaultValue: false)
  late bool albumDiscFolder;

  @JsonKey(defaultValue: false)
  late bool overwriteDownload;

  @JsonKey(defaultValue: 2)
  late int downloadThreads;

  @JsonKey(defaultValue: false)
  late bool playlistFolder;

  @JsonKey(defaultValue: true)
  late bool downloadLyrics;

  @JsonKey(defaultValue: false)
  late bool trackCover;

  @JsonKey(defaultValue: true)
  late bool albumCover;

  @JsonKey(defaultValue: false)
  late bool nomediaFiles;

  @JsonKey(defaultValue: ', ')
  late String artistSeparator;

  @JsonKey(defaultValue: '%artist% - %title%')
  late String singletonFilename;

  @JsonKey(defaultValue: 1400)
  late int albumArtResolution;

  @JsonKey(defaultValue: [
    'title',
    'album',
    'artist',
    'track',
    'disc',
    'albumArtist',
    'date',
    'label',
    'isrc',
    'upc',
    'trackTotal',
    'bpm',
    'lyrics',
    'genre',
    'contributors',
    'art'
  ])
  late List<String> tags;

  // Appearance
  @JsonKey(defaultValue: Themes.Dark)
  late Themes theme;

  @JsonKey(defaultValue: false)
  late bool useSystemTheme;

  @JsonKey(defaultValue: true)
  late bool colorGradientBackground;

  @JsonKey(defaultValue: false)
  late bool blurPlayerBackground;

  @JsonKey(defaultValue: 'Deezer')
  late String font;

  @JsonKey(defaultValue: false)
  late bool lyricsVisualizer;

  @JsonKey(defaultValue: null)
  int? displayMode;

  // Colors
  @JsonKey(toJson: _colorToJson, fromJson: _colorFromJson)
  Color primaryColor = Colors.blue;

  static int _colorToJson(Color c) => c.value;
  static Color _colorFromJson(int? v) => v == null ? Colors.blue : Color(v);

  @JsonKey(defaultValue: false)
  bool useArtColor = false;

  StreamSubscription? _useArtColorSub;

  @JsonKey(defaultValue: 'DefaultIcon')
  String? appIcon;

  // Deezer
  @JsonKey(defaultValue: 'en')
  late String deezerLanguage;

  @JsonKey(defaultValue: 'US')
  late String deezerCountry;

  @JsonKey(defaultValue: false)
  late bool logListen;

  @JsonKey(defaultValue: null)
  String? proxyAddress;

  // LastFM
  @JsonKey(defaultValue: null)
  String? lastFMUsername;

  @JsonKey(defaultValue: null)
  String? lastFMPassword;

  // Spotify
  @JsonKey(defaultValue: null)
  String? spotifyClientId;

  @JsonKey(defaultValue: null)
  String? spotifyClientSecret;

  @JsonKey(defaultValue: null)
  SpotifyCredentialsSave? spotifyCredentials;

  Settings({this.downloadPath, this.arl});

  ThemeData get themeData {
    // System theme
    if (useSystemTheme) {
      if (PlatformDispatcher.instance.platformBrightness == Brightness.light) {
        return _themeData[Themes.Light]!;
      } else {
        if (theme == Themes.Light) return _themeData[Themes.Dark]!;
        return _themeData[theme]!;
      }
    }

    // Theme
    return _themeData[theme] ?? ThemeData();
  }

  // Get all available fonts
  List<String> get fonts {
    return ['Deezer', ...GoogleFonts.asMap().keys];
  }

  // Get all available app icons
  List<String> get availableIcons {
    return AppIconChanger.availableIcons.map((icon) => icon.key).toList();
  }

  // ----------------------------
  // Surround preset helpers
  // ----------------------------

  static const List<String> supportedSurroundPresets = [
    'raw_clone',
    'room_fill_matrix',
    'wide_stage',
    'vocal_anchor',
    'immersive_music',
  ];

  static String normalizeSurroundPreset(String? value) {
    final v = (value ?? '').trim().toLowerCase();

    switch (v) {
      case 'raw':
      case 'raw_clone':
      case 'raw-stereo-clone':
      case 'raw stereo clone':
      case 'pure_stereo':
      case 'pure stereo':
        return 'raw_clone';

      case 'balanced':
      case 'room_fill':
      case 'room fill':
      case 'room_fill_matrix':
      case 'room fill matrix':
      case 'natural_matrix':
      case 'natural matrix':
        return 'room_fill_matrix';

      case 'wide':
      case 'wide_stage':
      case 'wide stage':
        return 'wide_stage';

      case 'vocal_anchor':
      case 'vocal anchor':
      case 'vocal_focus':
      case 'vocal focus':
        return 'vocal_anchor';

      case 'cinematic':
      case 'immersive':
      case 'immersive_music':
      case 'immersive music':
        return 'immersive_music';

      default:
        return 'room_fill_matrix';
    }
  }

  List<String> get availableSurroundPresets => supportedSurroundPresets;

  String get normalizedSurroundPreset =>
      Settings.normalizeSurroundPreset(surroundPreset);

  String get surroundPresetDisplayName {
    switch (normalizedSurroundPreset) {
      case 'raw_clone':
        return 'Pure Stereo';
      case 'wide_stage':
        return 'Wide Stage';
      case 'vocal_anchor':
        return 'Vocal Focus';
      case 'immersive_music':
        return 'Immersive';
      case 'room_fill_matrix':
      default:
        return 'Room Fill';
    }
  }

  String surroundPresetLabelFor(String preset) {
    switch (Settings.normalizeSurroundPreset(preset)) {
      case 'raw_clone':
        return 'Pure Stereo';
      case 'wide_stage':
        return 'Wide Stage';
      case 'vocal_anchor':
        return 'Vocal Focus';
      case 'immersive_music':
        return 'Immersive';
      case 'room_fill_matrix':
      default:
        return 'Room Fill';
    }
  }

  // JSON to forward into download service
  Map<String, dynamic> getServiceSettings() {
    return {'json': jsonEncode(toJson())};
  }

  Future<void> updateAppIcon(String iconKey) async {
    try {
      final LauncherIcon icon =
          LauncherIcon.values.firstWhere((e) => e.key == iconKey);
      await AppIconChanger.changeIcon(icon);
      appIcon = iconKey;
      await save();
    } catch (e) {
      Logger.root.severe('Error updating app icon: $e');
    }
  }

  void updateUseArtColor(bool v) {
    useArtColor = v;
    if (v) {
      // On media item change set color
      _useArtColorSub =
          GetIt.I<AudioPlayerHandler>().mediaItem.listen((event) async {
        if (event == null || event.artUri == null) return;
        primaryColor =
            await imagesDatabase.getPrimaryColor(event.artUri.toString());
        updateTheme();
      });
    } else {
      // Cancel stream subscription
      _useArtColorSub?.cancel();
      _useArtColorSub = null;
    }
  }

  SliderThemeData get _sliderTheme => SliderThemeData(
        thumbColor: primaryColor,
        activeTrackColor: primaryColor,
        inactiveTrackColor: primaryColor.withOpacity(0.2),
      );

  Future<void> _writeToDisk() async {
    final File f = File(await getPath());
    await f.writeAsString(jsonEncode(toJson()));
  }

  // Load settings/init
  Future<Settings> loadSettings() async {
    final String path = await getPath();
    final File f = File(path);

    if (await f.exists()) {
      final String data = await f.readAsString();
      final Settings loaded = Settings.fromJson(jsonDecode(data));
      loaded.surroundPreset =
          Settings.normalizeSurroundPreset(loaded.surroundPreset);
      return loaded;
    }

    final Settings s = Settings.fromJson({});

    // Normalize surround preset so legacy/generated defaults don't matter.
    s.surroundPreset = Settings.normalizeSurroundPreset(s.surroundPreset);

    // Set default path, because async
    s.downloadPath = await ExternalPath.getExternalStoragePublicDirectory(
      ExternalPath.DIRECTORY_MUSIC,
    );

    // IMPORTANT:
    // On first run, write settings file only.
    // Do NOT push to download service yet, because global late `settings`
    // has not been assigned in prepareRun() at this point.
    await s._writeToDisk();

    return s;
  }

  Future<void> save({bool updateDownloadService = true}) async {
    surroundPreset = Settings.normalizeSurroundPreset(surroundPreset);

    await _writeToDisk();

    if (updateDownloadService) {
      await downloadManager.updateServiceSettings(this);
    }
  }

  Future<void> updateAudioServiceQuality() async {
    if (!GetIt.I.isRegistered<AudioPlayerHandler>()) {
      Logger.root.info(
        'Audio service not registered yet, skipping updateQueueQuality.',
      );
      return;
    }

    await GetIt.I<AudioPlayerHandler>().updateQueueQuality();
  }

  bool get isSurroundMode => playbackMode == PlaybackMode.surround;

  Future<void> setPlaybackMode(PlaybackMode mode) async {
    playbackMode = mode;
    await save();

    if (!GetIt.I.isRegistered<AudioPlayerHandler>()) return;

    try {
      await GetIt.I<AudioPlayerHandler>().reloadQueueForPlaybackModeChange();
    } catch (e, st) {
      Logger.root.warning(
        'Failed to reload queue after playback mode change: $e',
        e,
        st,
      );
    }
  }

  Future<void> setSurroundPreset(String preset) async {
    surroundPreset = Settings.normalizeSurroundPreset(preset);
    await save();

    if (!isSurroundMode) return;
    if (!GetIt.I.isRegistered<AudioPlayerHandler>()) return;

    try {
      await GetIt.I<AudioPlayerHandler>().reloadQueueForPlaybackModeChange();
    } catch (e, st) {
      Logger.root.warning(
        'Failed to reload queue after surround preset change: $e',
        e,
        st,
      );
    }
  }

  // AudioQuality to Deezer int
  int getQualityInt(AudioQuality q) {
    switch (q) {
      case AudioQuality.MP3_128:
        return 1;
      case AudioQuality.MP3_320:
        return 3;
      case AudioQuality.FLAC:
        return 9;
      // Deezer default
      default:
        return 8;
    }
  }

  // Check if dark theme
  bool get isDark {
    if (useSystemTheme) {
      if (PlatformDispatcher.instance.platformBrightness == Brightness.light) {
        return false;
      }
      return true;
    }
    if (theme == Themes.Light) return false;
    return true;
  }

  static const deezerBg = Color(0xFF1F1A16);
  static const deezerBottom = Color(0xFF1B1714);

  TextTheme? get textTheme => (font == 'Deezer')
      ? null
      : GoogleFonts.getTextTheme(
          font,
          isDark ? ThemeData.dark().textTheme : ThemeData.light().textTheme,
        );

  String? get _fontFamily => (font == 'Deezer') ? 'MabryPro' : null;

  // Overrides for buttons
  OutlinedButtonThemeData get outlinedButtonTheme => OutlinedButtonThemeData(
        style: ButtonStyle(
          foregroundColor:
              WidgetStateProperty.all(isDark ? Colors.white : Colors.black),
          side:
              WidgetStateProperty.all(BorderSide(color: Colors.grey.shade800)),
        ),
      );

  TextButtonThemeData get textButtonTheme => TextButtonThemeData(
        style: ButtonStyle(
          foregroundColor:
              WidgetStateProperty.all(isDark ? Colors.white : Colors.black),
        ),
      );

  Map<Themes, ThemeData> get _themeData => {
        Themes.Light: ThemeData(
          useMaterial3: false,
          brightness: Brightness.light,
          textTheme: textTheme,
          fontFamily: _fontFamily,
          primaryColor: primaryColor,
          sliderTheme: _sliderTheme,
          outlinedButtonTheme: outlinedButtonTheme,
          textButtonTheme: textButtonTheme,
          colorScheme: ColorScheme.fromSwatch().copyWith(
            secondary: primaryColor,
            brightness: Brightness.light,
          ),
          checkboxTheme: CheckboxThemeData(
            fillColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          radioTheme: RadioThemeData(
            fillColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          switchTheme: SwitchThemeData(
            thumbColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
            trackColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          bottomAppBarTheme:
              const BottomAppBarTheme(color: Color(0xFFF5F5F5)),
        ),
        Themes.Dark: ThemeData(
          useMaterial3: false,
          brightness: Brightness.dark,
          textTheme: textTheme,
          fontFamily: _fontFamily,
          primaryColor: primaryColor,
          sliderTheme: _sliderTheme,
          outlinedButtonTheme: outlinedButtonTheme,
          textButtonTheme: textButtonTheme,
          colorScheme: ColorScheme.fromSwatch().copyWith(
            secondary: primaryColor,
            brightness: Brightness.dark,
          ),
          checkboxTheme: CheckboxThemeData(
            fillColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          radioTheme: RadioThemeData(
            fillColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          switchTheme: SwitchThemeData(
            thumbColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
            trackColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          bottomAppBarTheme:
              const BottomAppBarTheme(color: Color(0xFF424242)),
        ),
        Themes.Deezer: ThemeData(
          useMaterial3: false,
          brightness: Brightness.dark,
          textTheme: textTheme,
          fontFamily: _fontFamily,
          primaryColor: primaryColor,
          sliderTheme: _sliderTheme,
          scaffoldBackgroundColor: deezerBg,
          bottomSheetTheme:
              const BottomSheetThemeData(backgroundColor: deezerBottom),
          cardColor: deezerBg,
          outlinedButtonTheme: outlinedButtonTheme,
          textButtonTheme: textButtonTheme,
          colorScheme: ColorScheme.fromSwatch().copyWith(
            secondary: primaryColor,
            surface: deezerBg,
            brightness: Brightness.dark,
          ),
          checkboxTheme: CheckboxThemeData(
            fillColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          radioTheme: RadioThemeData(
            fillColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          switchTheme: SwitchThemeData(
            thumbColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
            trackColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          bottomAppBarTheme: const BottomAppBarTheme(color: deezerBottom),
          dialogTheme: const DialogThemeData(backgroundColor: deezerBottom),
        ),
        Themes.Black: ThemeData(
          useMaterial3: false,
          brightness: Brightness.dark,
          textTheme: textTheme,
          fontFamily: _fontFamily,
          primaryColor: primaryColor,
          scaffoldBackgroundColor: Colors.black,
          sliderTheme: _sliderTheme,
          bottomSheetTheme: const BottomSheetThemeData(
            backgroundColor: Colors.black,
          ),
          outlinedButtonTheme: outlinedButtonTheme,
          textButtonTheme: textButtonTheme,
          colorScheme: ColorScheme.fromSwatch().copyWith(
            secondary: primaryColor,
            surface: Colors.black,
            brightness: Brightness.dark,
          ),
          checkboxTheme: CheckboxThemeData(
            fillColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          radioTheme: RadioThemeData(
            fillColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          switchTheme: SwitchThemeData(
            thumbColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
            trackColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.disabled)) {
                  return null;
                }
                if (states.contains(WidgetState.selected)) {
                  return primaryColor;
                }
                return null;
              },
            ),
          ),
          bottomAppBarTheme: const BottomAppBarTheme(color: Colors.black),
          dialogTheme: const DialogThemeData(backgroundColor: Colors.black),
        ),
      };

  Future<String> getPath() async =>
      p.join((await getApplicationDocumentsDirectory()).path, 'settings.json');

  // JSON
  factory Settings.fromJson(Map<String, dynamic> json) =>
      _$SettingsFromJson(json);

  Map<String, dynamic> toJson() => _$SettingsToJson(this);
}

enum AudioQuality { MP3_128, MP3_320, FLAC, ASK }

enum PlaybackMode { normal, surround }

enum Themes { Light, Dark, Deezer, Black }

@JsonSerializable()
class SpotifyCredentialsSave {
  String? accessToken;
  String? refreshToken;
  List<String>? scopes;
  DateTime? expiration;

  SpotifyCredentialsSave({
    this.accessToken,
    this.refreshToken,
    this.scopes,
    this.expiration,
  });

  // JSON
  factory SpotifyCredentialsSave.fromJson(Map<String, dynamic> json) =>
      _$SpotifyCredentialsSaveFromJson(json);

  Map<String, dynamic> toJson() => _$SpotifyCredentialsSaveToJson(this);
}
