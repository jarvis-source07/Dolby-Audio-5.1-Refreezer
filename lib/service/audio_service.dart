import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:audio_service/audio_service.dart';
import 'package:audio_session/audio_session.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:equalizer_flutter/equalizer_flutter.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:fluttertoast/fluttertoast.dart';
import 'package:just_audio/just_audio.dart';
import 'package:logging/logging.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:refreezer/utils/env.dart';
import 'package:rxdart/rxdart.dart';
import 'package:scrobblenaut/scrobblenaut.dart';

import '../api/cache.dart';
import '../api/deezer.dart';
import '../api/definitions.dart';
import '../settings.dart';
import '../translations.i18n.dart';
import '../ui/android_auto.dart';
import '../utils/mediaitem_converter.dart';

Future<AudioPlayerHandler> initAudioService() async {
  return await AudioService.init(
    builder: () => AudioPlayerHandler(),
    config: const AudioServiceConfig(
      androidNotificationChannelId: 'r.r.refreezer.audio',
      androidNotificationChannelName: 'ReFreezer',
      androidNotificationOngoing: true,
      androidStopForegroundOnPause: true,
      androidNotificationClickStartsActivity: true,
      androidNotificationChannelDescription: 'ReFreezer',
      androidNotificationIcon: 'drawable/ic_logo',
    ),
  );
}

class AudioPlayerHandler extends BaseAudioHandler
    with QueueHandler, SeekHandler {
  AudioPlayerHandler() {
    _init();
  }

  static const MethodChannel _nativeChannel =
      MethodChannel('r.r.refreezer/native');

  int? _audioSession;
  int? _prevAudioSession;
  bool _equalizerOpen = false;

  final AndroidAuto _androidAuto = AndroidAuto();

  // for some reason, dart can decide not to respect the 'await'
  final Completer<void> _playerInitializedCompleter = Completer<void>();
  late AudioPlayer _player;
  final _playlist = ConcatenatingAudioSource(children: []);

  // Prevent MediaItem change while shuffling or otherwise rearranging
  bool _rearranging = false;

  // Prevent duplicate queue load races
  bool _queueLoadInProgress = false;

  Scrobblenaut? _scrobblenaut;
  bool _scrobblenautReady = false;

  // Last logged track id
  String? _loggedTrackId;

  // Visualizer
  final StreamController _visualizerController = StreamController.broadcast();
  Stream get visualizerStream => _visualizerController.stream;
  late StreamSubscription? _visualizerSubscription;

  QueueSource? queueSource;
  StreamSubscription? _queueStateSub;
  StreamSubscription? _mediaItemSub;

  final BehaviorSubject<QueueState> _queueStateSubject =
      BehaviorSubject<QueueState>();

  Stream<QueueState> get queueStateStream => _queueStateSubject.stream;
  QueueState get queueState => _queueStateSubject.value;

  int currentIndex = 0;
  int _requestedIndex = -1;

  // ----------------------------
  // Native AC3 playback state
  // ----------------------------

  bool _nativeAc3Active = false;
  bool _desiredPlaying = false;
  bool _nativeBypassForCurrentTrack = false;
  String? _nativeTrackId;
  Duration _nativeBasePosition = Duration.zero;
  DateTime? _nativeStartedAt;
  Timer? _nativeProgressTimer;

  // Background surround generation tracking
  final Set<String> _surroundGenerationInFlight = <String>{};

  Duration get _nativeEstimatedPosition {
    if (_nativeStartedAt == null) {
      return _nativeBasePosition;
    }
    return _nativeBasePosition + DateTime.now().difference(_nativeStartedAt!);
  }

  Future<void> _init() async {
    await _startSession();
    _playerInitializedCompleter.complete();

    // Broadcast the current queue when just_audio sequence changes.
    _player.sequenceStateStream
        .map((state) {
          try {
            return state?.effectiveSequence
                .map((source) => source.tag as MediaItem)
                .toList();
          } catch (e) {
            if (e is RangeError) {
              Logger.root.severe(
                'RangeError occurred while accessing effectiveSequence: $e',
              );
              return null;
            }
            rethrow;
          }
        })
        .whereType<List<MediaItem>>()
        .distinct((a, b) => listEquals(a, b))
        .pipe(queue);

    // Update current QueueState
    _queueStateSub = Rx.combineLatest3<List<MediaItem>, PlaybackState,
            List<int>, QueueState>(
      queue,
      playbackState,
      _player.shuffleIndicesStream.whereType<List<int>>(),
      (queue, playbackState, shuffleIndices) => QueueState(
        queue,
        playbackState.queueIndex,
        playbackState.shuffleMode == AudioServiceShuffleMode.all
            ? shuffleIndices
            : null,
        playbackState.repeatMode,
        playbackState.shuffleMode,
      ),
    )
        .where(
          (state) =>
              state.shuffleIndices == null ||
              state.queue.length == state.shuffleIndices!.length,
        )
        .distinct()
        .listen(_queueStateSubject.add);

    // Broadcast media item changes after track or position change
    _mediaItemSub = Rx.combineLatest3<int?, List<MediaItem>, bool, MediaItem?>(
      _player.currentIndexStream,
      queue,
      _player.shuffleModeEnabledStream,
      (index, queue, shuffleModeEnabled) {
        if (_rearranging) return null;
        if (_requestedIndex != -1 && _requestedIndex != index) return null;

        final queueIndex = _getQueueIndex(
          index ?? 0,
          shuffleModeEnabled: shuffleModeEnabled,
        );

        return (queueIndex < queue.length) ? queue[queueIndex] : null;
      },
    ).whereType<MediaItem>().distinct().listen((item) async {
      // New item => native per-track bypass reset if track changed
      if (_nativeTrackId != item.id) {
        _nativeBypassForCurrentTrack = false;
        _nativeBasePosition = Duration.zero;
      }

      mediaItem.add(item);

      final int queueIndex = queue.value.indexOf(item);
      final int queueLength = queue.value.length;

      if (queueLength - queueIndex == 1) {
        Logger.root.info('loaded last track of queue, adding more tracks');
        _onQueueEnd();
      }

      _saveQueueToFile();
      _addToHistory(item);

      _fireAndForgetSurroundGeneration(item);
      _fireAndForgetSurroundGenerationForNextTrack();

      // If user was in playing state and switched track while native was active,
      // stop native and start new track according to current mode.
      if (_desiredPlaying &&
          _nativeAc3Active &&
          _nativeTrackId != null &&
          _nativeTrackId != item.id) {
        await _stopNativeAc3(updateState: false);
        await Future.delayed(const Duration(milliseconds: 120));
        await _playCurrentAccordingToMode();
      }
    });

    // Propagate all events from the audio player to AudioService clients.
    _player.playbackEventStream
        .listen(_broadcastState, onError: _playbackError);

    _player.shuffleModeEnabledStream
        .listen((_) => _broadcastState(_player.playbackEvent));

    _player.loopModeStream.listen((_) => _broadcastState(_player.playbackEvent));

    _player.processingStateStream.listen((state) async {
      if (_nativeAc3Active) {
        return;
      }

      if (state == ProcessingState.completed && _player.playing) {
        stop();
        _player.seek(Duration.zero, index: 0);
      }
    });

    // Audio session / Equalizer
    _player.androidAudioSessionIdStream.listen((session) {
      if (!settings.enableEqualizer) return;

      _prevAudioSession = _audioSession;
      _audioSession = session;
      if (_audioSession == null) return;

      if (!_equalizerOpen) {
        EqualizerFlutter.open(session!);
        _equalizerOpen = true;
        return;
      }

      if (_prevAudioSession != _audioSession) {
        if (_prevAudioSession != null) {
          EqualizerFlutter.removeAudioSessionId(_prevAudioSession!);
        }
        EqualizerFlutter.setAudioSessionId(_audioSession!);
      }
    });

    // When 75% played, cache loggedTrackId and optionally log listen
    AudioService.position.listen((position) {
      if (mediaItem.value == null || !playbackState.value.playing) {
        return;
      }

      final duration = mediaItem.value!.duration;
      if (duration == null || duration.inSeconds <= 0) return;

      if (position.inSeconds > (duration.inSeconds * 0.75)) {
        if (cache.loggedTrackId == mediaItem.value!.id) return;
        cache.loggedTrackId = mediaItem.value!.id;
        cache.save();

        if (settings.logListen) {
          deezerAPI.logListen(mediaItem.value!.id);
        }
      }
    });
  }

  @override
  Future<void> play() async {
    _desiredPlaying = true;
    await _playCurrentAccordingToMode();

    // Scrobble to LastFM / add to history if new track
    MediaItem? newMediaItem = mediaItem.value;
    if (newMediaItem != null && newMediaItem.id != _loggedTrackId) {
      await _addToHistory(newMediaItem);
    }
  }

  Future<void> _playCurrentAccordingToMode() async {
    final currentItem = mediaItem.value;
    if (currentItem == null) {
      await _syncJustAudioToStoredPositionIfNeeded();
      await _player.setVolume(1.0);
      await _player.play();
      return;
    }

    // Native surround only if artifact already exists and is ready.
    final bool nativeStarted =
        !_nativeBypassForCurrentTrack && await _tryStartNativeAc3(currentItem);

    if (nativeStarted) {
      _fireAndForgetSurroundGenerationForNextTrack();
      return;
    }

    // Fallback immediately to normal playback (no waiting, no restart).
    await _stopNativeAc3(updateState: false);
    await _syncJustAudioToStoredPositionIfNeeded();
    await _player.setVolume(1.0);
    await _player.play();
    _broadcastState(_player.playbackEvent);

    // Generate current + next surround artifacts in background.
    _fireAndForgetSurroundGeneration(currentItem);
    _fireAndForgetSurroundGenerationForNextTrack();
  }

  @override
  Future<void> playFromMediaId(
    String mediaId, [
    Map<String, dynamic>? extras,
  ]) async {
    if (mediaId.startsWith(AndroidAuto.prefix)) {
      await _androidAuto.playItem(mediaId);
      return;
    }

    final index = queue.value.indexWhere((item) => item.id == mediaId);
    if (index != -1) {
      await _stopNativeAc3(updateState: false);
      _nativeBypassForCurrentTrack = false;
      _nativeBasePosition = Duration.zero;
      await _player.seek(
        Duration.zero,
        index:
            _player.shuffleModeEnabled ? _player.shuffleIndices![index] : index,
      );
      if (_desiredPlaying) {
        await Future.delayed(const Duration(milliseconds: 120));
        await _playCurrentAccordingToMode();
      }
    } else {
      Logger.root.severe('playFromMediaId: MediaItem not found');
    }
  }

  @override
  Future<void> pause() async {
    _desiredPlaying = false;

    if (_nativeAc3Active) {
      // Capture current native progress and switch shell player to same spot
      // so resume doesn't restart from zero or become silent.
      final Duration pos = _nativeEstimatedPosition;
      _nativeBasePosition = pos;
      _nativeBypassForCurrentTrack = true;

      await _stopNativeAc3(updateState: false);

      try {
        await _player.setVolume(1.0);
        await _player.seek(pos);
      } catch (e, st) {
        Logger.root.warning(
          'Failed to sync player position after native pause',
          e,
          st,
        );
      }

      await _player.pause();
      _broadcastState(_player.playbackEvent);
      return;
    }

    await _player.pause();
  }

  @override
  Future<void> stop() async {
    _desiredPlaying = false;
    Logger.root.info('saving queue');
    await _saveQueueToFile();
    await _stopNativeAc3(updateState: false);
    _nativeBasePosition = Duration.zero;
    Logger.root.info('stopping player');
    await _player.setVolume(1.0);
    await _player.stop();
    await super.stop();
  }

  @override
  Future<void> addQueueItem(MediaItem mediaItem) async {
    final res = await _itemToSource(mediaItem);
    if (res != null) {
      await _playlist.add(res);
    }
  }

  @override
  Future<void> addQueueItems(List<MediaItem> mediaItems) async {
    await _playlist.addAll(await _itemsToSources(mediaItems));
  }

  @override
  Future<void> insertQueueItem(int index, MediaItem mediaItem) async {
    if (index == -1) index = currentIndex + 1;
    final res = await _itemToSource(mediaItem);
    if (res != null) {
      await _playlist.insert(index, res);
    }
  }

  @override
  Future<void> updateQueue(List<MediaItem> queueItems) async {
    await _playlist.clear();
    if (queueItems.isNotEmpty) {
      await _playlist.addAll(await _itemsToSources(queueItems));
    } else {
      if (mediaItem.hasValue) {
        mediaItem.add(null);
      }
    }
  }

  Future<void> clearQueue() async {
    await updateQueue([]);
    await removeSavedQueueFile();
  }

  @override
  Future<void> removeQueueItem(MediaItem mediaItem) async {
    final queueItems = queue.value;
    final index = queueItems.indexOf(mediaItem);

    if (_player.shuffleModeEnabled) {
      final shuffledIndex = _player.shuffleIndices!.indexOf(index);
      await _playlist.removeAt(shuffledIndex);
    } else {
      await _playlist.removeAt(index);
    }
  }

  @override
  Future<void> removeQueueItemAt(int index) async {
    await _playlist.removeAt(index);
  }

  Future<void> moveQueueItem(int currentIndex, int newIndex) async {
    _rearranging = true;
    await _playlist.move(currentIndex, newIndex);
    _rearranging = false;
    playbackState.add(playbackState.value.copyWith());
  }

  @override
  Future<void> skipToNext() async {
    final wasPlaying = _desiredPlaying;
    await _stopNativeAc3(updateState: false);
    _nativeBypassForCurrentTrack = false;
    _nativeBasePosition = Duration.zero;
    await _player.seekToNext();
    if (wasPlaying) {
      await Future.delayed(const Duration(milliseconds: 120));
      await _playCurrentAccordingToMode();
    } else {
      _broadcastState(_player.playbackEvent);
    }
  }

  @override
  Future<void> skipToPrevious() async {
    final wasPlaying = _desiredPlaying;
    await _stopNativeAc3(updateState: false);
    _nativeBypassForCurrentTrack = false;
    _nativeBasePosition = Duration.zero;

    if ((_player.position.inSeconds) <= 5) {
      await _player.seekToPrevious();
    } else {
      await _player.seek(Duration.zero);
    }

    if (wasPlaying) {
      await Future.delayed(const Duration(milliseconds: 120));
      await _playCurrentAccordingToMode();
    } else {
      _broadcastState(_player.playbackEvent);
    }
  }

  @override
  Future<void> skipToQueueItem(int index) async {
    if (index < 0 || index >= _playlist.children.length) return;

    final wasPlaying = _desiredPlaying;
    await _stopNativeAc3(updateState: false);
    _nativeBypassForCurrentTrack = false;
    _nativeBasePosition = Duration.zero;

    await _player.seek(
      Duration.zero,
      index:
          _player.shuffleModeEnabled ? _player.shuffleIndices![index] : index,
    );

    if (wasPlaying) {
      await Future.delayed(const Duration(milliseconds: 120));
      await _playCurrentAccordingToMode();
    } else {
      _broadcastState(_player.playbackEvent);
    }
  }

  @override
  Future<void> seek(Duration position) async {
    if (_nativeAc3Active || settings.playbackMode == PlaybackMode.surround) {
      // Native raw AC3 path does not support practical accurate seeking here.
      // So current track falls back to just_audio from the new position.
      _nativeBypassForCurrentTrack = true;
      _nativeBasePosition = position;
      await _stopNativeAc3(updateState: false);
      await _player.setVolume(1.0);
      await _player.seek(position);
      _broadcastState(_player.playbackEvent);
      return;
    }

    await _player.seek(position);
  }

  @override
  Future<void> setRepeatMode(AudioServiceRepeatMode repeatMode) async {
    playbackState.add(playbackState.value.copyWith(repeatMode: repeatMode));
    await _player.setLoopMode(LoopMode.values[repeatMode.index]);
  }

  @override
  Future<void> setShuffleMode(AudioServiceShuffleMode shuffleMode) async {
    final enabled = shuffleMode == AudioServiceShuffleMode.all;
    _rearranging = true;
    await _player.setShuffleModeEnabled(enabled);
    _rearranging = false;

    if (enabled) {
      await _player.shuffle();
    }

    playbackState.add(playbackState.value.copyWith(shuffleMode: shuffleMode));
  }

  @override
  Future<void> onTaskRemoved() async {
    await dispose();
  }

  @override
  Future<void> onNotificationDeleted() async {
    await dispose();
  }

  @override
  Future<List<MediaItem>> getChildren(
    String parentMediaId, [
    Map<String, dynamic>? options,
  ]) async {
    return _androidAuto.getScreen(parentMediaId);
  }

  //----------------------------------------------
  // Internal methods
  //----------------------------------------------

  Future<void> _waitForPlayerReadiness() async {
    if (_player.processingState == ProcessingState.ready) {
      return;
    }

    final Completer<void> readyCompleter = Completer<void>();

    late StreamSubscription subscription;
    subscription = _player.processingStateStream.listen((state) {
      if (state == ProcessingState.ready) {
        if (!readyCompleter.isCompleted) {
          readyCompleter.complete();
        }
        subscription.cancel();
      }
    });

    return readyCompleter.future.timeout(
      const Duration(seconds: 10),
      onTimeout: () {
        subscription.cancel();
        Logger.root.warning('Timed out waiting for player to be ready');
      },
    );
  }

  Future<void> _startSession() async {
    Logger.root.info('starting audio service...');
    final session = await AudioSession.instance;
    await session.configure(const AudioSessionConfiguration.music());

    if (settings.ignoreInterruptions == true) {
      _player = AudioPlayer(handleInterruptions: false);
      session.interruptionEventStream.listen((_) {});
      session.becomingNoisyEventStream.listen((_) {});
    } else {
      _player = AudioPlayer();
    }

    _loadEmptyPlaylist()
        .then((_) => Logger.root.info('audio player initialized!'));
  }

  /// Broadcast the current state to all clients.
  void _broadcastState(PlaybackEvent event) {
    if (_nativeAc3Active) {
      _broadcastNativeState(isPlaying: true);
      return;
    }

    final playing = _player.playing;
    currentIndex = _getQueueIndex(
      _player.currentIndex ?? 0,
      shuffleModeEnabled: _player.shuffleModeEnabled,
    );

    playbackState.add(
      playbackState.value.copyWith(
        controls: [
          MediaControl.skipToPrevious,
          if (playing) MediaControl.pause else MediaControl.play,
          MediaControl.skipToNext,
          const MediaControl(
            androidIcon: 'drawable/ic_action_stop',
            label: 'stop',
            action: MediaAction.stop,
          ),
        ],
        systemActions: const {
          MediaAction.seek,
          MediaAction.seekForward,
          MediaAction.seekBackward,
        },
        androidCompactActionIndices: const [0, 1, 2],
        processingState: const {
          ProcessingState.idle: AudioProcessingState.idle,
          ProcessingState.loading: AudioProcessingState.loading,
          ProcessingState.buffering: AudioProcessingState.buffering,
          ProcessingState.ready: AudioProcessingState.ready,
          ProcessingState.completed: AudioProcessingState.completed,
        }[_player.processingState]!,
        playing: playing,
        updatePosition: _player.position,
        bufferedPosition: _player.bufferedPosition,
        speed: _player.speed,
        queueIndex: currentIndex,
      ),
    );
  }

  void _broadcastNativeState({required bool isPlaying}) {
    currentIndex = _getQueueIndex(
      _player.currentIndex ?? 0,
      shuffleModeEnabled: _player.shuffleModeEnabled,
    );

    playbackState.add(
      playbackState.value.copyWith(
        controls: [
          MediaControl.skipToPrevious,
          if (isPlaying) MediaControl.pause else MediaControl.play,
          MediaControl.skipToNext,
          const MediaControl(
            androidIcon: 'drawable/ic_action_stop',
            label: 'stop',
            action: MediaAction.stop,
          ),
        ],
        systemActions: const {
          MediaAction.seek,
          MediaAction.seekForward,
          MediaAction.seekBackward,
        },
        androidCompactActionIndices: const [0, 1, 2],
        processingState: AudioProcessingState.ready,
        playing: isPlaying,
        updatePosition: _nativeEstimatedPosition,
        bufferedPosition: _nativeEstimatedPosition,
        speed: 1.0,
        queueIndex: currentIndex,
      ),
    );
  }

  void _startNativeProgressTicker() {
    _nativeProgressTimer?.cancel();
    _nativeProgressTimer =
        Timer.periodic(const Duration(milliseconds: 500), (_) async {
      if (!_nativeAc3Active) return;

      _broadcastNativeState(isPlaying: true);

      final item = mediaItem.value;
      final duration = item?.duration;
      if (duration != null && _nativeEstimatedPosition >= duration) {
        Logger.root.info('Native AC3 playback reached track duration');
        await _advanceAfterNativeCompletion(duration);
      }
    });
  }

  Future<bool> _tryStartNativeAc3(MediaItem currentItem) async {
    if (settings.playbackMode != PlaybackMode.surround) {
      return false;
    }

    if (_nativeBypassForCurrentTrack) {
      Logger.root.info(
        'Native AC3 bypass enabled for current track ${currentItem.id}, using normal playback.',
      );
      return false;
    }

    // If resuming from a mid-track position, use just_audio fallback.
    if (_nativeBasePosition > Duration.zero) {
      Logger.root.info(
        'Stored native position exists for ${currentItem.id} (${_nativeBasePosition.inMilliseconds} ms), using normal playback fallback.',
      );
      return false;
    }

    bool directSupported = false;
    try {
      directSupported = await _nativeChannel.invokeMethod<bool>(
            'isDirectAc3PlaybackSupported',
            <String, dynamic>{'sampleRateHz': 48000},
          ) ??
          false;
    } catch (e, st) {
      Logger.root.warning(
        'Direct AC3 playback support check failed',
        e,
        st,
      );
      directSupported = false;
    }

    if (!directSupported) {
      Logger.root.info(
        'Direct AC3 playback is not supported for current route/device.',
      );
      return false;
    }

    // IMPORTANT:
    // Do not block playback by generating here.
    // Only use native if artifact already exists.
    String? ac3Path = await _getSurroundArtifactPath(
      currentItem,
      extension: 'ac3',
    );

    if (ac3Path == null) {
      _fireAndForgetSurroundGeneration(currentItem);
      Logger.root.info(
        'No ready AC3 artifact for ${currentItem.id}, staying on normal playback for now.',
      );
      return false;
    }

    final ac3File = File(ac3Path);
    if (!await ac3File.exists() || await ac3File.length() <= 0) {
      _fireAndForgetSurroundGeneration(currentItem);
      Logger.root.warning(
        'AC3 artifact missing/empty for ${currentItem.id}: $ac3Path',
      );
      return false;
    }

    final bool wasPlaying = _player.playing;
    final double previousVolume = _player.volume;

    try {
      if (wasPlaying) {
        await _player.pause();
      }

      final bool started = await _nativeChannel.invokeMethod<bool>(
            'playNativeAc3',
            <String, dynamic>{
              'path': ac3Path,
              'sampleRateHz': 48000,
            },
          ) ??
          false;

      if (!started) {
        await _player.setVolume(previousVolume);
        return false;
      }

      await _player.setVolume(0.0);

      _nativeAc3Active = true;
      _nativeTrackId = currentItem.id;
      _nativeStartedAt = DateTime.now();
      _nativeBasePosition = Duration.zero;

      _startNativeProgressTicker();
      _broadcastNativeState(isPlaying: true);

      Logger.root.info(
        'Native AC3 playback started for ${currentItem.id}: $ac3Path',
      );

      _fireAndForgetSurroundGenerationForNextTrack();
      return true;
    } catch (e, st) {
      Logger.root.warning(
        'playNativeAc3 failed for ${currentItem.id}',
        e,
        st,
      );
      await _player.setVolume(previousVolume);
      await _stopNativeAc3(updateState: false);
      return false;
    }
  }

  Future<void> _stopNativeAc3({bool updateState = true}) async {
    _nativeProgressTimer?.cancel();
    _nativeProgressTimer = null;

    if (_nativeAc3Active) {
      try {
        await _nativeChannel.invokeMethod('stopNativeAc3');
      } catch (e, st) {
        Logger.root.warning('stopNativeAc3 failed', e, st);
      }
    }

    _nativeAc3Active = false;
    _nativeTrackId = null;
    _nativeStartedAt = null;

    if (updateState) {
      _broadcastState(_player.playbackEvent);
    }
  }

  Future<void> _syncJustAudioToStoredPositionIfNeeded() async {
    if (_nativeBasePosition <= Duration.zero) {
      return;
    }

    final Duration target = _nativeBasePosition;
    final Duration current = _player.position;
    final int deltaMs = (current - target).inMilliseconds.abs();

    if (deltaMs > 1500) {
      try {
        Logger.root.info(
          'Syncing just_audio position to stored native position: ${target.inMilliseconds} ms',
        );
        await _player.seek(target);
      } catch (e, st) {
        Logger.root.warning(
          'Failed to seek just_audio to stored native position',
          e,
          st,
        );
      }
    }

    // After syncing to normal player, clear stored native resume marker.
    _nativeBasePosition = Duration.zero;
  }

  void _fireAndForgetSurroundGeneration(MediaItem item) {
    if (settings.playbackMode != PlaybackMode.surround) return;
    if (_surroundGenerationInFlight.contains(item.id)) return;
    unawaited(_ensureSurroundArtifactInBackground(item));
  }

  void _fireAndForgetSurroundGenerationForNextTrack() {
    if (settings.playbackMode != PlaybackMode.surround) return;
    if (queue.value.isEmpty) return;

    final current = mediaItem.value;
    if (current == null) return;

    final int currentQueueIndex =
        queue.value.indexWhere((m) => m.id == current.id);
    if (currentQueueIndex == -1) return;

    int nextQueueIndex = currentQueueIndex + 1;

    if (nextQueueIndex >= queue.value.length) {
      if (_player.loopMode == LoopMode.all && queue.value.isNotEmpty) {
        nextQueueIndex = 0;
      } else {
        return;
      }
    }

    final nextItem = queue.value[nextQueueIndex];
    _fireAndForgetSurroundGeneration(nextItem);
  }

  Future<void> _ensureSurroundArtifactInBackground(MediaItem item) async {
    if (settings.playbackMode != PlaybackMode.surround) return;
    if (_surroundGenerationInFlight.contains(item.id)) return;

    _surroundGenerationInFlight.add(item.id);

    try {
      final existing = await _getSurroundArtifactPath(
        item,
        extension: 'ac3',
      );

      if (existing != null) {
        return;
      }

      final String? originalUrl = await _getTrackUrl(item);
      if (originalUrl == null || originalUrl.trim().isEmpty) {
        Logger.root.warning(
          'Cannot generate surround artifact: no track URL for ${item.id}',
        );
        return;
      }

      Logger.root.info(
        'Background generating surround artifact for ${item.id}',
      );

      await _generateSurroundNow(
        item,
        originalUrl,
        outputMode: 'ac3',
      );
    } catch (e, st) {
      Logger.root.warning(
        'Background surround generation failed for ${item.id}',
        e,
        st,
      );
    } finally {
      _surroundGenerationInFlight.remove(item.id);
    }
  }

  Future<void> _advanceAfterNativeCompletion(Duration duration) async {
    final int queueIndex = playbackState.value.queueIndex ?? currentIndex;
    final bool hasNext = queueIndex + 1 < queue.value.length;
    final bool repeatAll = _player.loopMode == LoopMode.all;
    final bool repeatOne = _player.loopMode == LoopMode.one;

    _nativeBasePosition = Duration.zero;
    await _stopNativeAc3(updateState: false);

    if (_desiredPlaying && repeatOne) {
      await skipToQueueItem(queueIndex);
      return;
    }

    if (_desiredPlaying && hasNext) {
      await skipToNext();
      return;
    }

    if (_desiredPlaying && !hasNext && repeatAll && queue.value.isNotEmpty) {
      await skipToQueueItem(0);
      return;
    }

    _desiredPlaying = false;
    playbackState.add(
      playbackState.value.copyWith(
        playing: false,
        processingState: AudioProcessingState.completed,
        updatePosition: duration,
        bufferedPosition: duration,
      ),
    );
  }

  /// Resolve effective queue index taking shuffle mode into account.
  int _getQueueIndex(int currentIndex, {bool shuffleModeEnabled = false}) {
    final effectiveIndices = _player.effectiveIndices ?? [];
    final shuffleIndicesInv = List.filled(effectiveIndices.length, 0);

    for (var i = 0; i < effectiveIndices.length; i++) {
      shuffleIndicesInv[effectiveIndices[i]] = i;
    }

    return (shuffleModeEnabled && (currentIndex < shuffleIndicesInv.length))
        ? shuffleIndicesInv[currentIndex]
        : currentIndex;
  }

  Future<void> _loadEmptyPlaylist() async {
    try {
      Logger.root.info('Loading empty playlist...');
      await _player.setAudioSource(_playlist);
    } catch (e) {
      Logger.root.severe('Error loading empty playlist: $e');
    }
  }

  Future<List<AudioSource>> _itemsToSources(
    List<MediaItem> mediaItems, {
    bool forceOriginal = false,
  }) async {
    final sources = await Future.wait(
      mediaItems.map((mi) => _itemToSource(mi, forceOriginal: forceOriginal)),
    );
    return sources.whereType<AudioSource>().toList();
  }

  Future<AudioSource?> _itemToSource(
    MediaItem mi, {
    bool forceOriginal = false,
  }) async {
    final Uri? resolvedUri =
        await _resolvePlayableUri(mi, forceOriginal: forceOriginal);
    if (resolvedUri == null) return null;

    return AudioSource.uri(resolvedUri, tag: mi);
  }

  Future<Uri?> _resolvePlayableUri(
    MediaItem mediaItem, {
    bool forceOriginal = false,
  }) async {
    final String? originalUrl = await _getTrackUrl(mediaItem);
    if (originalUrl == null) return null;

    final Uri originalUri = Uri.parse(originalUrl);

    if (forceOriginal) {
      Logger.root.info(
        'Using ORIGINAL source for ${mediaItem.id}: $originalUri',
      );
      return originalUri;
    }

    // just_audio remains queue/session shell.
    // Even in surround mode we keep original source loaded here and route
    // actual sound through native AC3 playback when available.
    final String? ac3Path = await _getSurroundArtifactPath(
      mediaItem,
      extension: 'ac3',
    );

    Logger.root.info(
      'Resolved source for ${mediaItem.id} => $originalUri '
      '(mode=${settings.playbackMode.name}, surroundAc3Path=$ac3Path, nativeShell=true)',
    );

    return originalUri;
  }

  Future<String?> _getSurroundArtifactPath(
    MediaItem mediaItem, {
    required String extension,
  }) async {
    final String ext = extension.toLowerCase().trim();

    // 1) Direct path from MediaItem extras (best option)
    final List<String> extraKeys = ext == 'ac3'
        ? const ['surroundAc3Path', 'surroundTsPath', 'surroundPath']
        : const ['surroundTsPath', 'surroundAc3Path', 'surroundPath'];

    for (final key in extraKeys) {
      final dynamic extraPath = mediaItem.extras?[key];
      if (extraPath is String &&
          extraPath.trim().isNotEmpty &&
          extraPath.toLowerCase().endsWith('.$ext')) {
        final file = File(extraPath);
        if (await file.exists() && await file.length() > 0) {
          return file.path;
        }
      }
    }

    // 2) tempDir/surround/<trackId>.<ext>
    try {
      final tempDir = await getTemporaryDirectory();
      final tempPath = p.join(tempDir.path, 'surround', '${mediaItem.id}.$ext');
      final tempFile = File(tempPath);
      if (await tempFile.exists() && await tempFile.length() > 0) {
        return tempFile.path;
      }
    } catch (_) {}

    // 3) docsDir/surround/<trackId>.<ext>
    try {
      final docsDir = await getApplicationDocumentsDirectory();
      final docsPath = p.join(docsDir.path, 'surround', '${mediaItem.id}.$ext');
      final docsFile = File(docsPath);
      if (await docsFile.exists() && await docsFile.length() > 0) {
        return docsFile.path;
      }
    } catch (_) {}

    // 4) external app dir/surround/<trackId>.<ext>
    try {
      final extDir = await getExternalStorageDirectory();
      if (extDir != null) {
        final extPath = p.join(extDir.path, 'surround', '${mediaItem.id}.$ext');
        final extFile = File(extPath);
        if (await extFile.exists() && await extFile.length() > 0) {
          return extFile.path;
        }
      }
    } catch (_) {}

    // 5) ask native side too
    try {
      final String? nativeFound =
          await _nativeChannel.invokeMethod<String>('findExistingSurroundPath', {
        'trackId': mediaItem.id,
        'extension': ext,
      });

      if (nativeFound != null && nativeFound.trim().isNotEmpty) {
        final file = File(nativeFound);
        if (await file.exists() && await file.length() > 0) {
          return file.path;
        }
      }
    } catch (e, st) {
      Logger.root.warning(
        'Native surround lookup failed for ${mediaItem.id}.$ext',
        e,
        st,
      );
    }

    return null;
  }

  Future<bool> _generateSurroundNow(
    MediaItem mediaItem,
    String inputPath, {
    String outputMode = 'ac3',
  }) async {
    try {
      Logger.root.info(
        'No surround artifact found for ${mediaItem.id}, generating now from input: $inputPath (outputMode=$outputMode)',
      );

      final dynamic raw = await _nativeChannel.invokeMethod(
        'generateSurroundNow',
        <String, dynamic>{
          'trackId': mediaItem.id,
          'inputPath': inputPath,
          'outputMode': outputMode,
          'preset': settings.surroundPreset,
          'overwrite': true,
          'persistent': true,
          'debugPassthrough': false,
          'bitrateKbps': 448,
          'sampleRateHz': 48000,
          'outputChannels': 6,
        },
      );

      final Map<dynamic, dynamic>? result =
          raw is Map ? raw as Map<dynamic, dynamic> : null;

      Logger.root.info(
        'generateSurroundNow result for ${mediaItem.id}: $result',
      );

      final bool success = result?['success'] == true;
      if (!success) {
        return false;
      }

      final String? outputPath = result?['outputPath']?.toString();
      if (outputPath == null || outputPath.trim().isEmpty) {
        return false;
      }

      final file = File(outputPath);
      return await file.exists() && await file.length() > 0;
    } catch (e, st) {
      Logger.root.warning(
        'generateSurroundNow failed for ${mediaItem.id}',
        e,
        st,
      );
      return false;
    }
  }

  Future<String?> _getTrackUrl(MediaItem mediaItem) async {
    // Check if offline
    final String offlinePath =
        p.join((await getExternalStorageDirectory())!.path, 'offline/');
    final File f = File(p.join(offlinePath, mediaItem.id));
    if (await f.exists()) {
      return 'http://localhost:36958/?id=${mediaItem.id}';
    }

    // Show episode direct link
    if (mediaItem.extras?['showUrl'] != null) {
      return mediaItem.extras?['showUrl'];
    }

    int quality = await getStreamQuality();

    List? streamPlaybackDetails =
        jsonDecode(mediaItem.extras?['playbackDetails']);
    String streamItemId = mediaItem.id;

    if (mediaItem.extras?['fallbackId'] != null) {
      streamItemId = mediaItem.extras?['fallbackId'];
      streamPlaybackDetails =
          jsonDecode(mediaItem.extras?['playbackDetailsFallback']);
    }

    if ((streamPlaybackDetails ?? []).length < 3) return null;

    final String url =
        'http://localhost:36958/?q=$quality&id=${mediaItem.id}&streamTrackId=$streamItemId&trackToken=${streamPlaybackDetails?[2]}&mv=${streamPlaybackDetails?[1]}&md5origin=${streamPlaybackDetails?[0]}';

    return url;
  }

  /// Get requested stream quality based on connection and settings.
  Future<int> getStreamQuality() async {
    int quality = settings.getQualityInt(settings.mobileQuality);
    final List<ConnectivityResult> conn =
        await Connectivity().checkConnectivity();
    if (conn.contains(ConnectivityResult.wifi)) {
      quality = settings.getQualityInt(settings.wifiQuality);
    }
    return quality;
  }

  /// Load new queue and seek to given index & position
  Future<void> _loadQueueAtIndex(
    List<MediaItem> newQueue,
    int index, {
    Duration position = Duration.zero,
  }) async {
    if (_queueLoadInProgress) {
      Logger.root.warning(
        'Queue load already in progress, skipping duplicate request.',
      );
      return;
    }

    _queueLoadInProgress = true;
    _requestedIndex = index;

    try {
      await _playlist.clear();

      try {
        await _playlist.addAll(await _itemsToSources(newQueue));
      } catch (e, st) {
        Logger.root.warning(
          'Error building queue with current playback mode, falling back to original sources.',
          e,
          st,
        );

        await _playlist.clear();
        await _playlist.addAll(
          await _itemsToSources(newQueue, forceOriginal: true),
        );
      }

      // DO NOT manually call queue.add(newQueue) here.
      // queue is already driven by just_audio sequenceStateStream.

      await _waitForPlayerReadiness();

      try {
        await _player.seek(position, index: index);
        _nativeBasePosition = Duration.zero;
        await _player.setVolume(1.0);
      } catch (e, st) {
        Logger.root.severe('Error loading tracks', e, st);
      }
    } finally {
      _requestedIndex = -1;
      _queueLoadInProgress = false;
    }
  }

  /// Replace queue and play specified item index
  Future<void> _loadQueueAndPlayAtIndex(
    QueueSource newQueueSource,
    List<MediaItem> newQueue,
    int index,
  ) async {
    await pause();
    _requestedIndex = index;

    queueSource = newQueueSource;

    await _loadQueueAtIndex(newQueue, index, position: Duration.zero);
    await setShuffleMode(AudioServiceShuffleMode.none);
    await play();

    _requestedIndex = -1;
  }

  /// Call this after changing settings.playbackMode
  Future<void> reloadQueueForPlaybackModeChange() async {
    if (queue.value.isEmpty) return;

    final bool wasPlaying = _desiredPlaying;
    final Duration currentPosition =
        _nativeAc3Active ? _nativeEstimatedPosition : _player.position;
    final int queueIndex = playbackState.value.queueIndex ?? currentIndex;

    Logger.root.info(
      'Reloading queue for playback mode change -> ${settings.playbackMode.name}',
    );

    await pause();
    _nativeBypassForCurrentTrack = false;

    await _loadQueueAtIndex(
      queue.value,
      queueIndex,
      position: currentPosition,
    );

    if (wasPlaying) {
      await play();
    }
  }

  /// Attempt to load more tracks when queue ends
  Future<void> _onQueueEnd() async {
    if (queueSource == null) return;

    List<Track> tracks = [];
    switch (queueSource!.source) {
      case 'flow':
        tracks = await deezerAPI.flow();
        break;
      case 'smartradio':
        tracks = await deezerAPI.smartRadio(queueSource!.id ?? '');
        break;
      case 'libraryshuffle':
        tracks = await deezerAPI.libraryShuffle(start: queue.value.length);
        break;
      case 'mix':
        tracks = await deezerAPI.playMix(queueSource!.id ?? '');
        break;
      case 'playlist':
        final int pos = queue.value.length;
        tracks =
            await deezerAPI.playlistTracksPage(queueSource!.id!, pos, nb: 25);
        break;
      default:
        Logger.root.info('Reached end of queue source: ${queueSource!.source}');
        break;
    }

    final List<String> queueIds = queue.value.map((mi) => mi.id).toList();
    tracks.removeWhere((track) => queueIds.contains(track.id));

    final List<MediaItem> extraTracks =
        tracks.map<MediaItem>((t) => t.toMediaItem()).toList();

    await addQueueItems(extraTracks);
  }

  void _playbackError(err) {
    Logger.root.severe('Playback Error from audioservice: ${err.code}', err);
    if (err is PlatformException &&
        err.code == 'abort' &&
        err.message == 'Connection aborted') {
      return;
    }
    _onError(err, null);
  }

  void _onError(err, stacktrace, {bool stopService = false}) {
    Logger.root.severe('Error from audioservice: ${err.code}', err);
    if (stopService) stop();
  }

  Future<void> _addToHistory(MediaItem item) async {
    if (!playbackState.value.playing) return;

    if (_scrobblenautReady && !(_loggedTrackId == item.id)) {
      Logger.root.info('scrobbling track ${item.id} to recently LastFM');
      _loggedTrackId = item.id;
      await _scrobblenaut?.track.scrobble(
        track: item.title,
        artist: item.artist ?? '',
        album: item.album,
      );
    }

    if (cache.history.isNotEmpty && cache.history.last.id == item.id) return;

    Logger.root.info('adding track ${item.id} to recently played history');
    cache.history.add(Track.fromMediaItem(item));
    await cache.save();
  }

  Future<String> _getQueueFilePath() async {
    final Directory dir = await getApplicationDocumentsDirectory();
    return p.join(dir.path, 'playback.json');
  }

  Future<void> _saveQueueToFile() async {
    if (_player.currentIndex == 0 && queue.value.isEmpty) return;

    final String path = await _getQueueFilePath();
    File f = File(path);

    if (!await File(path).exists()) {
      f = await f.create();
    }

    final Map data = {
      'index': _player.currentIndex,
      'queue': queue.value
          .map<Map<String, dynamic>>(
            (mi) => MediaItemConverter.mediaItemToMap(mi),
          )
          .toList(),
      'position': (_nativeAc3Active ? _nativeEstimatedPosition : _player.position)
          .inMilliseconds,
      'queueSource': (queueSource ?? QueueSource()).toJson(),
      'loopMode': LoopMode.values.indexOf(_player.loopMode),
    };

    await f.writeAsString(jsonEncode(data));
  }

  //----------------------------------------------------------------------------------------------
  // Public app-specific methods
  //----------------------------------------------------------------------------------------------

  Future<void> waitForPlayerInitialization() async {
    await _playerInitializedCompleter.future;
  }

  Future<void> dispose() async {
    await _queueStateSub?.cancel();
    await _mediaItemSub?.cancel();
    await stop();
    await _player.dispose();
  }

  Future<void> loadQueueFromFile() async {
    Logger.root.info('looking for saved queue file...');
    try {
      final File f = File(await _getQueueFilePath());
      if (await f.exists()) {
        Logger.root.info('saved queue file found, loading...');

        try {
          final String fileContent = await f.readAsString();
          if (fileContent.isEmpty) {
            Logger.root.warning('saved queue file is empty');
            return;
          }

          final Map<String, dynamic> json = jsonDecode(fileContent);
          final List<MediaItem> savedQueue = (json['queue'] ?? [])
              .map<MediaItem>((mi) => MediaItemConverter.mediaItemFromMap(mi))
              .toList();

          final int lastIndex = json['index'] ?? 0;
          final Duration lastPos =
              Duration(milliseconds: json['position'] ?? 0);
          queueSource = QueueSource.fromJson(json['queueSource'] ?? {});
          final repeatType = LoopMode.values[(json['loopMode'] ?? 0)];

          await _player.setLoopMode(repeatType);
          await _loadQueueAtIndex(savedQueue, lastIndex, position: lastPos);

          Logger.root.info('saved queue loaded from file!');
        } catch (e) {
          Logger.root.severe('Error parsing queue file: $e');
          await f.delete();
          await _loadEmptyPlaylist();
        }
      }
    } catch (e, st) {
      Logger.root.severe('Error loading queue from file', e, st);
      await _loadEmptyPlaylist();
    }
  }

  Future<void> removeSavedQueueFile() async {
    final String path = await _getQueueFilePath();
    final File f = File(path);
    if (await f.exists()) {
      await f.delete();
      Logger.root.info('saved queue file removed!');
    }
  }

  Future<void> authorizeLastFM() async {
    if (settings.lastFMPassword == null) return;

    final String username = settings.lastFMUsername ?? '';
    final String password = settings.lastFMPassword ?? '';

    try {
      final LastFM lastFM = await LastFM.authenticateWithPasswordHash(
        apiKey: Env.lastFmApiKey,
        apiSecret: Env.lastFmApiSecret,
        username: username,
        passwordHash: password,
      );
      _scrobblenaut = Scrobblenaut(lastFM: lastFM);
      _scrobblenautReady = true;
    } catch (e) {
      Logger.root.severe('Error authorizing LastFM: $e');
      Fluttertoast.showToast(msg: 'Authorization error!'.i18n);
    }
  }

  Future<void> disableLastFM() async {
    _scrobblenaut = null;
    _scrobblenautReady = false;
  }

  Future<void> toggleShuffle() async {
    await setShuffleMode(
      _player.shuffleModeEnabled
          ? AudioServiceShuffleMode.none
          : AudioServiceShuffleMode.all,
    );
  }

  LoopMode getLoopMode() {
    return _player.loopMode;
  }

  Future<void> changeRepeat() async {
    switch (_player.loopMode) {
      case LoopMode.one:
        await setRepeatMode(AudioServiceRepeatMode.none);
        break;
      case LoopMode.all:
        await setRepeatMode(AudioServiceRepeatMode.one);
        break;
      default:
        await setRepeatMode(AudioServiceRepeatMode.all);
        break;
    }
  }

  Future<void> updateQueueQuality() async {
    if (_desiredPlaying) {
      await pause();
      await _loadQueueAtIndex(
        queue.value,
        playbackState.value.queueIndex ?? 0,
        position: _player.position,
      );
      await play();
    } else {
      await _loadQueueAtIndex(
        queue.value,
        playbackState.value.queueIndex ?? 0,
        position: _player.position,
      );
    }
  }

  Future<void> playFromAlbum(Album album, String trackId) async {
    await playFromTrackList(
      album.tracks ?? [],
      trackId,
      QueueSource(id: album.id, text: album.title, source: 'album'),
    );
  }

  Future<void> playMix(String trackId, String trackTitle) async {
    final List<Track> tracks = await deezerAPI.playMix(trackId);
    await playFromTrackList(
      tracks,
      tracks[0].id ?? '',
      QueueSource(
        id: trackId,
        text: 'Mix based on'.i18n + ' $trackTitle',
        source: 'mix',
      ),
    );
  }

  Future<void> playFromTopTracks(
    List<Track> tracks,
    String trackId,
    Artist artist,
  ) async {
    await playFromTrackList(
      tracks,
      trackId,
      QueueSource(
        id: artist.id,
        text: 'Top ${artist.name}',
        source: 'topTracks',
      ),
    );
  }

  Future<void> playFromPlaylist(Playlist playlist, String trackId) async {
    await playFromTrackList(
      playlist.tracks ?? [],
      trackId,
      QueueSource(id: playlist.id, text: playlist.title, source: 'playlist'),
    );
  }

  Future<void> playShowEpisode(
    Show show,
    List<ShowEpisode> episodes, {
    int index = 0,
  }) async {
    final QueueSource showQueueSource =
        QueueSource(id: show.id, text: show.name, source: 'show');

    final List<MediaItem> episodeQueue =
        episodes.map<MediaItem>((e) => e.toMediaItem(show)).toList();

    await _loadQueueAndPlayAtIndex(showQueueSource, episodeQueue, index);
  }

  Future<void> playFromTrackList(
    List<Track> tracks,
    String trackId,
    QueueSource trackQueueSource,
  ) async {
    final List<MediaItem> trackQueue =
        tracks.map<MediaItem>((track) => track.toMediaItem()).toList();

    await _loadQueueAndPlayAtIndex(
      trackQueueSource,
      trackQueue,
      trackQueue.indexWhere((m) => m.id == trackId),
    );
  }

  Future<void> playFromSmartTrackList(SmartTrackList stl) async {
    if ((stl.tracks?.length ?? 0) == 0) {
      if (settings.offlineMode) {
        Fluttertoast.showToast(
          msg: "Offline mode, can't play flow or smart track lists.".i18n,
          gravity: ToastGravity.BOTTOM,
          toastLength: Toast.LENGTH_SHORT,
        );
        return;
      }

      if (stl.id == 'flow') {
        stl.tracks = await deezerAPI.flow(type: stl.flowType);
      } else {
        stl = await deezerAPI.smartTrackList(stl.id ?? '');
      }
    }

    final QueueSource queueSrc = QueueSource(
      id: stl.id,
      source: (stl.id == 'flow') ? 'flow' : 'smarttracklist',
      text: stl.title ??
          ((stl.id == 'flow') ? 'Flow'.i18n : 'Smart track list'.i18n),
    );

    await playFromTrackList(
      stl.tracks ?? [],
      stl.tracks?[0].id ?? '',
      queueSrc,
    );
  }

  Future<void> startVisualizer() async {
    /* Needs experimental 'visualizer' branch of just_audio
      _player.startVisualizer(enableWaveform: false, enableFft: true, captureRate: 15000, captureSize: 128);
      _visualizerSubscription = _player.visualizerFftStream.listen((event) {
        List<double> out = [];
        for (int i = 0; i < event.length / 2; i++) {
          int rfk = event[i * 2].toSigned(8);
          int ifk = event[i * 2 + 1].toSigned(8);
          out.add(log(hypot(rfk, ifk) + 1) / 5.2);
        }
        _visualizerController.add(out);
      });
    */
  }

  Future<void> stopVisualizer() async {
    if (_visualizerSubscription != null) {
      await _visualizerSubscription!.cancel();
      _visualizerSubscription = null;
    }
  }
}

class QueueState {
  static const QueueState empty = QueueState(
    [],
    0,
    [],
    AudioServiceRepeatMode.none,
    AudioServiceShuffleMode.none,
  );

  final List<MediaItem> queue;
  final int? queueIndex;
  final List<int>? shuffleIndices;
  final AudioServiceRepeatMode repeatMode;
  final AudioServiceShuffleMode shuffleMode;

  const QueueState(
    this.queue,
    this.queueIndex,
    this.shuffleIndices,
    this.repeatMode,
    this.shuffleMode,
  );

  bool get hasPrevious =>
      repeatMode != AudioServiceRepeatMode.none || (queueIndex ?? 0) > 0;

  bool get hasNext =>
      repeatMode != AudioServiceRepeatMode.none ||
      (queueIndex ?? 0) + 1 < queue.length;

  List<int> get indices =>
      shuffleIndices ?? List.generate(queue.length, (i) => i);
}
