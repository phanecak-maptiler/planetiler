package com.onthegomap.planetiler.geo;

import com.carrotsearch.hppc.DoubleArrayList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;

/**
 * A 2-dimensional {@link CoordinateSequence} implementation backed by a {@link DoubleArrayList} that supports adding
 * points.
 * <p>
 * Reads are thread-safe but writes are not.
 */
public class MutableCoordinateSequence extends PackedCoordinateSequence {

  private final DoubleArrayList points;

  public MutableCoordinateSequence() {
    this(2);
  }

  public MutableCoordinateSequence(int size) {
    super(2, 0);
    points = new DoubleArrayList(2 * size);
  }

  /**
   * Returns a coordinate sequence that translates and scales coordinates on insertion.
   *
   * @param relX  x origin of the translation
   * @param relY  y origin of the translation
   * @param scale amount to scale by
   * @return the new empty coordinate sequence
   */
  public static MutableCoordinateSequence newScalingSequence(double relX, double relY, double scale) {
    return new ScalingSequence(scale, relX, relY);
  }

  @Override
  public double getOrdinate(int index, int ordinateIndex) {
    return points.get((index * 2) + ordinateIndex);
  }

  @Override
  public int size() {
    return points.size() >> 1;
  }

  @Override
  protected Coordinate getCoordinateInternal(int index) {
    return new CoordinateXY(getX(index), getY(index));
  }

  @Override
  @Deprecated
  public Object clone() {
    throw new UnsupportedOperationException();
  }

  @Override
  public PackedCoordinateSequence copy() {
    return new PackedCoordinateSequence.Double(points.toArray(), dimension, measures);
  }

  @Override
  public void setOrdinate(int index, int ordinate, double value) {
    points.set((index << 1) + ordinate, value);
  }

  @Override
  public Envelope expandEnvelope(Envelope env) {
    for (int i = 0; i < points.size(); i += dimension) {
      env.expandToInclude(points.get(i), points.get(i + 1));
    }
    return env;
  }

  /** Adds a coordinate to the sequence as long as it is different from the current endpoint. */
  public void addPoint(double x, double y) {
    int size = size();
    if (size == 0 || getX(size - 1) != x || getY(size - 1) != y) {
      points.add(x, y);
    }
  }

  /** Adds a coordinate to the sequence even if it is the same as the current endpoint. */
  public void forceAddPoint(double x, double y) {
    points.add(x, y);
  }

  /** Adds the starting coordinate to the end of this sequence if it has not already been added. */
  public void closeRing() {
    int size = size();
    if (size >= 1) {
      double firstX = getX(0);
      double firstY = getY(0);
      double lastX = getX(size - 1);
      double lastY = getY(size - 1);
      if (firstX != lastX || firstY != lastY) {
        points.add(firstX, firstY);
      }
    }
  }

  public boolean isEmpty() {
    return points.isEmpty();
  }

  /** Implementation that transforms and scales coordinates on insert. */
  private static class ScalingSequence extends MutableCoordinateSequence {

    private final double scale;
    private final double relX;
    private final double relY;

    public ScalingSequence(double scale, double relX, double relY) {
      this.scale = scale;
      this.relX = relX;
      this.relY = relY;
    }

    @Override
    public void addPoint(double x, double y) {
      super.addPoint(scale * (x - relX), scale * (y - relY));
    }
  }
}
