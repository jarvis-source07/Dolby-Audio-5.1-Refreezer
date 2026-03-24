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
    2627341749,
    183518288,
    2259470412,
    1748031784,
    2440435748,
    1402048447,
  ];

  static const List<int> _envieddatadeezerClientId = <int>[
    2627341700,
    183518306,
    2259470463,
    1748031772,
    2440435729,
    1402048393,
  ];

  static final String deezerClientId = String.fromCharCodes(List<int>.generate(
    _envieddatadeezerClientId.length,
    (int i) => i,
    growable: false,
  ).map((int i) => _envieddatadeezerClientId[i] ^ _enviedkeydeezerClientId[i]));

  static const List<int> _enviedkeydeezerClientSecret = <int>[
    3617508148,
    3454089546,
    1950971876,
    2463953131,
    1056552781,
    2668020581,
  ];

  static const List<int> _envieddatadeezerClientSecret = <int>[
    3617508181,
    3454089512,
    1950971783,
    2463953039,
    1056552744,
    2668020483,
  ];

  static final String deezerClientSecret = String.fromCharCodes(
      List<int>.generate(
    _envieddatadeezerClientSecret.length,
    (int i) => i,
    growable: false,
  ).map((int i) =>
          _envieddatadeezerClientSecret[i] ^ _enviedkeydeezerClientSecret[i]));

  static const List<int> _enviedkeylastFmApiKey = <int>[
    821757968,
    61716852,
    3909798531,
    1227504732,
    2135858314,
    2694233968,
  ];

  static const List<int> _envieddatalastFmApiKey = <int>[
    821757985,
    61716806,
    3909798576,
    1227504744,
    2135858367,
    2694233926,
  ];

  static final String lastFmApiKey = String.fromCharCodes(List<int>.generate(
    _envieddatalastFmApiKey.length,
    (int i) => i,
    growable: false,
  ).map((int i) => _envieddatalastFmApiKey[i] ^ _enviedkeylastFmApiKey[i]));

  static const List<int> _enviedkeylastFmApiSecret = <int>[
    1975942497,
    2517468511,
    1400112166,
    4213190767,
    524176404,
    3227126028,
  ];

  static const List<int> _envieddatalastFmApiSecret = <int>[
    1975942400,
    2517468477,
    1400112197,
    4213190667,
    524176497,
    3227126122,
  ];

  static final String lastFmApiSecret = String.fromCharCodes(List<int>.generate(
    _envieddatalastFmApiSecret.length,
    (int i) => i,
    growable: false,
  ).map(
      (int i) => _envieddatalastFmApiSecret[i] ^ _enviedkeylastFmApiSecret[i]));
}
