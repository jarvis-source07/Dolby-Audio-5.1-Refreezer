// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'env.dart';

// **************************************************************************
// EnviedGenerator
// **************************************************************************

// coverage:ignore-file
// ignore_for_file: type=lint
// generated_from: .env
final class _Env {
  static const List<int> _enviedkeydeezerClientId = <int>[
    1039223639,
    1618068024,
    1175455998,
    3136425130,
    463853431,
    3746927844,
  ];

  static const List<int> _envieddatadeezerClientId = <int>[
    1039223654,
    1618067978,
    1175455949,
    3136425118,
    463853378,
    3746927826,
  ];

  static final String deezerClientId = String.fromCharCodes(List<int>.generate(
    _envieddatadeezerClientId.length,
    (int i) => i,
    growable: false,
  ).map((int i) => _envieddatadeezerClientId[i] ^ _enviedkeydeezerClientId[i]));

  static const List<int> _enviedkeydeezerClientSecret = <int>[
    2920493838,
    1147083351,
    413820956,
    291057055,
    1073212564,
    1484209395,
  ];

  static const List<int> _envieddatadeezerClientSecret = <int>[
    2920493935,
    1147083317,
    413821055,
    291057147,
    1073212657,
    1484209301,
  ];

  static final String deezerClientSecret = String.fromCharCodes(
      List<int>.generate(
    _envieddatadeezerClientSecret.length,
    (int i) => i,
    growable: false,
  ).map((int i) =>
          _envieddatadeezerClientSecret[i] ^ _enviedkeydeezerClientSecret[i]));

  static const List<int> _enviedkeylastFmApiKey = <int>[
    1726091862,
    1931811993,
    2652868866,
    3682321611,
    1113164879,
    347481805,
  ];

  static const List<int> _envieddatalastFmApiKey = <int>[
    1726091879,
    1931812011,
    2652868913,
    3682321663,
    1113164922,
    347481851,
  ];

  static final String lastFmApiKey = String.fromCharCodes(List<int>.generate(
    _envieddatalastFmApiKey.length,
    (int i) => i,
    growable: false,
  ).map((int i) => _envieddatalastFmApiKey[i] ^ _enviedkeylastFmApiKey[i]));

  static const List<int> _enviedkeylastFmApiSecret = <int>[
    2873502035,
    3144677133,
    1737784723,
    3050012157,
    3043041820,
    1348329290,
  ];

  static const List<int> _envieddatalastFmApiSecret = <int>[
    2873502002,
    3144677231,
    1737784816,
    3050012057,
    3043041913,
    1348329260,
  ];

  static final String lastFmApiSecret = String.fromCharCodes(List<int>.generate(
    _envieddatalastFmApiSecret.length,
    (int i) => i,
    growable: false,
  ).map(
      (int i) => _envieddatalastFmApiSecret[i] ^ _enviedkeylastFmApiSecret[i]));
}
