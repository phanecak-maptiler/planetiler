package com.onthegomap.planetiler.geo;

import static com.onthegomap.planetiler.TestUtils.*;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.AffineTransformation;

class VWSimplifierTest {

  final int[] rotations = new int[]{0, 45, 90, 180, 270};

  private void testSimplify(Geometry in, Geometry expected, double amount) {
    for (int rotation : rotations) {
      var rotate = AffineTransformation.rotationInstance(Math.PI * rotation / 180);
      assertSameNormalizedFeature(
        rotate.transform(expected),
        new VWSimplifier().setTolerance(amount).setWeight(0).transform(rotate.transform(in))
      );
      assertSameNormalizedFeature(
        rotate.transform(expected.reverse()),
        new VWSimplifier().setTolerance(amount).setWeight(0).transform(rotate.transform(in.reverse()))
      );
    }
  }

  @Test
  void testSimplifyPoint() {
    testSimplify(newPoint(1, 1), newPoint(1, 1), 1);
  }

  @Test
  void testSimplify2Points() {
    testSimplify(newLineString(
      0, 0,
      10, 10
    ), newLineString(
      0, 0,
      10, 10
    ), 1);
  }

  @Test
  void testRemoveAPoint() {
    testSimplify(newLineString(
      0, 0,
      5, 0.9,
      10, 0
    ), newLineString(
      0, 0,
      10, 0
    ), 5);
  }

  @Test
  void testKeepAPoint() {
    testSimplify(newLineString(
      0, 0,
      5, 1.1,
      10, 0
    ), newLineString(
      0, 0,
      5, 1.1,
      10, 0
    ), 5);
  }

  @Test
  void testPolygonLeaveAPointWhenAreaLessThenTolerance() {
    Geometry in = newPolygon(
      0, 0,
      10, 10,
      9, 10,
      0, 8,
      0, 0
    );
    assertSameNormalizedFeature(
      emptyGeometry(),
      new VWSimplifier().setTolerance(200).setWeight(0).transform(in)
    );
    assertSameNormalizedFeature(
      newPolygon(
        0, 0,
        0, 8,
        10, 10,
        0, 0
      ),
      new VWSimplifier().setTolerance(200).setKeepCollapsed(true).setWeight(0).transform(in)
    );
  }

  @Test
  void testLine() {
    testSimplify(
      newLineString(
        0, 0,
        1, 0.1,
        2, 0,
        3, 0.1
      ),
      newLineString(
        0, 0,
        1, 0.1,
        2, 0,
        3, 0.1
      ),
      0.09
    );
    testSimplify(
      newLineString(
        0, 0,
        1, 0.1,
        2, 0,
        3, 0.1
      ),
      newLineString(
        0, 0,
        3, 0.1
      ),
      0.11
    );
  }
}
