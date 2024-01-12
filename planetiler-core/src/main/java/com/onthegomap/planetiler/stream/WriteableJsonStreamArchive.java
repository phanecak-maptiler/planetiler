package com.onthegomap.planetiler.stream;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.onthegomap.planetiler.archive.TileArchiveConfig;
import com.onthegomap.planetiler.archive.TileArchiveMetadata;
import com.onthegomap.planetiler.archive.TileEncodingResult;
import com.onthegomap.planetiler.geo.TileCoord;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes JSON-serialized tile data as well as meta data into file(s). The entries are of type
 * {@link WriteableJsonStreamArchive.Entry} are separated by newline (by default).
 */
public final class WriteableJsonStreamArchive extends WriteableStreamArchive {

  private static final Logger LOGGER = LoggerFactory.getLogger(WriteableJsonStreamArchive.class);

  /**
   * exposing meta data (non-tile data) might be useful for most use cases but complicates parsing for simple use cases
   * => allow to output tiles, only
   */
  private static final String OPTION_WRITE_TILES_ONLY = "tiles_only";

  private static final String OPTION_ROOT_VALUE_SEPARATOR = "root_value_separator";

  static final JsonMapper jsonMapper = JsonMapper.builder()
    .serializationInclusion(Include.NON_ABSENT)
    .addModule(new Jdk8Module())
    .build();

  private final boolean writeTilesOnly;
  private final String rootValueSeparator;

  private WriteableJsonStreamArchive(Path p, StreamArchiveConfig config) {
    super(p, config);
    this.writeTilesOnly = config.moreOptions().getBoolean(OPTION_WRITE_TILES_ONLY, "write tiles, only", false);
    this.rootValueSeparator = StreamArchiveUtils.getEscapedString(config.moreOptions(), TileArchiveConfig.Format.JSON,
      OPTION_ROOT_VALUE_SEPARATOR, "root value separator", "'\\n'", List.of("\n", " "));
  }

  public static WriteableJsonStreamArchive newWriteToFile(Path path, StreamArchiveConfig config) {
    return new WriteableJsonStreamArchive(path, config);
  }

  @Override
  protected TileWriter newTileWriter(OutputStream outputStream) {
    return new JsonTileWriter(outputStream, rootValueSeparator);
  }

  @Override
  public void initialize() {
    if (writeTilesOnly) {
      return;
    }
    writeEntryFlush(new InitializationEntry());
  }

  @Override
  public void finish(TileArchiveMetadata metadata) {
    if (writeTilesOnly) {
      return;
    }
    writeEntryFlush(new FinishEntry(metadata));
  }

  private void writeEntryFlush(Entry entry) {
    try (var out = new OutputStreamWriter(getPrimaryOutputStream(), StandardCharsets.UTF_8.newEncoder())) {
      jsonMapper
        .writerFor(Entry.class)
        .withoutFeatures(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
        .writeValue(out, entry);
      out.write(rootValueSeparator);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static class JsonTileWriter implements TileWriter {

    private final OutputStream outputStream;
    private final SequenceWriter jsonWriter;
    private final String rootValueSeparator;

    JsonTileWriter(OutputStream out, String rootValueSeparator) {
      this.outputStream = new BufferedOutputStream(out);
      this.rootValueSeparator = rootValueSeparator;
      try {
        this.jsonWriter =
          jsonMapper.writerFor(Entry.class).withRootValueSeparator(rootValueSeparator).writeValues(outputStream);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void write(TileEncodingResult encodingResult) {
      final TileCoord coord = encodingResult.coord();
      try {
        jsonWriter.write(new TileEntry(coord.x(), coord.y(), coord.z(), encodingResult.tileData()));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void close() {
      UncheckedIOException flushOrWriteError = null;
      try {
        jsonWriter.flush();
        // jackson only handles newlines between entries but does not append one to the last one
        for (byte b : rootValueSeparator.getBytes(StandardCharsets.UTF_8)) {
          outputStream.write(b);
        }
      } catch (IOException e) {
        LOGGER.warn("failed to finish writing", e);
        flushOrWriteError = new UncheckedIOException(e);
      }

      try {
        jsonWriter.close();
        outputStream.close();
      } catch (IOException e) {
        if (flushOrWriteError != null) {
          e.addSuppressed(flushOrWriteError);
        }
        throw new UncheckedIOException(e);
      }

      if (flushOrWriteError != null) {
        throw flushOrWriteError;
      }
    }
  }


  @JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type")
  @JsonSubTypes({
    @Type(value = TileEntry.class, name = "tile"),
    @Type(value = InitializationEntry.class, name = "initialization"),
    @Type(value = FinishEntry.class, name = "finish")
  })
  sealed interface Entry {

  }


  record TileEntry(int x, int y, int z, byte[] encodedData) implements Entry {

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + Arrays.hashCode(encodedData);
      result = prime * result + Objects.hash(x, y, z);
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof TileEntry)) {
        return false;
      }
      TileEntry other = (TileEntry) obj;
      return Arrays.equals(encodedData, other.encodedData) && x == other.x && y == other.y && z == other.z;
    }

    @Override
    public String toString() {
      return "TileEntry [x=" + x + ", y=" + y + ", z=" + z + ", encodedData=" + Arrays.toString(encodedData) + "]";
    }
  }

  record InitializationEntry() implements Entry {}


  record FinishEntry(TileArchiveMetadata metadata) implements Entry {}

}
