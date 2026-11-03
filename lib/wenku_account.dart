import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:xml/xml.dart';

import 'legacy_transport.dart';
import 'library_state.dart';
import 'online_component.dart';
import 'wenku_shelf_sync.dart' show CloudShelfPlan;

typedef AccountRequest = Future<String> Function(String action);

class WenkuApiError implements Exception {
  const WenkuApiError(this.code);
  final int code;
  String get message => switch (code) {
    2 => '用户名或邮箱不正确',
    3 => '密码不正确',
    4 => '登录已过期，请重新登录；本地阅读记录仍保留',
    6 => '云书架已满，已完成的收藏仍保留',
    8 => '这条书评已不存在',
    9 => '今天已经签到',
    10 => '本次推荐未成功，请稍后再试',
    11 => '发表未成功，请检查内容后重试',
    _ => '服务器未完成本次操作，请稍后重试',
  };
  @override
  String toString() => message;
}

int? wenkuResponseCode(String source) => int.tryParse(source.trim());
void requireWenkuSuccess(String source, {Set<int> accepted = const {1}}) {
  final code = wenkuResponseCode(source);
  if (code == null) throw const FormatException('接口返回的操作结果格式有误');
  if (!accepted.contains(code)) throw WenkuApiError(code);
}

class WenkuProfile {
  const WenkuProfile({
    required this.id,
    required this.username,
    required this.nickname,
    required this.score,
    required this.experience,
    required this.rank,
  });
  final int id;
  final String username, nickname, rank;
  final int score, experience;
  String get displayName => nickname.isEmpty ? username : nickname;
  factory WenkuProfile.parse(String source) {
    final code = wenkuResponseCode(source);
    if (code != null) throw WenkuApiError(code);
    final root = XmlDocument.parse(source).rootElement;
    if (root.name.local != 'metadata') throw const FormatException('账号信息格式有误');
    final fields = {
      for (final item in root.findElements('item'))
        item.getAttribute('name'): item.innerText.trim(),
    };
    final id = int.tryParse(fields['uid'] ?? '');
    final name = fields['uname'] ?? '';
    if (id == null || id <= 0 || name.isEmpty) {
      throw const FormatException('账号信息不完整');
    }
    return WenkuProfile(
      id: id,
      username: name,
      nickname: fields['nickname'] ?? '',
      score: int.tryParse(fields['score'] ?? '') ?? 0,
      experience: int.tryParse(fields['experience'] ?? '') ?? 0,
      rank: fields['rank'] ?? '',
    );
  }
}

class CloudBook {
  const CloudBook({
    required this.id,
    this.title = '',
    this.updated = '',
    this.latestChapterId = 0,
    this.latestChapter = '',
  });
  final int id, latestChapterId;
  final String title, updated, latestChapter;
  static List<CloudBook> parse(String source) {
    final code = wenkuResponseCode(source);
    if (code != null) throw WenkuApiError(code);
    final root = XmlDocument.parse(source).rootElement;
    if (root.name.local != 'metadata') throw const FormatException('云书架格式有误');
    final seen = <int>{};
    final books = <CloudBook>[];
    for (final book in root.findElements('book')) {
      final id = int.tryParse(book.getAttribute('aid') ?? '');
      if (id == null || id <= 0 || id > 0x7fffffff || !seen.add(id)) continue;
      final chapter = book.getElement('chapter');
      books.add(
        CloudBook(
          id: id,
          title: book.getElement('name')?.innerText.trim() ?? '',
          updated: book.getAttribute('date') ?? '',
          latestChapterId:
              int.tryParse(chapter?.getAttribute('cid') ?? '') ?? 0,
          latestChapter: chapter?.innerText.trim() ?? '',
        ),
      );
    }
    // An unrelated metadata document is not an empty bookshelf.
    if (root.childElements.any((element) => element.name.local != 'book') ||
        (root.childElements.isNotEmpty && books.isEmpty)) {
      throw const FormatException('云书架格式有误');
    }
    return List.unmodifiable(books);
  }
}

class CloudUploadResult {
  const CloudUploadResult(this.uploaded, this.remaining, this.error);
  final List<int> uploaded, remaining;
  final Object? error;
}

/// Session owner for user actions and the explicit cloud-shelf synchronizer.
/// A plain shelf fetch never mutates membership or reading progress.
class WenkuAccount extends ChangeNotifier {
  WenkuAccount({
    AccountRequest? request,
    AccountRequest? loginRequest,
    Future<bool> Function()? hasSession,
    Future<void> Function()? clearSession,
    Future<void> Function(String username, String password)? publicationLogin,
    Future<Uint8List> Function()? avatarRequest,
    Future<WebsiteStatus> Function()? websiteStatus,
    Future<WebsiteStatus> Function(String host)? websiteSelect,
    Future<WebsiteProbe> Function()? websiteProbe,
    Future<WebsiteStatus> Function(bool enabled)? websiteKeepAlive,
    Future<WebsiteStatus> Function()? websiteBrowserLogin,
  }) : _request = request ?? LegacyTransport.accountRequest,
       _login = loginRequest ?? LegacyTransport.login,
       _hasSession = hasSession ?? LegacyTransport.hasSession,
       _clear = clearSession ?? LegacyTransport.clearSession,
       _avatarRequest = avatarRequest ?? LegacyTransport.accountAvatar,
       _websiteStatus = websiteStatus ?? LegacyTransport.websiteStatus,
       _websiteSelect = websiteSelect ?? LegacyTransport.selectWebsite,
       _websiteProbe = websiteProbe ?? LegacyTransport.probeWebsite,
       _websiteKeepAlive =
           websiteKeepAlive ?? LegacyTransport.keepAliveWebsite,
       _websiteBrowserLogin =
           websiteBrowserLogin ?? LegacyTransport.browserLoginWebsite,
       _publicationLogin =
           publicationLogin ?? LegacyTransport.connectPublications;
  final AccountRequest _request, _login;
  final Future<Uint8List> Function() _avatarRequest;
  final Future<WebsiteStatus> Function() _websiteStatus;
  final Future<WebsiteStatus> Function(String host) _websiteSelect;
  final Future<WebsiteProbe> Function() _websiteProbe;
  final Future<WebsiteStatus> Function(bool enabled) _websiteKeepAlive;
  final Future<WebsiteStatus> Function() _websiteBrowserLogin;
  Uint8List? avatar;
  Future<void> _loadAvatar() async {
    final identity = _identity;
    try {
      final bytes = await _avatarRequest();
      if (!_disposed &&
          identity == _identity &&
          authenticated &&
          bytes.length <= 2 * 1024 * 1024) {
        avatar = bytes;
        _notify();
      }
    } catch (_) {
      /* An absent avatar does not invalidate a login. */
    }
  }

  final Future<bool> Function() _hasSession;
  final Future<void> Function() _clear;
  final Future<void> Function(String username, String password)
  _publicationLogin;
  int _publicationRevision = 0;
  int get publicationRevision => _publicationRevision;
  Future<void>? _initialization;
  bool _disposed = false;
  int _identity = 0;
  int get sessionRevision => _identity;
  bool authenticated = false;
  bool busy = false;
  String? operation, error;
  WenkuProfile? profile;
  List<CloudBook>? cloudBooks;
  int? syncedAt;

  /// The official mirrors share one account, but each keeps its own session.
  String websiteHost = WebsiteStatus.net;
  bool websiteSignedIn = false;
  /// Keep the website login on this device between launches.
  bool websiteKeepAlive = true;
  /// The last connection test, including a failure, so the page can report it.
  WebsiteProbe? websiteProbe;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  static String describeError(Object error) => error is WenkuApiError
      ? error.message
      : error is OnlineComponentUnavailable
      ? error.toString()
      : error is OnlineAuthenticationRequired
      ? error.toString()
      : error is FormatException
      ? error.message.toString()
      : '网络或本地存储暂时异常，请重试';

  Future<void> initialize() => _initialization ??= _run('正在读取账号', () async {
    final session = await _hasSession();
    if (_disposed) return;
    authenticated = session;
    if (authenticated) {
      profile = WenkuProfile.parse(await _send('action=userinfo'));
      unawaited(_loadAvatar());
      unawaited(_refreshWebsiteStatus());
    }
  }).catchError((Object _) {});

  Future<T> _run<T>(String label, Future<T> Function() work) async {
    if (busy) throw StateError('An account operation is already active');
    busy = true;
    operation = label;
    error = null;
    _notify();
    try {
      return await work();
    } catch (failure) {
      error = describeError(failure);
      rethrow;
    } finally {
      busy = false;
      operation = null;
      _notify();
    }
  }

  Future<String> _send(String action) async {
    if (!authenticated) throw const WenkuApiError(4);
    final identity = _identity;
    final reply = await _request(action);
    if (_disposed || identity != _identity) throw StateError('Account changed');
    if (wenkuResponseCode(reply) == 4) {
      await _clear();
      _identity++;
      authenticated = false;
      profile = null;
      avatar = null;
      cloudBooks = null;
      syncedAt = null;
      websiteProbe = null;
      throw const WenkuApiError(4);
    }
    return reply;
  }

  Future<void> login(
    String username,
    String password,
  ) => _run('正在登录', () async {
    final name = username.trim();
    if (name.isEmpty ||
        name.length > 254 ||
        password.isEmpty ||
        password.length > 30 ||
        RegExp(r'[\x00-\x1f]').hasMatch(name)) {
      throw const FormatException('请填写用户名 / 邮箱及密码');
    }
    final action = name.contains('@') ? 'loginemail' : 'login';
    final reply = await _login(
      'action=$action&username=${Uri.encodeQueryComponent(name)}&password=${Uri.encodeQueryComponent(password)}',
    );
    if (_disposed) return;
    requireWenkuSuccess(reply);
    _identity++;
    authenticated = true;
    profile = null;
    avatar = null;
    cloudBooks = null;
    syncedAt = null;
    websiteProbe = null;
    // The session is valid even if the following profile request is offline.
    profile = WenkuProfile.parse(await _send('action=userinfo'));
    unawaited(_loadAvatar());
    unawaited(_refreshWebsiteStatus());
    // Classification uses this API session. Do not send the password a second
    // time to the unrelated website login or hold up a valid API sign-in.
    _publicationRevision++;
  });

  Future<void> connectPublications(String password) => _run('正在连接分类', () async {
    if (!authenticated) throw const WenkuApiError(4);
    if (password.isEmpty || password.length > 30) {
      throw const FormatException('请填写当前账号的密码');
    }
    profile = WenkuProfile.parse(await _send('action=userinfo'));
    await _publicationLogin(profile!.username, password);
    _publicationRevision++;
  });

  Future<void> refreshProfile() => _run('正在更新账号', () async {
    profile = WenkuProfile.parse(await _send('action=userinfo'));
    unawaited(_loadAvatar());
  });

  /// A classification refusal is not evidence that a working account expired.
  /// Let userinfo validate/refresh the native jar, then retry this read once.
  /// Passwords and cookie values never need to leave their existing owners.
  Future<String> readPublication(String action) async {
    await initialize();
    if (!authenticated) throw const OnlineAuthenticationRequired();
    final identity = _identity;
    Future<String> read() async {
      final response = await _request(action);
      if (_disposed || identity != _identity) {
        throw StateError('Account changed');
      }
      return response;
    }

    final first = await read();
    if (wenkuResponseCode(first) != 4) return first;
    // Only an explicit userinfo expiry can invalidate the stored account.
    profile = WenkuProfile.parse(await _send('action=userinfo'));
    _notify();
    return read();
  }

  /// Refresh the remember-me website session implied by the API login.
  ///
  /// The original app re-submits the password here; a signed-in account does
  /// not need to hold it, so the refreshed profile is the actual readiness
  /// signal and the website jar is re-derived on the next catalogue read.
  Future<void> refreshWebsiteSession() => _run('正在刷新登录状态', () async {
    profile = WenkuProfile.parse(await _send('action=userinfo'));
    await _refreshWebsiteStatus();
  });

  Future<void> _refreshWebsiteStatus() async {
    try {
      final status = await _websiteStatus();
      if (_disposed) return;
      websiteHost = status.host;
      websiteSignedIn = status.signedIn;
      websiteKeepAlive = status.keepAlive;
    } catch (_) {
      /* An unreadable website jar never invalidates an API login. */
    }
    _notify();
  }

  /// Keep the account usable on a chosen mirror; switching mirrors does not
  /// touch the API session, the shelf, or reading progress.
  Future<void> selectWebsite(String host) => _run('正在切换官网线路', () async {
    final status = await _websiteSelect(host);
    if (_disposed) return;
    websiteHost = status.host;
    websiteSignedIn = status.signedIn;
    websiteKeepAlive = status.keepAlive;
    websiteProbe = null;
  });

  /// The site needs a browser check, so the account page offers a real login
  /// window instead of posting the form over HTTP.
  Future<void> connectWebsiteInBrowser() => _run('正在连接官网', () async {
    final status = await _websiteBrowserLogin();
    if (_disposed) return;
    websiteHost = status.host;
    websiteSignedIn = status.signedIn;
    websiteKeepAlive = status.keepAlive;
    websiteProbe = null;
    if (status.signedIn) {
      // The website identity is the one that just signed in.
      profile = WenkuProfile.parse(await _send('action=userinfo'));
      unawaited(_loadAvatar());
    }
  });

  Future<void> setWebsiteKeepAlive(bool enabled) =>
      _run(enabled ? '正在开启保持登录' : '正在关闭保持登录', () async {
        final status = await _websiteKeepAlive(enabled);
        if (_disposed) return;
        websiteKeepAlive = status.keepAlive;
        websiteSignedIn = status.signedIn;
      });

  Future<void> testWebsite() => _run('正在测试连接', () async {
    websiteProbe = null;
    _notify();
    final probe = await _websiteProbe();
    if (_disposed) return;
    websiteProbe = probe;
    // A failed test is a real result: surface it instead of staying silent.
    if (!probe.ok) error = probe.error ?? '当前线路暂时不可用';
    await _refreshWebsiteStatus();
  });
  Future<void> logout() => _run('正在退出账号', () async {
    // Local logout succeeds offline; never remove the local shelf/body/history.
    try {
      if (authenticated) await _request('action=logout');
    } catch (_) {}
    await _clear();
    _identity++;
    authenticated = false;
    profile = null;
    avatar = null;
    cloudBooks = null;
    syncedAt = null;
    websiteProbe = null;
  });
  Future<List<CloudBook>> refreshCloud() => _run('正在读取云书架', () async {
    final books = CloudBook.parse(await _send('action=bookcase&t=0'));
    cloudBooks = books;
    syncedAt = DateTime.now().millisecondsSinceEpoch;
    return books;
  });

  Future<CloudShelfPlan> exchangeShelf({
    required Set<int> local,
    Set<int>? lastLocal,
    Set<int>? lastCloud,
  }) => _run('正在同步书架', () async {
    final remote = CloudBook.parse(await _send('action=bookcase&t=0'));
    final ids = remote.map((b) => b.id).toSet();
    final plan = CloudShelfPlan(
      local: local,
      remote: ids,
      lastLocal: lastLocal,
      lastCloud: lastCloud,
    );
    for (final id in plan.target.difference(ids)) {
      requireWenkuSuccess(
        await _send('action=bookcase&do=add&aid=$id'),
        accepted: const {1, 5},
      );
    }
    for (final id in ids.difference(plan.target)) {
      requireWenkuSuccess(
        await _send('action=bookcase&do=del&aid=$id'),
        accepted: const {1, 7},
      );
    }
    cloudBooks = List.unmodifiable([
      ...remote.where((b) => plan.target.contains(b.id)),
      for (final id in plan.target.difference(ids)) CloudBook(id: id),
    ]);
    syncedAt = DateTime.now().millisecondsSinceEpoch;
    return plan;
  });

  Future<CloudUploadResult> uploadMissing(Set<int> localIds) => _run(
    '正在合并到云端',
    () async {
      // Read afresh before every upload; a malformed/error result must not turn
      // into an empty cloud shelf and schedule all local books for upload.
      final remote = CloudBook.parse(await _send('action=bookcase&t=0'));
      final remoteIds = remote.map((book) => book.id).toSet();
      final missing = localIds
          .where((id) => id > 0 && id < 0x60000000 && !remoteIds.contains(id))
          .toList();
      final uploaded = <int>[];
      Object? failure;
      for (final id in missing) {
        try {
          requireWenkuSuccess(
            await _send('action=bookcase&do=add&aid=$id'),
            accepted: const {1, 5},
          );
          uploaded.add(id);
        } catch (error) {
          failure = error;
          break;
        }
      }
      if (authenticated) {
        cloudBooks = List.unmodifiable([
          ...remote,
          for (final id in uploaded) CloudBook(id: id),
        ]);
      }
      if (failure != null) error = describeError(failure);
      syncedAt = DateTime.now().millisecondsSinceEpoch;
      return CloudUploadResult(
        List.unmodifiable(uploaded),
        List.unmodifiable(missing.skip(uploaded.length)),
        failure,
      );
    },
  );

  Future<void> addCloud(int id) => _run('正在收藏到云端', () async {
    if (id <= 0) throw ArgumentError.value(id);
    requireWenkuSuccess(
      await _send('action=bookcase&do=add&aid=$id'),
      accepted: const {1, 5},
    );
    if (cloudBooks != null && !cloudBooks!.any((book) => book.id == id)) {
      cloudBooks = List.unmodifiable([...cloudBooks!, CloudBook(id: id)]);
    }
  });
  Future<void> removeCloud(int id) => _run('正在移出云书架', () async {
    if (id <= 0) throw ArgumentError.value(id);
    requireWenkuSuccess(
      await _send('action=bookcase&do=del&aid=$id'),
      accepted: const {1, 7},
    );
    cloudBooks = cloudBooks
        ?.where((book) => book.id != id)
        .toList(growable: false);
  });
  Future<void> recommend(int id) => _run('正在推荐作品', () async {
    if (id <= 0) throw ArgumentError.value(id);
    requireWenkuSuccess(await _send('action=book&do=vote&aid=$id'));
  });
  Future<void> submitReview(String action) => _run('正在发表', () async {
    if (!action.startsWith('action=review&do=post&') &&
        !action.startsWith('action=review&do=reply&')) {
      throw ArgumentError('Invalid review action');
    }
    requireWenkuSuccess(await _send(action));
  });
  Future<void> postReview({
    required int bookId,
    required String title,
    required String content,
  }) async {
    if (bookId <= 0) throw ArgumentError.value(bookId);
    final encodedTitle = await LegacyTransport.encodeReview(title);
    final encodedContent = await LegacyTransport.encodeReview(content);
    await submitReview(
      'action=review&do=post&aid=$bookId&title=$encodedTitle&content=$encodedContent',
    );
  }

  Future<void> replyReview({
    required int threadId,
    required String content,
  }) async {
    if (threadId <= 0) throw ArgumentError.value(threadId);
    final encodedContent = await LegacyTransport.encodeReview(content);
    await submitReview(
      'action=review&do=reply&rid=$threadId&content=$encodedContent',
    );
  }
  Future<int> mergeCloudToLocal(LibraryState state) async {
    return _run('正在合并本机收藏', () async {
      final entries = CloudBook.parse(await _send('action=bookcase&t=0'));
      final before = state.shelf;
      await state.addShelfIds(entries.map((book) => book.id));
      cloudBooks = entries;
      syncedAt = DateTime.now().millisecondsSinceEpoch;
      return entries.where((book) => !before.contains(book.id)).length;
    });
  }

  @override
  void dispose() {
    _disposed = true;
    _identity++;
    super.dispose();
  }
}
