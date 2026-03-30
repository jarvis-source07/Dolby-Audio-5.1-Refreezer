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
    196799294,
    3493599479,
    3323075833,
    1616175634,
    198548604,
    3232910942,
  ];

  static const List<int> _envieddatadeezerClientId = <int>[
    196799247,
    3493599429,
    3323075786,
    1616175654,
    198548553,
    3232910952,
  ];

  static final String deezerClientId = String.fromCharCodes(List<int>.generate(
    _envieddatadeezerClientId.length,
    (int i) => i,
    growable: false,
  ).map((int i) => _envieddatadeezerClientId[i] ^ _enviedkeydeezerClientId[i]));

  static const List<int> _enviedkeydeezerClientSecret = <int>[
    961353033,
    2663793893,
    2259171452,
    679608694,
    2213715014,
    3447877190,
  ];

  static const List<int> _envieddatadeezerClientSecret = <int>[
    961353000,
    2663793799,
    2259171359,
    679608594,
    2213714979,
    3447877152,
  ];

  static final String deezerClientSecret = String.fromCharCodes(
      List<int>.generate(
    _envieddatadeezerClientSecret.length,
    (int i) => i,
    growable: false,
  ).map((int i) =>
          _envieddatadeezerClientSecret[i] ^ _enviedkeydeezerClientSecret[i]));

  static const List<int> _enviedkeylastFmApiKey = <int>[
    1346927171,
    2290722225,
    3567286280,
    525134563,
    3049867588,
    1882619227,
  ];

  static const List<int> _envieddatalastFmApiKey = <int>[
    1346927218,
    2290722179,
    3567286331,
    525134551,
    3049867633,
    1882619245,
  ];

  static final String lastFmApiKey = String.fromCharCodes(List<int>.generate(
    _envieddatalastFmApiKey.length,
    (int i) => i,
    growable: false,
  ).map((int i) => _envieddatalastFmApiKey[i] ^ _enviedkeylastFmApiKey[i]));

  static const List<int> _enviedkeylastFmApiSecret = <int>[
    3224477520,
    545887477,
    526999744,
    3473233476,
    3072609722,
    1721586016,
  ];

  static const List<int> _envieddatalastFmApiSecret = <int>[
    3224477489,
    545887383,
    526999715,
    3473233440,
    3072609759,
    1721585926,
  ];

  static final String lastFmApiSecret = String.fromCharCodes(List<int>.generate(
    _envieddatalastFmApiSecret.length,
    (int i) => i,
    growable: false,
  ).map(
      (int i) => _envieddatalastFmApiSecret[i] ^ _enviedkeylastFmApiSecret[i]));
}
