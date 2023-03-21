package com.onthegomap.planetiler.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntRangeSetTest {

  private static List<Integer> getInts(IntRangeSet range) {
    List<Integer> result = new ArrayList<>();
    for (Integer integer : range) {
      result.add(integer);
    }
    return result;
  }

  @Test
  void testEmptyIntRange() {
    IntRangeSet range = new IntRangeSet();
    assertEquals(List.of(), getInts(range));
  }

  @Test
  void testSingleIntRange() {
    IntRangeSet range = new IntRangeSet();
    range.add(1, 1);
    assertEquals(List.of(1), getInts(range));
  }

  @Test
  void testLongerIntRange() {
    IntRangeSet range = new IntRangeSet();
    range.add(1, 3);
    assertEquals(List.of(1, 2, 3), getInts(range));
  }

  @Test
  void testTwoIntRanges() {
    IntRangeSet range = new IntRangeSet();
    range.add(1, 3);
    range.add(5, 7);
    assertEquals(List.of(1, 2, 3, 5, 6, 7), getInts(range));
  }

  @Test
  void testTwoOverlappingIntRanges() {
    IntRangeSet range = new IntRangeSet();
    range.add(1, 5);
    range.add(4, 7);
    assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), getInts(range));
  }

  @Test
  void testRemoveSingle() {
    IntRangeSet range = new IntRangeSet();
    range.add(1, 5);
    range.remove(3);
    assertEquals(List.of(1, 2, 4, 5), getInts(range));
  }

  @Test
  void testRemoveOtherRange() {
    IntRangeSet range = new IntRangeSet();
    range.add(1, 5);
    IntRangeSet range2 = new IntRangeSet();
    range2.add(4, 6);
    range.removeAll(range2);
    assertEquals(List.of(1, 2, 3), getInts(range));
  }

  @Test
  void testAddOtherRange() {
    IntRangeSet range = new IntRangeSet();
    range.add(1, 5);
    IntRangeSet range2 = new IntRangeSet();
    range2.add(8, 10);
    range.addAll(range2);
    assertEquals(List.of(1, 2, 3, 4, 5, 8, 9, 10), getInts(range));
  }

  @Test
  void testContains() {
    IntRangeSet range = new IntRangeSet();
    range.add(1, 5);
    IntRangeSet range2 = new IntRangeSet();
    range2.add(8, 10);
    range.addAll(range2);
    range.remove(3);

    var expected = Map.ofEntries(
      Map.entry(1, true),
      Map.entry(2, true),
      Map.entry(3, false),
      Map.entry(4, true),
      Map.entry(5, true),
      Map.entry(6, false),
      Map.entry(7, false),
      Map.entry(8, true),
      Map.entry(9, true),
      Map.entry(10, true),
      Map.entry(11, false)
    );

    expected.forEach((num, val) -> assertEquals(val, range.contains(num), (val ? "" : "!") + num));
  }

  @Test
  void testNoIntersection() {
    IntRangeSet range = new IntRangeSet();
    range.add(1, 5);
    IntRangeSet range2 = new IntRangeSet();
    range2.add(8, 10);
    range.intersect(range2);
    assertEquals(List.of(), getInts(range));
  }

  @Test
  void testSingleIntersection() {
    IntRangeSet range = new IntRangeSet();
    range.add(1, 5);
    IntRangeSet range2 = new IntRangeSet();
    range2.add(5, 10);
    range.intersect(range2);
    assertEquals(List.of(5), getInts(range));
  }

  @Test
  void testMultipleIntersection() {
    IntRangeSet range = new IntRangeSet();
    range.add(1, 5);
    IntRangeSet range2 = new IntRangeSet();
    range2.add(3, 10);
    range2.remove(4);
    range.intersect(range2);
    assertEquals(List.of(3, 5), getInts(range));
  }

  @Test
  void testIsEmpty() {
    IntRangeSet range = new IntRangeSet();
    assertTrue(range.isEmpty());
    range.add(1, 5);
    assertFalse(range.isEmpty());
    var other = new IntRangeSet();
    other.add(6, 10);
    range.intersect(other);
    assertTrue(range.isEmpty());
  }

  @Test
  void testXor() {
    IntRangeSet range = new IntRangeSet();
    assertEquals(List.of(), getInts(range));
    range.xor(1, 5);
    assertEquals(List.of(1, 2, 3, 4, 5), getInts(range));
    range.xor(1, 5);
    assertEquals(List.of(), getInts(range));
    range.xor(1, 5);
    assertEquals(List.of(1, 2, 3, 4, 5), getInts(range));
    range.xor(3, 6);
    assertEquals(List.of(1, 2, 6), getInts(range));
  }
}
