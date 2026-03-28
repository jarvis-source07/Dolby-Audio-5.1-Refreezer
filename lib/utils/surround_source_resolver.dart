import 'dart:io';

import '../settings.dart';

class SurroundSourceResolver {
  static Uri resolve({
    required Uri originalUri,
    required PlaybackMode playbackMode,
    String? surroundTsPath,
  }) {
    if (playbackMode == PlaybackMode.surround &&
        surroundTsPath != null &&
        surroundTsPath.trim().isNotEmpty &&
        File(surroundTsPath).existsSync()) {
      return Uri.file(surroundTsPath);
    }

    return originalUri;
  }
}
