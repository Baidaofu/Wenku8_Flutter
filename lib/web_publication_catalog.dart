import 'package:html/parser.dart' as html;
import 'package:xml/xml.dart';

import 'catalog.dart';
import 'legacy_transport.dart';
import 'publication_catalog.dart';

/// One official-site listing page: the book ids it shows and how many pages
/// the board has in total.
class WebPublicationPage {
  const WebPublicationPage(this.ids, this.totalPages);
  final List<int> ids;
  final int totalPages;
}

class PublicationConnectionRequired implements Exception {
  const PublicationConnectionRequired();
}

const _acceptedHosts = {'www.wenku8.net', 'wenku8.net', 'www.wenku8.cc', 'wenku8.cc'};

/// The API relay answers lists as XML; the official site answers them as HTML.
/// Both carry the same book ids, so one adapter serves the two representations.
WebPublicationPage parseWebPublicationPage(String source) {
  if (source.trim().isEmpty) throw const FormatException('官网分类返回空内容');
  final text = source.trim();
  if (RegExp(r'^(?:<\?xml[^>]*>\s*)?<result(?:\s|/|>)').hasMatch(text)) {
    final root = XmlDocument.parse(source).rootElement;
    final ids = <int>{};
    for (final item in root.findElements('item')) {
      final id = int.tryParse(item.getAttribute('aid') ?? '');
      if (id != null && id > 0 && id < 0x60000000) ids.add(id);
    }
    final pages =
        int.tryParse(root.getElement('page')?.getAttribute('num') ?? '') ?? 1;
    if (pages < 0 ||
        pages > 100000 ||
        (root.findElements('item').isNotEmpty && ids.isEmpty)) {
      throw const FormatException('书目格式有误');
    }
    return WebPublicationPage(List.unmodifiable(ids), pages);
  }
  final document = html.parse(source);
  final content = document.querySelector('#content');
  final ids = <int>{};
  if (content != null) {
    for (final anchor in content.querySelectorAll('a[href]')) {
      final uri = Uri.tryParse(anchor.attributes['href'] ?? '');
      if (uri == null ||
          (uri.hasAuthority && !_acceptedHosts.contains(uri.host))) {
        continue;
      }
      final match = RegExp(r'(?:^|/)book/(\d+)\.htm$').firstMatch(uri.path);
      final id = match != null
          ? int.tryParse(match[1]!)
          : uri.path.endsWith('/modules/article/articleinfo.php')
          ? int.tryParse(uri.queryParameters['id'] ?? '')
          : null;
      if (id != null && id > 0 && id < 0x60000000) ids.add(id);
    }
  }
  if (ids.isEmpty) {
    final body = document.body?.text ?? '';
    // Cloudflare's interstitial and the site's own sign-in wall both look like
    // an empty board; telling them apart keeps the retry advice honest.
    if (document.querySelector('input[name="password"]') != null ||
        RegExp(r'(请先|必须|需要|尚未|还未|没有).{0,8}(登录|登陆)|(登录|登陆)后')
            .hasMatch(body)) {
      throw const PublicationConnectionRequired();
    }
    if (RegExp(r'Just a moment|Enable JavaScript|cf-browser-verification')
        .hasMatch(body)) {
      throw const FormatException('官网要求浏览器验证，请在账号页测试连接后重试');
    }
    if (content != null &&
        RegExp(r'没有.{0,10}(书籍|作品|记录)|暂无.{0,10}(书籍|作品)')
            .hasMatch(content.text)) {
      return const WebPublicationPage([], 0);
    }
    throw const FormatException('官网书目页面格式发生变化，请稍后重试');
  }
  var pages = 1;
  for (final anchor
      in (content ?? document.body!).querySelectorAll('a[href]')) {
    final uri = Uri.tryParse(anchor.attributes['href'] ?? '');
    if (uri == null || !uri.path.endsWith('articlelist.php')) continue;
    final page = int.tryParse(uri.queryParameters['page'] ?? '');
    if (page != null && page > pages && page <= 100000) pages = page;
  }
  return WebPublicationPage(List.unmodifiable(ids), pages);
}

/// The API relay can answer a ranking board, but only the official site has the
/// every-account boards the discovery list uses. Each request therefore tries
/// the relay first and falls back to the shared website session, exactly like
/// a category listing; a retired action is not an expired account.
class WebPublicationCatalog implements PublicationCatalog {
  WebPublicationCatalog({
    required this.categories,
    required this.books,
    Future<String> Function(int library, int page)? read,
    Future<String> Function(String sort, int page, {bool complete})? ranking,
    Object Function()? sessionKey,
  }) : _read = read ?? LegacyTransport.publicationPage,
       _ranking = ranking ?? LegacyTransport.rankingPage,
       _sessionKey = sessionKey ?? (() => 0);
  final PublicationCatalog categories;
  final NovelRepository books;
  final Future<String> Function(int library, int page) _read;
  final Future<String> Function(
    String sort,
    int page, {
    bool complete,
  })
  _ranking;
  final Object Function() _sessionKey;
  final _pages = <(Object, int, int, int), Future<NovelPage>>{};
  final _versions = <int, int>{};

  @override
  Future<List<PublicationLibrary>> publicationLibraries({
    bool refresh = false,
  }) => categories.publicationLibraries(refresh: refresh);

  @override
  Future<NovelPage> browsePublication(
    int libraryId, {
    int page = 1,
    bool refresh = false,
  }) => _page((key: libraryId, sort: null), page: page, refresh: refresh);

  /// A board the site sorts itself; `sort` must be a board name.
  Future<NovelPage> browseRanking(
    NovelSort sort, {
    int page = 1,
    bool refresh = false,
  }) => _page(
    (key: 0, sort: sort),
    page: page,
    refresh: refresh,
  );

  Future<NovelPage> _page(
    ({int key, NovelSort? sort}) selection, {
    required int page,
    required bool refresh,
  }) {
    final id = selection.key;
    if (page <= 0) throw ArgumentError('Invalid catalogue page');
    if (selection.sort == null && id <= 0) {
      throw ArgumentError('Invalid catalogue page');
    }
    final session = _sessionKey();
    // Discard cached pages belonging to a previous account/website connection.
    _pages.removeWhere((entry, _) => entry.$1 != session);
    if (refresh) {
      if (page == 1) _versions[id] = (_versions[id] ?? 0) + 1;
      _pages.removeWhere(
        (entry, _) => entry.$2 == id && (page == 1 || entry.$3 == page),
      );
    }
    final version = _versions[id] ?? 0;
    final entry = (session, id, page, version);
    return _pages.putIfAbsent(entry, () async {
      try {
        final sort = selection.sort;
        final source = sort == null
            ? await _read(id, page)
            : await _ranking(
                sort.apiKey,
                page,
                complete: sort == NovelSort.fullFlag,
              );
        final parsed = parseWebPublicationPage(source);
        final found = <int, Novel>{};
        var cursor = 0;
        Future<void> resolve() async {
          // Ranking pages carry no metadata, so each id is fetched once. Four
          // readers keep the wall-clock cost of a 20-book page bounded.
          while (cursor < parsed.ids.length) {
            final bookId = parsed.ids[cursor++];
            final book = await books.find(bookId);
            if (book != null) found[bookId] = book;
          }
        }

        await Future.wait(
          List.generate(parsed.ids.length.clamp(0, 4), (_) => resolve()),
        );
        return NovelPage(
          items: [
            for (final bookId in parsed.ids)
              if (found[bookId] != null) found[bookId]!,
          ],
          page: page,
          totalPages: parsed.totalPages,
        );
      } catch (failure) {
        _pages.remove(entry);
        if (failure is FormatException) rethrow;
        throw FormatException(
          failure is PublicationConnectionRequired
              ? '此书目需要官网账号，请到账号页测试连接后重试'
              : '官网书目暂时不可用，请稍后重试',
        );
      }
    });
  }
}
