package com.onthegomap.planetiler.archive;

import static com.onthegomap.planetiler.util.LanguageUtils.nullIfEmpty;

import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.files.FilesArchiveUtils;
import com.onthegomap.planetiler.geo.TileOrder;
import com.onthegomap.planetiler.stream.StreamArchiveUtils;
import com.onthegomap.planetiler.util.FileUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Definition for a tileset, parsed from a URI-like string.
 * <p>
 * {@link #from(String)} can accept:
 * <ul>
 * <li>A platform-specific absolute or relative path like {@code "./archive.mbtiles"} or
 * {@code "C:\root\archive.mbtiles"}</li>
 * <li>A URI pointing at a file, like {@code "file:///root/archive.pmtiles"} or
 * {@code "file:///C:/root/archive.pmtiles"}</li>
 * </ul>
 * <p>
 * Both of these can also have archive-specific options added to the end, for example
 * {@code "output.mbtiles?compact=false&page_size=16384"}.
 *
 * @param format  The {@link Format format} of the archive, either inferred from the filename extension or the
 *                {@code ?format=} query parameter
 * @param scheme  Scheme for accessing the archive
 * @param uri     Full URI including scheme, location, and options
 * @param options Parsed query parameters from the definition string
 */
public record TileArchiveConfig(
  Format format,
  Scheme scheme,
  URI uri,
  Map<String, String> options
) {

  // be more generous and encode some characters for the users
  private static final Map<String, String> URI_ENCODINGS = Map.of(
    "{", "%7B",
    "}", "%7D"
  );

  private static TileArchiveConfig.Scheme getScheme(URI uri) {
    String scheme = uri.getScheme();
    if (scheme == null) {
      return Scheme.FILE;
    }
    for (var value : TileArchiveConfig.Scheme.values()) {
      if (value.id().equals(scheme)) {
        return value;
      }
    }
    throw new IllegalArgumentException("Unsupported scheme " + scheme + " from " + uri);
  }

  private static String getExtension(URI uri) {
    String path = uri.getPath();
    if (path != null && (path.contains("."))) {
      return nullIfEmpty(path.substring(path.lastIndexOf(".") + 1));
    }
    return null;
  }

  private static Map<String, String> parseQuery(URI uri) {
    String query = uri.getRawQuery();
    Map<String, String> result = new HashMap<>();
    if (query != null) {
      for (var part : query.split("&")) {
        var split = part.split("=", 2);
        result.put(
          URLDecoder.decode(split[0], StandardCharsets.UTF_8),
          split.length == 1 ? "true" : URLDecoder.decode(split[1], StandardCharsets.UTF_8)
        );
      }
    }
    return result;
  }

  private static TileArchiveConfig.Format getFormat(URI uri) {
    String format = parseQuery(uri).get("format");
    for (var value : TileArchiveConfig.Format.values()) {
      if (value.isQueryFormatSupported(format)) {
        return value;
      }
    }
    if (format != null) {
      throw new IllegalArgumentException("Unsupported format " + format + " from " + uri);
    }
    for (var value : TileArchiveConfig.Format.values()) {
      if (value.isUriSupported(uri)) {
        return value;
      }
    }
    throw new IllegalArgumentException("Unsupported format " + getExtension(uri) + " from " + uri);
  }

  /**
   * Parses a string definition of a tileset from a URI-like string.
   */
  public static TileArchiveConfig from(String string) {
    // unix paths parse fine as URIs, but need to explicitly parse windows paths with backslashes
    if (string.contains("\\")) {
      String[] parts = string.split("\\?", 2);
      string = Path.of(parts[0]).toUri().toString();
      if (parts.length > 1) {
        string += "?" + parts[1];
      }
    }
    for (Map.Entry<String, String> uriEncoding : URI_ENCODINGS.entrySet()) {
      string = string.replace(uriEncoding.getKey(), uriEncoding.getValue());
    }

    return from(URI.create(string));
  }

  /**
   * Parses a string definition of a tileset from a URI.
   */
  public static TileArchiveConfig from(URI uri) {
    if (uri.getScheme() == null) {
      final String path = uri.getPath();
      String base = Path.of(path).toAbsolutePath().toUri().normalize().toString();
      if (path.endsWith("/")) {
        base = base + "/";
      }
      if (uri.getRawQuery() != null) {
        base += "?" + uri.getRawQuery();
      }
      uri = URI.create(base);
    }
    return new TileArchiveConfig(
      getFormat(uri),
      getScheme(uri),
      uri,
      parseQuery(uri)
    );
  }

  /**
   * Returns the local path on disk that this archive reads/writes to, or {@code null} if it is not on disk (ie. an HTTP
   * repository).
   */
  public Path getLocalPath() {
    return scheme == Scheme.FILE ? Path.of(URI.create(uri.toString().replaceAll("\\?.*$", ""))) : null;
  }

  /**
   * Returns the local <b>base</b> path for this archive, for which directories should be pre-created for.
   */
  public Path getLocalBasePath() {
    Path p = getLocalPath();
    if (format() == Format.FILES) {
      p = FilesArchiveUtils.cleanBasePath(p);
    }
    return p;
  }


  /**
   * Deletes the archive if possible.
   */
  public void delete() {
    if (scheme == Scheme.FILE) {
      FileUtils.delete(getLocalBasePath());
    }
  }

  /**
   * Returns {@code true} if the archive already exists, {@code false} otherwise.
   */
  public boolean exists() {
    return exists(getLocalBasePath());
  }

  /**
   * @param p path to the archive
   * @return {@code true} if the archive already exists, {@code false} otherwise.
   */
  public boolean exists(Path p) {
    if (p == null) {
      return false;
    }
    if (format() != Format.FILES) {
      return Files.exists(p);
    } else {
      if (!Files.exists(p)) {
        return false;
      }
      // file-archive exists only if it has any contents
      try (Stream<Path> paths = Files.list(p)) {
        return paths.findAny().isPresent();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /**
   * Returns the current size of this archive.
   */
  public long size() {
    return getLocalPath() == null ? 0 : FileUtils.size(getLocalPath());
  }

  /**
   * Returns an {@link Arguments} instance that returns the value for options directly from the query parameters in the
   * URI, or from {@code arguments} prefixed by {@code "format_"}.
   */
  public Arguments applyFallbacks(Arguments arguments) {
    return Arguments.of(options).orElse(arguments.withPrefix(format.id));
  }

  public Path getPathForMultiThreadedWriter(int index) {
    return switch (format) {
      case CSV, TSV, JSON, PROTO, PBF -> StreamArchiveUtils.constructIndexedPath(getLocalPath(), index);
      case FILES -> getLocalPath();
      default -> throw new UnsupportedOperationException("not supported by " + format);
    };
  }

  public enum Format {
    MBTILES("mbtiles",
      false /* TODO mbtiles could support append in the future by using insert statements with an "on conflict"-clause (i.e. upsert) and by creating tables only if they don't exist, yet */,
      false, TileOrder.TMS),
    PMTILES("pmtiles", false, false, TileOrder.HILBERT),

    // should be before PBF in order to avoid collisions
    FILES("files", true, true, TileOrder.TMS) {
      @Override
      boolean isUriSupported(URI uri) {
        final String path = uri.getPath();
        return path != null && (path.endsWith("/") || path.contains("{") /* template string */ ||
          !path.contains(".") /* no extension => assume files */);
      }
    },

    CSV("csv", true, true, TileOrder.TMS),
    /** identical to {@link Format#CSV} - except for the column separator */
    TSV("tsv", true, true, TileOrder.TMS),

    PROTO("proto", true, true, TileOrder.TMS),
    /** identical to {@link Format#PROTO} */
    PBF("pbf", true, true, TileOrder.TMS),

    JSON("json", true, true, TileOrder.TMS);

    private final String id;
    private final boolean supportsAppend;
    private final boolean supportsConcurrentWrites;
    private final TileOrder order;

    Format(String id, boolean supportsAppend, boolean supportsConcurrentWrites, TileOrder order) {
      this.id = id;
      this.supportsAppend = supportsAppend;
      this.supportsConcurrentWrites = supportsConcurrentWrites;
      this.order = order;
    }

    public TileOrder preferredOrder() {
      return order;
    }

    public String id() {
      return id;
    }

    public boolean supportsAppend() {
      return supportsAppend;
    }

    public boolean supportsConcurrentWrites() {
      return supportsConcurrentWrites;
    }

    boolean isUriSupported(URI uri) {
      final String path = uri.getPath();
      return path != null && path.endsWith("." + id);
    }

    boolean isQueryFormatSupported(String queryFormat) {
      return id.equals(queryFormat);
    }
  }

  public enum Scheme {
    FILE("file");

    private final String id;

    Scheme(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }
}
