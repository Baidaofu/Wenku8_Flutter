import 'package:flutter/services.dart';

/// The same v1.30 POST bridge. Account cookies never cross into Dart or backups.
abstract final class LegacyTransport {
  static const channel = MethodChannel('org.mewx.wenku8/legacy_api');

  static Future<void> setUpdatePriority(bool enabled) async {
    try {
      await channel.invokeMethod<void>('updatePriority', {'enabled': enabled});
    } on MissingPluginException {
      // Non-Android clients keep the original transport.
    }
  }

  static Future<String> request(String action) => _text('request', action);
  static Future<String> accountRequest(String action) =>
      _text('accountRequest', action);

  /// Read-only routes which the service may gate behind a signed-in session.
  /// Public discovery/search remain independent from account cookies.
  static Future<String> sessionRead(String action) async =>
      await hasSession() ? accountRequest(action) : request(action);
  static Future<String> login(String action) => _text('login', action);

  static Future<Uint8List> accountAvatar() async {
    final bytes = await channel.invokeMethod<Uint8List>('accountAvatar');
    if (bytes == null || bytes.isEmpty) throw const FormatException('账号暂无头像');
    return bytes;
  }

  static Future<String> _text(String method, String action) async {
    final text = await channel.invokeMethod<String>(method, {'action': action});
    if (text == null || text.trim().isEmpty) {
      throw const FormatException('接口返回空内容');
    }
    return text;
  }

  static Future<bool> hasSession() async =>
      await channel.invokeMethod<bool>('hasSession') ?? false;
  static Future<void> clearSession() =>
      channel.invokeMethod<void>('clearSession');

  static Future<void> connectPublications(
    String username,
    String password,
  ) async {
    try {
      await channel.invokeMethod<void>('webLogin', {
        'username': username,
        'password': password,
      });
    } on PlatformException catch (failure) {
      if (failure.code.startsWith('WEB_')) {
        throw FormatException(failure.message ?? '分类连接未成功');
      }
      rethrow;
    }
  }

  static Future<String> publicationPage(int library, int page) async =>
      (await channel.invokeMethod<String>('publicationPage', {
        'library': library,
        'page': page,
      })) ??
      '';

  /// A ranking board read from the official site. `sort` is the site's own
  /// board name, never a category id.
  static Future<String> rankingPage(
    String sort,
    int page, {
    bool complete = false,
  }) async =>
      (await channel.invokeMethod<String>('websiteRanking', {
        'sort': sort,
        'page': page,
        'complete': complete,
      })) ??
      '';

  /// The official site has two interchangeable mirrors. Only one of them can
  /// hold a website session at a time; the API login is shared.
  static Future<WebsiteStatus> websiteStatus() async => WebsiteStatus.parse(
    await channel.invokeMethod<Map<Object?, Object?>>('websiteStatus'),
  );

  static Future<WebsiteStatus> selectWebsite(String host) async =>
      WebsiteStatus.parse(
        await channel.invokeMethod<Map<Object?, Object?>>('websiteSelect', {
          'host': host,
        }),
      );

  /// Opens the official login page in a real browser so the site's access
  /// check runs; the resulting session replaces the stored website jar.
  static Future<WebsiteStatus> browserLoginWebsite() async =>
      WebsiteStatus.parse(
        await channel.invokeMethod<Map<Object?, Object?>>('websiteBrowserLogin'),
      );

  static Future<WebsiteStatus> keepAliveWebsite(bool enabled) async =>
      WebsiteStatus.parse(
        await channel.invokeMethod<Map<Object?, Object?>>('websiteKeepAlive', {
          'enabled': enabled,
        }),
      );

  /// A reachability test that always reports an outcome, so the page can show
  /// both a success latency and a failure instead of staying silent.
  static Future<WebsiteProbe> probeWebsite() async {
    try {
      final reply = await channel.invokeMethod<Map<Object?, Object?>>(
        'websiteProbe',
      );
      return WebsiteProbe(
        millis: (reply?['millis'] as num?)?.toInt() ?? 0,
        ok: true,
      );
    } on PlatformException catch (failure) {
      return WebsiteProbe(
        millis: 0,
        ok: false,
        error: failure.message ?? '当前线路暂时不可用',
      );
    }
  }

  /// Application-bound image reader. Tests or alternate transports may replace
  /// it; the default reuses the native illustration cache and gateway.
  static Future<Uint8List> Function(String) illustrationReader =
      _channelIllustration;

  static Future<Uint8List> illustration(
    String url, {
    bool cacheForViewing = true,
  }) => illustrationReader(url);

  static Future<Uint8List> _channelIllustration(String url) async {
    final bytes = await channel.invokeMethod<Uint8List>('illustration', {
      'url': url,
    });
    if (bytes == null || bytes.isEmpty) throw const FormatException('插图内容为空');
    return bytes;
  }

  static Future<void> clearIllustrations() =>
      channel.invokeMethod<void>('clearIllustrations');

  static Future<String> encodeReview(String text) async {
    try {
      final encoded = await channel.invokeMethod<String>('encodeReview', {
        'text': text,
      });
      if (encoded == null || encoded.isEmpty) {
        throw const FormatException('书评编码返回空内容');
      }
      return encoded;
    } on PlatformException catch (error) {
      if (error.code == 'REVIEW_ENCODING') {
        throw const FormatException('原版书评接口使用 GBK，请移除表情或特殊字符后重试；草稿仍保留');
      }
      rethrow;
    }
  }
}

/// Result of one reachability test against the selected mirror.
class WebsiteProbe {
  const WebsiteProbe({required this.millis, required this.ok, this.error});
  final int millis;
  final bool ok;
  final String? error;
}

/// Which official mirror the website session is bound to.
class WebsiteStatus {
  const WebsiteStatus({
    required this.host,
    required this.signedIn,
    this.keepAlive = true,
  });
  final String host;
  final bool signedIn;

  /// Whether the website session may outlive the process.
  final bool keepAlive;
  static const net = 'net';
  static const cc = 'cc';
  static const _suffixes = [net, cc];
  String get label => 'www.wenku8.$host';
  static WebsiteStatus parse(Map<Object?, Object?>? source) =>
      WebsiteStatus(
        host: _suffixes.contains(source?['host']) ? source!['host']! as String : net,
        signedIn: source?['signedIn'] == true,
        keepAlive: source?['keepAlive'] != false,
      );
}
