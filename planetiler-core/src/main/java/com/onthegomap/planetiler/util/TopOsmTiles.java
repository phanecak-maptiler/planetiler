package com.onthegomap.planetiler.util;

import static com.onthegomap.planetiler.util.Exceptions.throwFatalException;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import com.google.common.io.LineReader;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.TileCoord;
import com.onthegomap.planetiler.stats.ProgressLoggers;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.worker.WorkerPipeline;
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tukaani.xz.XZInputStream;

/**
 * A utility for computing {@link TileWeights} from historic openstreetmap.org tile traffic.
 * <p>
 * To download raw data from OSM tile logs, run with:
 *
 * <pre>
 * {@code
 * java -jar planetiler.jar top-osm-tiles --days=<# days to fetch> --top=<# tiles to include> --output=output.tsv.gz
 * }
 * </pre>
 * <p>
 * You can also fetch precomputed top-1m tile stats from summer 2023 using
 * {@link #downloadPrecomputed(PlanetilerConfig)}
 */
public class TopOsmTiles {

  private static final String DOWLOAD_URL =
    "https://raw.githubusercontent.com/onthegomap/planetiler/main/layerstats/top_osm_tiles.tsv.gz";
  private static final Logger LOGGER = LoggerFactory.getLogger(TopOsmTiles.class);
  private final Stats stats;
  private final PlanetilerConfig config;
  private final Downloader downloader;

  TopOsmTiles(PlanetilerConfig config, Stats stats) {
    this.config = config;
    this.stats = stats;
    downloader = Downloader.create(config);
  }

  Reader fetch(LocalDate date) throws IOException {
    String url = "https://planet.openstreetmap.org/tile_logs/tiles-%4d-%02d-%02d.txt.xz".formatted(
      date.getYear(),
      date.getMonthValue(),
      date.getDayOfMonth()
    );
    return new InputStreamReader(new XZInputStream(new BufferedInputStream(downloader.openStream(url))));
  }

  @SuppressWarnings("java:S2142")
  TileWeights run(int threads, int topN, int maxZoom, List<LocalDate> toDownload) {
    CompletableFuture<TileWeights> result = new CompletableFuture<>();
    var timer = stats.startStage("top-osm-tiles");

    AtomicLong downloaded = new AtomicLong();

    var pipeline = WorkerPipeline.start("top-osm-tiles", stats)
      .readFromTiny("urls", toDownload).<Map.Entry<Integer, Long>>addWorker("download", threads,
        (prev, next) -> {
          for (var date : prev) {
            for (var line : readFile(maxZoom, date)) {
              next.accept(line);
            }
            downloaded.incrementAndGet();
          }
        })
      .addBuffer("lines", 100_000, 1_000)
      .sinkTo("collect", 1, lines -> {
        Map<Integer, Long> counts = new HashMap<>();
        for (var line : lines) {
          counts.merge(line.getKey(), line.getValue(), Long::sum);
        }
        LOGGER.info("Extracting top {} tiles from {} tiles", topN, counts.size());
        var tileWeights = new TileWeights();
        counts.entrySet().stream()
          .sorted(Comparator.comparingLong(e -> -e.getValue()))
          .limit(topN)
          .forEach(entry -> tileWeights.put(TileCoord.decode(entry.getKey()), entry.getValue()));
        result.complete(tileWeights);
      });

    ProgressLoggers progress = ProgressLoggers.create()
      .addPercentCounter("files", toDownload.size(), downloaded)
      .newLine()
      .addPipelineStats(pipeline)
      .newLine()
      .addProcessStats();

    pipeline.awaitAndLog(progress, config.logInterval());
    timer.stop();
    stats.printSummary();
    try {
      return result.get();
    } catch (InterruptedException | ExecutionException e) {
      return throwFatalException(e);
    }
  }

  private List<Map.Entry<Integer, Long>> readFile(int maxZoom, LocalDate date) {
    var splitter = Pattern.compile("[/ ]");
    for (int i = 0; i <= config.httpRetries(); i++) {
      List<Map.Entry<Integer, Long>> result = new ArrayList<>();
      try (var reader = fetch(date)) {
        LineReader lines = new LineReader(reader);
        String line;
        while ((line = lines.readLine()) != null) {
          String[] parts = splitter.split(line);
          if (parts.length == 4) {
            // adjust osm tiles (256x256px) to vector (512x512px) by moving up one zoom level
            int z = Integer.parseInt(parts[0]) - 1;
            if (z >= 0 && z <= maxZoom) {
              int x = Integer.parseInt(parts[1]) >> 1;
              int y = Integer.parseInt(parts[2]) >> 1;
              long loads = Long.parseLong(parts[3]);
              result.add(Map.entry(TileCoord.ofXYZ(x, y, z).encoded(), loads));
            }
          }
        }
        return result;
      } catch (FileNotFoundException e) {
        LOGGER.info("No data for {}", date);
        break;
      } catch (IOException e) {
        if (i == config.httpRetries()) {
          LOGGER.warn("Failed getting {} {}", date, e);
        }
      }
    }
    return List.of();
  }

  public static void main(String[] args) throws IOException {
    Arguments arguments = Arguments.fromArgsOrConfigFile(args).orElse(Arguments.of(Map.of(
      "http-retries", "3"
    )));
    var config = PlanetilerConfig.from(arguments);
    var stats = arguments.getStats();
    var days = arguments.getInteger("days", "number of days into the past to look", 90);
    var maxZoom = arguments.getInteger("maxzoom", "max zoom", 15);
    var topN = arguments.getInteger("top", "top n", 1_000_000);
    var output = arguments.file("output", "output", Path.of("top_osm_tiles.tsv.gz"));
    var threads = arguments.getInteger("download-threads", "number of threads to use for downloading/parsing",
      Math.min(10, arguments.threads()));

    var date = LocalDate.now(ZoneOffset.UTC);
    var toDownload = IntStream.range(0, days)
      .mapToObj(i -> date.minus(Period.ofDays(i)))
      .toList();

    new TopOsmTiles(config, stats)
      .run(threads, topN, maxZoom, toDownload)
      .writeToFile(output);
  }

  /**
   * Download precomputed top-1m tile stats from 90 days of openstreetmap.org tile logs to
   * {@link PlanetilerConfig#tileWeights()} path if they don't already exist.
   */
  @SuppressWarnings("java:S2142")
  public static void downloadPrecomputed(PlanetilerConfig config) {
    var dest = config.tileWeights();
    if (!Files.exists(dest)) {
      var tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
      FileUtils.deleteOnExit(tmp);
      try {
        try (
          var in = Downloader.openStream(DOWLOAD_URL, config);
          var out = Files.newOutputStream(tmp, CREATE, TRUNCATE_EXISTING, WRITE)
        ) {
          LOGGER.info("Downloading pre-computed tile weights from {} to {}", DOWLOAD_URL, dest);
          in.transferTo(out);
        }
        Files.move(tmp, dest);
      } catch (IOException e) {
        LOGGER.warn("Failed downloading pre-computed tile weights: {}", e.toString());
        FileUtils.delete(dest);
      } finally {
        FileUtils.delete(tmp);
      }
    }
  }
}
