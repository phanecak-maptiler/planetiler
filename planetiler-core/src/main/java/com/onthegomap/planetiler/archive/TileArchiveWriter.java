package com.onthegomap.planetiler.archive;

import static com.onthegomap.planetiler.util.Gzip.gzip;
import static com.onthegomap.planetiler.worker.Worker.joinFutures;

import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.collection.FeatureGroup;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.geo.TileCoord;
import com.onthegomap.planetiler.stats.Counter;
import com.onthegomap.planetiler.stats.ProcessInfo;
import com.onthegomap.planetiler.stats.ProgressLoggers;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.stats.Timer;
import com.onthegomap.planetiler.util.DiskBacked;
import com.onthegomap.planetiler.util.Format;
import com.onthegomap.planetiler.util.Hashing;
import com.onthegomap.planetiler.worker.WorkQueue;
import com.onthegomap.planetiler.worker.Worker;
import com.onthegomap.planetiler.worker.WorkerPipeline;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Final stage of the map generation process that encodes vector tiles using {@link VectorTile} and writes them to a
 * {@link WriteableTileArchive}.
 */
public class TileArchiveWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(TileArchiveWriter.class);
  private static final long MAX_FEATURES_PER_BATCH = 10_000;
  private static final long MAX_TILES_PER_BATCH = 1_000;
  private final Counter.Readable featuresProcessed;
  private final Counter memoizedTiles;
  private final WriteableTileArchive archive;
  private final PlanetilerConfig config;
  private final Stats stats;
  private final Counter.Readable[] tilesByZoom;
  private final Counter.Readable[] totalTileSizesByZoom;
  private final LongAccumulator[] maxTileSizesByZoom;
  private final Iterable<FeatureGroup.TileFeatures> inputTiles;
  private final AtomicReference<TileCoord> lastTileWritten = new AtomicReference<>();
  private final TileArchiveMetadata tileArchiveMetadata;

  private TileArchiveWriter(Iterable<FeatureGroup.TileFeatures> inputTiles, WriteableTileArchive archive,
    PlanetilerConfig config, TileArchiveMetadata tileArchiveMetadata, Stats stats) {
    this.inputTiles = inputTiles;
    this.archive = archive;
    this.config = config;
    this.tileArchiveMetadata = tileArchiveMetadata;
    this.stats = stats;
    tilesByZoom = IntStream.rangeClosed(0, config.maxzoom())
      .mapToObj(i -> Counter.newSingleThreadCounter())
      .toArray(Counter.Readable[]::new);
    totalTileSizesByZoom = IntStream.rangeClosed(0, config.maxzoom())
      .mapToObj(i -> Counter.newMultiThreadCounter())
      .toArray(Counter.Readable[]::new);
    maxTileSizesByZoom = IntStream.rangeClosed(0, config.maxzoom())
      .mapToObj(i -> new LongAccumulator(Long::max, 0))
      .toArray(LongAccumulator[]::new);
    memoizedTiles = stats.longCounter("archive_memoized_tiles");
    featuresProcessed = stats.longCounter("archive_features_processed");
    Map<String, LongSupplier> countsByZoom = new LinkedHashMap<>();
    for (int zoom = config.minzoom(); zoom <= config.maxzoom(); zoom++) {
      countsByZoom.put(Integer.toString(zoom), tilesByZoom[zoom]);
    }
    stats.counter("archive_tiles_written", "zoom", () -> countsByZoom);
  }

  /** Reads all {@code features}, encodes them in parallel, and writes to {@code output}. */
  public static void writeOutput(FeatureGroup features, WriteableTileArchive output, DiskBacked fileSize,
    TileArchiveMetadata tileArchiveMetadata, PlanetilerConfig config, Stats stats) {
    var timer = stats.startStage("archive");

    int readThreads = config.featureReadThreads();
    int threads = config.threads();
    int processThreads = threads < 10 ? threads : threads - readThreads;

    // when using more than 1 read thread: (N read threads) -> (1 merge thread) -> ...
    // when using 1 read thread we just have: (1 read & merge thread) -> ...
    Worker readWorker = null;
    Iterable<FeatureGroup.TileFeatures> inputTiles;
    String secondStageName;
    if (readThreads == 1) {
      secondStageName = "read";
      inputTiles = features;
    } else {
      secondStageName = "merge";
      var reader = features.parallelIterator(readThreads);
      inputTiles = reader.result();
      readWorker = reader.readWorker();
    }

    TileArchiveWriter writer =
      new TileArchiveWriter(inputTiles, output, config, tileArchiveMetadata.withLayerStats(features.layerStats()
        .getTileStats()), stats);

    var pipeline = WorkerPipeline.start("archive", stats);

    // a larger tile queue size helps keep cores busy, but needs a lot of RAM
    // 5k works fine with 100GB of RAM, so adjust the queue size down from there
    // but no less than 100
    int queueSize = Math.max(
      100,
      (int) (5_000d * ProcessInfo.getMaxMemoryBytes() / 100_000_000_000d)
    );

    WorkerPipeline<TileBatch> encodeBranch, writeBranch = null;

    /*
     * To emit tiles in order, fork the input queue and send features to both the encoder and writer. The writer
     * waits on them to be encoded in the order they were received, and the encoder processes them in parallel.
     * One batch might take a long time to process, so make the queues very big to avoid idle encoding CPUs.
     */
    WorkQueue<TileBatch> writerQueue = new WorkQueue<>("archive_writer_queue", queueSize, 1, stats);
    encodeBranch = pipeline
      .<TileBatch>fromGenerator(secondStageName, next -> {
        var writerEnqueuer = writerQueue.threadLocalWriter();
        writer.readFeaturesAndBatch(batch -> {
          next.accept(batch);
          writerEnqueuer.accept(batch); // also send immediately to writer
        });
        writerQueue.close();
        // use only 1 thread since readFeaturesAndBatch needs to be single-threaded
      }, 1)
      .addBuffer("reader_queue", queueSize)
      .sinkTo("encode", processThreads, writer::tileEncoderSink);

    // the tile writer will wait on the result of each batch to ensure tiles are written in order
    writeBranch = pipeline.readFromQueue(writerQueue)
      // use only 1 thread since tileWriter needs to be single-threaded
      .sinkTo("write", 1, writer::tileWriter);

    var loggers = ProgressLoggers.create()
      .addRatePercentCounter("features", features.numFeaturesWritten(), writer.featuresProcessed, true)
      .addFileSize(features)
      .addRateCounter("tiles", writer::tilesEmitted)
      .addFileSize(fileSize)
      .newLine()
      .addProcessStats()
      .newLine();
    if (readWorker != null) {
      loggers.addThreadPoolStats("read", readWorker);
    }
    loggers.addPipelineStats(encodeBranch)
      .addPipelineStats(writeBranch)
      .newLine()
      .add(writer::getLastTileLogDetails);

    var doneFuture = writeBranch == null ? encodeBranch.done() : joinFutures(writeBranch.done(), encodeBranch.done());
    loggers.awaitAndLog(doneFuture, config.logInterval());
    writer.printTileStats();
    timer.stop();
  }

  private String getLastTileLogDetails() {
    TileCoord lastTile = lastTileWritten.get();
    String blurb;
    if (lastTile == null) {
      blurb = "n/a";
    } else {
      blurb = "%d/%d/%d (z%d %s) %s".formatted(
        lastTile.z(), lastTile.x(), lastTile.y(),
        lastTile.z(),
        Format.defaultInstance().percent(archive.tileOrder().progressOnLevel(lastTile, config.bounds().tileExtents())),
        lastTile.getDebugUrl()
      );
    }
    return "last tile: " + blurb;
  }

  private void readFeaturesAndBatch(Consumer<TileBatch> next) {
    int currentZoom = Integer.MIN_VALUE;
    TileBatch batch = new TileBatch();
    long featuresInThisBatch = 0;
    long tilesInThisBatch = 0;
    for (var feature : inputTiles) {
      int z = feature.tileCoord().z();
      if (z != currentZoom) {
        LOGGER.trace("Starting z{}", z);
        currentZoom = z;
      }
      long thisTileFeatures = feature.getNumFeaturesToEmit();
      if (tilesInThisBatch > 0 &&
        (tilesInThisBatch >= MAX_TILES_PER_BATCH ||
          ((featuresInThisBatch + thisTileFeatures) > MAX_FEATURES_PER_BATCH))) {
        next.accept(batch);
        batch = new TileBatch();
        featuresInThisBatch = 0;
        tilesInThisBatch = 0;
      }
      featuresInThisBatch += thisTileFeatures;
      tilesInThisBatch++;
      batch.in.add(feature);
    }
    if (!batch.in.isEmpty()) {
      next.accept(batch);
    }
  }

  private void tileEncoderSink(Iterable<TileBatch> prev) throws IOException {
    tileEncoder(prev, batch -> {
      // no next step
    });
  }

  private void tileEncoder(Iterable<TileBatch> prev, Consumer<TileBatch> next) throws IOException {
    /*
     * To optimize emitting many identical consecutive tiles (like large ocean areas), memoize output to avoid
     * recomputing if the input hasn't changed.
     */
    byte[] lastBytes = null, lastEncoded = null;
    Long lastTileDataHash = null;
    boolean lastIsFill = false;
    boolean skipFilled = config.skipFilledTiles();

    for (TileBatch batch : prev) {
      Queue<TileEncodingResult> result = new ArrayDeque<>(batch.size());
      FeatureGroup.TileFeatures last = null;
      // each batch contains tile ordered by z asc, x asc, y desc
      for (int i = 0; i < batch.in.size(); i++) {
        FeatureGroup.TileFeatures tileFeatures = batch.in.get(i);
        featuresProcessed.incBy(tileFeatures.getNumFeaturesProcessed());
        byte[] bytes, encoded;
        Long tileDataHash;
        if (tileFeatures.hasSameContents(last)) {
          bytes = lastBytes;
          encoded = lastEncoded;
          tileDataHash = lastTileDataHash;
          memoizedTiles.inc();
        } else {
          VectorTile en = tileFeatures.getVectorTileEncoder();
          if (skipFilled && (lastIsFill = en.containsOnlyFills())) {
            encoded = null;
            bytes = null;
          } else {
            encoded = en.encode();
            bytes = gzip(encoded);
            if (encoded.length > config.tileWarningSizeBytes()) {
              LOGGER.warn("{} {}kb uncompressed",
                tileFeatures.tileCoord(),
                encoded.length / 1024);
            }
          }
          lastEncoded = encoded;
          lastBytes = bytes;
          last = tileFeatures;
          if (archive.deduplicates() && en.likelyToBeDuplicated() && bytes != null) {
            tileDataHash = generateContentHash(bytes);
          } else {
            tileDataHash = null;
          }
          lastTileDataHash = tileDataHash;
        }
        if (skipFilled && lastIsFill) {
          continue;
        }
        int zoom = tileFeatures.tileCoord().z();
        int encodedLength = encoded == null ? 0 : encoded.length;
        totalTileSizesByZoom[zoom].incBy(encodedLength);
        maxTileSizesByZoom[zoom].accumulate(encodedLength);
        result.add(
          new TileEncodingResult(tileFeatures.tileCoord(), bytes,
            tileDataHash == null ? OptionalLong.empty() : OptionalLong.of(tileDataHash))
        );
      }
      // hand result off to writer
      batch.out.complete(result);
      next.accept(batch);
    }
  }

  private void tileWriter(Iterable<TileBatch> tileBatches) throws ExecutionException, InterruptedException {

    archive.initialize(tileArchiveMetadata);
    var order = archive.tileOrder();

    TileCoord lastTile = null;
    Timer time = null;
    int currentZ = Integer.MIN_VALUE;
    try (var tileWriter = archive.newTileWriter()) {
      for (TileBatch batch : tileBatches) {
        Queue<TileEncodingResult> encodedTiles = batch.out.get();
        TileEncodingResult encodedTile;
        while ((encodedTile = encodedTiles.poll()) != null) {
          TileCoord tileCoord = encodedTile.coord();
          assert lastTile == null ||
            order.encode(tileCoord) > order.encode(lastTile) : "Tiles out of order %s before %s"
              .formatted(lastTile, tileCoord);
          lastTile = encodedTile.coord();
          int z = tileCoord.z();
          if (z != currentZ) {
            if (time == null) {
              LOGGER.info("Starting z{}", z);
            } else {
              LOGGER.info("Finished z{} in {}, now starting z{}", currentZ, time.stop(), z);
            }
            time = Timer.start();
            currentZ = z;
          }
          tileWriter.write(encodedTile);

          stats.wroteTile(z, encodedTile.tileData() == null ? 0 : encodedTile.tileData().length);
          tilesByZoom[z].inc();
        }
        lastTileWritten.set(lastTile);
      }
      tileWriter.printStats();
    }

    if (time != null) {
      LOGGER.info("Finished z{} in {}", currentZ, time.stop());
    }


    archive.finish(tileArchiveMetadata);
  }

  private void printTileStats() {
    if (LOGGER.isDebugEnabled()) {
      Format format = Format.defaultInstance();
      LOGGER.debug("Tile stats:");
      long sumSize = 0;
      long sumCount = 0;
      long maxMax = 0;
      for (int z = config.minzoom(); z <= config.maxzoom(); z++) {
        long totalCount = tilesByZoom[z].get();
        long totalSize = totalTileSizesByZoom[z].get();
        sumSize += totalSize;
        sumCount += totalCount;
        long maxSize = maxTileSizesByZoom[z].get();
        maxMax = Math.max(maxMax, maxSize);
        LOGGER.debug("z{} avg:{} max:{}",
          z,
          format.storage(totalCount == 0 ? 0 : (totalSize / totalCount), false),
          format.storage(maxSize, false));
      }
      LOGGER.debug("all avg:{} max:{}",
        format.storage(sumCount == 0 ? 0 : (sumSize / sumCount), false),
        format.storage(maxMax, false));
      LOGGER.debug(" # features: {}", format.integer(featuresProcessed.get()));
      LOGGER.debug("    # tiles: {}", format.integer(this.tilesEmitted()));
    }
  }

  private long tilesEmitted() {
    return Stream.of(tilesByZoom).mapToLong(c -> c.get()).sum();
  }

  /**
   * Generates a hash over encoded and compressed tile.
   * <p>
   * Used as an optimization to avoid writing the same (mostly ocean) tiles over and over again.
   */
  public static long generateContentHash(byte[] bytes) {
    return Hashing.fnv1a64(bytes);
  }

  /**
   * Container for a batch of tiles to be processed together in the encoder and writer threads.
   * <p>
   * The cost of encoding a tile may vary dramatically by its size (depending on the profile) so batches are sized
   * dynamically to put as little as 1 large tile, or as many as 10,000 small tiles in a batch to keep encoding threads
   * busy.
   *
   * @param in  the tile data to encode
   * @param out the future that encoder thread completes to hand finished tile off to writer thread
   */
  private record TileBatch(
    List<FeatureGroup.TileFeatures> in,
    CompletableFuture<Queue<TileEncodingResult>> out
  ) {

    TileBatch() {
      this(new ArrayList<>(), new CompletableFuture<>());
    }

    public int size() {
      return in.size();
    }

    public boolean isEmpty() {
      return in.isEmpty();
    }
  }
}
