package com.onthegomap.planetiler.reader.parquet;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongFunction;
import org.apache.parquet.column.Dictionary;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.PrimitiveConverter;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveType;

/**
 * Converts typed primitive values from parquet records to java objects:
 *
 * <ul>
 * <li>{@link PrimitiveType.PrimitiveTypeName#FLOAT} -> {@link Float}
 * <li>{@link PrimitiveType.PrimitiveTypeName#DOUBLE} -> {@link Double}
 * <li>{@link PrimitiveType.PrimitiveTypeName#INT32} -> {@link Integer}
 * <li>{@link PrimitiveType.PrimitiveTypeName#INT64} -> {@link Long}
 * <li>{@link PrimitiveType.PrimitiveTypeName#BOOLEAN} -> {@link Boolean}
 * <li>{@link PrimitiveType.PrimitiveTypeName#INT96} -> {@link Instant}
 * <li>{@link LogicalTypeAnnotation.DateLogicalTypeAnnotation} -> {@link LocalDate}
 * <li>{@link LogicalTypeAnnotation.TimeLogicalTypeAnnotation} -> {@link LocalTime}
 * <li>{@link LogicalTypeAnnotation.TimestampLogicalTypeAnnotation} -> {@link Instant}
 * <li>{@link LogicalTypeAnnotation.UUIDLogicalTypeAnnotation} -> {@link UUID}
 * <li>{@link LogicalTypeAnnotation.DecimalLogicalTypeAnnotation} -> {@link Double}
 * <li>{@link LogicalTypeAnnotation.StringLogicalTypeAnnotation} -> {@link String}
 * <li>{@link LogicalTypeAnnotation.JsonLogicalTypeAnnotation} -> {@link String}
 * <li>{@link LogicalTypeAnnotation.EnumLogicalTypeAnnotation} -> {@link String}
 * <li>{@link PrimitiveType.PrimitiveTypeName#BINARY} -> {@code byte[]}
 * </ul>
 */
class ParquetPrimitiveConverter extends PrimitiveConverter {
  private final PrimitiveType.PrimitiveTypeName primitiveType;
  private final ParquetRecordConverter.Context context;
  private Dictionary dictionary;

  ParquetPrimitiveConverter(ParquetRecordConverter.Context context) {
    this.context = context;
    this.primitiveType = context.type.asPrimitiveType().getPrimitiveTypeName();
  }

  static ParquetPrimitiveConverter of(ParquetRecordConverter.Context context) {
    var primitiveType = context.type().asPrimitiveType().getPrimitiveTypeName();
    return switch (primitiveType) {
      case FLOAT, DOUBLE, BOOLEAN -> new ParquetPrimitiveConverter(context);
      case INT64, INT32 -> switch (context.type().getLogicalTypeAnnotation()) {
        case null -> new ParquetPrimitiveConverter(context);
        case LogicalTypeAnnotation.IntLogicalTypeAnnotation ignored ->
          new ParquetPrimitiveConverter(context);
        case LogicalTypeAnnotation.DecimalLogicalTypeAnnotation decimal -> {
          var multiplier = Math.pow(10, -decimal.getScale());
          yield new IntegerConverter(context, value -> multiplier * value);
        }
        case LogicalTypeAnnotation.DateLogicalTypeAnnotation ignored ->
          new IntegerConverter(context, LocalDate::ofEpochDay);
        case LogicalTypeAnnotation.TimeLogicalTypeAnnotation time -> {
          var unit = getUnit(time.getUnit());
          yield new IntegerConverter(context, value -> LocalTime.ofNanoOfDay(Duration.of(value, unit).toNanos()));
        }
        case LogicalTypeAnnotation.TimestampLogicalTypeAnnotation timestamp -> {
          var unit = getUnit(timestamp.getUnit());
          yield new IntegerConverter(context, value -> Instant.ofEpochMilli(Duration.of(value, unit).toMillis()));
        }
        default -> throw new UnsupportedOperationException(
          "Unsupported logical type for " + primitiveType + ": " + context.type().getLogicalTypeAnnotation());
      };
      case INT96 -> new BinaryConverer(context, value -> {
        var buf = value.toByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        LocalTime timeOfDay = LocalTime.ofNanoOfDay(buf.getLong());
        LocalDate day = LocalDate.ofEpochDay(buf.getInt() - 2440588L);
        return LocalDateTime.of(day, timeOfDay).toInstant(ZoneOffset.UTC);
      });
      case FIXED_LEN_BYTE_ARRAY, BINARY -> switch (context.type().getLogicalTypeAnnotation()) {
        case LogicalTypeAnnotation.UUIDLogicalTypeAnnotation ignored -> new BinaryConverer(context, binary -> {
          ByteBuffer byteBuffer = binary.toByteBuffer();
          long msb = byteBuffer.getLong();
          long lsb = byteBuffer.getLong();
          return new UUID(msb, lsb);
        });
        case LogicalTypeAnnotation.IntervalLogicalTypeAnnotation ignored -> new BinaryConverer(context, binary -> {
          ByteBuffer byteBuffer = binary.toByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
          int months = byteBuffer.getInt();
          int days = byteBuffer.getInt();
          int millis = byteBuffer.getInt();
          return new Interval(Period.ofMonths(months).plusDays(days), Duration.ofMillis(millis));
        });
        case LogicalTypeAnnotation.DecimalLogicalTypeAnnotation decimal -> {
          int scale = -decimal.getScale();
          yield new BinaryConverer(context,
            binary -> new BigDecimal(new BigInteger(binary.getBytes()), scale).doubleValue());
        }
        case LogicalTypeAnnotation.StringLogicalTypeAnnotation ignored ->
          new BinaryConverer(context, Binary::toStringUsingUTF8);
        case LogicalTypeAnnotation.EnumLogicalTypeAnnotation ignored ->
          new BinaryConverer(context, Binary::toStringUsingUTF8);
        case LogicalTypeAnnotation.JsonLogicalTypeAnnotation ignores ->
          new BinaryConverer(context, Binary::toStringUsingUTF8);
        case null, default -> new ParquetPrimitiveConverter(context);
      };
    };
  }

  private static ChronoUnit getUnit(LogicalTypeAnnotation.TimeUnit unit) {
    return switch (unit) {
      case MILLIS -> ChronoUnit.MILLIS;
      case MICROS -> ChronoUnit.MICROS;
      case NANOS -> ChronoUnit.NANOS;
    };
  }

  void add(Object value) {
    context.accept(value);
  }

  @Override
  public void addFloat(float value) {
    add((double) value);
  }

  @Override
  public void addDouble(double value) {
    add(value);
  }

  @Override
  public void addInt(int value) {
    add(value);
  }

  @Override
  public void addLong(long value) {
    add(value);
  }

  @Override
  public void addBoolean(boolean value) {
    add(value);
  }

  @Override
  public void addBinary(Binary value) {
    add(value.getBytes());
  }

  @Override
  public void addValueFromDictionary(int idx) {
    switch (primitiveType) {
      case INT64 -> addLong(dictionary.decodeToLong(idx));
      case INT32 -> addInt(dictionary.decodeToInt(idx));
      case BOOLEAN -> addBoolean(dictionary.decodeToBoolean(idx));
      case FLOAT -> addFloat(dictionary.decodeToFloat(idx));
      case DOUBLE -> addDouble(dictionary.decodeToDouble(idx));
      case BINARY, FIXED_LEN_BYTE_ARRAY, INT96 -> addBinary(dictionary.decodeToBinary(idx));
    }
  }

  @Override
  public void setDictionary(Dictionary dictionary) {
    this.dictionary = dictionary;
  }

  @Override
  public boolean hasDictionarySupport() {
    return true;
  }

  private static class BinaryConverer extends ParquetPrimitiveConverter {

    private final Function<Binary, ?> remapper;

    BinaryConverer(ParquetRecordConverter.Context context, Function<Binary, ?> remapper) {
      super(context);
      this.remapper = remapper;
    }

    @Override
    public void addBinary(Binary value) {
      add(remapper.apply(value));
    }
  }


  private static class IntegerConverter extends ParquetPrimitiveConverter {
    private final LongFunction<?> remapper;

    IntegerConverter(ParquetRecordConverter.Context context, LongFunction<?> remapper) {
      super(context);
      this.remapper = remapper;
    }

    @Override
    public void addLong(long value) {
      add(remapper.apply(value));
    }

    @Override
    public void addInt(int value) {
      addLong(value);
    }
  }
}
