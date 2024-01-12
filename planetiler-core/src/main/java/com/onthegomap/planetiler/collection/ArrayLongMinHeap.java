/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.onthegomap.planetiler.collection;

import java.util.Arrays;

/**
 * A min-heap stored in an array where each element has 4 children.
 * <p>
 * This is about 5-10% faster than the standard binary min-heap for the case of merging sorted lists.
 * <p>
 * Ported from <a href=
 * "https://github.com/graphhopper/graphhopper/blob/master/core/src/main/java/com/graphhopper/coll/MinHeapWithUpdate.java">GraphHopper</a>
 * and:
 * <ul>
 * <li>modified to use {@code long} values instead of {@code float}</li>
 * <li>extracted a common interface for subclass implementations</li>
 * <li>modified so that each element has 4 children instead of 2 (improves performance by 5-10%)</li>
 * <li>performance improvements to minimize array lookups</li>
 * </ul>
 *
 * @see <a href="https://en.wikipedia.org/wiki/D-ary_heap">d-ary heap (wikipedia)</a>
 */
class ArrayLongMinHeap implements LongMinHeap {
  protected static final int NOT_PRESENT = -1;
  protected final int[] tree;
  protected final int[] positions;
  protected final long[] vals;
  protected final int max;
  protected int size;

  /**
   * @param elements the number of elements that can be stored in this heap. Currently the heap cannot be resized or
   *                 shrunk/trimmed after initial creation. elements-1 is the maximum id that can be stored in this heap
   */
  ArrayLongMinHeap(int elements) {
    // we use an offset of one to make the arithmetic a bit simpler/more efficient, the 0th elements are not used!
    tree = new int[elements + 1];
    positions = new int[elements + 1];
    Arrays.fill(positions, NOT_PRESENT);
    vals = new long[elements + 1];
    vals[0] = Long.MIN_VALUE;
    this.max = elements;
  }

  private static int firstChild(int index) {
    return (index << 2) - 2;
  }

  private static int parent(int index) {
    return (index + 2) >> 2;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public void push(int id, long value) {
    checkIdInRange(id);
    if (size == max) {
      throw new IllegalStateException("Cannot push anymore, the heap is already full. size: " + size);
    }
    if (contains(id)) {
      throw new IllegalStateException("Element with id: " + id +
        " was pushed already, you need to use the update method if you want to change its value");
    }
    size++;
    tree[size] = id;
    positions[id] = size;
    vals[size] = value;
    percolateUp(size);
  }

  @Override
  public boolean contains(int id) {
    checkIdInRange(id);
    return positions[id] != NOT_PRESENT;
  }

  @Override
  public void update(int id, long value) {
    checkIdInRange(id);
    int index = positions[id];
    if (index < 0) {
      throw new IllegalStateException(
        "The heap does not contain: " + id + ". Use the contains method to check this before calling update");
    }
    long prev = vals[index];
    vals[index] = value;
    if (value > prev) {
      percolateDown(index);
    } else if (value < prev) {
      percolateUp(index);
    }
  }

  @Override
  public void updateHead(long value) {
    vals[1] = value;
    percolateDown(1);
  }

  @Override
  public int peekId() {
    return tree[1];
  }

  @Override
  public long peekValue() {
    return vals[1];
  }

  @Override
  public int poll() {
    int id = peekId();
    tree[1] = tree[size];
    vals[1] = vals[size];
    positions[tree[1]] = 1;
    positions[id] = NOT_PRESENT;
    size--;
    percolateDown(1);
    return id;
  }

  @Override
  public void clear() {
    for (int i = 1; i <= size; i++) {
      positions[tree[i]] = NOT_PRESENT;
    }
    size = 0;
  }

  private void percolateUp(int index) {
    assert index != 0;
    if (index == 1) {
      return;
    }
    final int el = tree[index];
    final long val = vals[index];
    // the finish condition (index==0) is covered here automatically because we set vals[0]=-inf
    int parent;
    long parentValue;
    while (val < (parentValue = vals[parent = parent(index)])) {
      vals[index] = parentValue;
      positions[tree[index] = tree[parent]] = index;
      index = parent;
    }
    tree[index] = el;
    vals[index] = val;
    positions[tree[index]] = index;
  }

  private void checkIdInRange(int id) {
    if (id < 0 || id >= max) {
      throw new IllegalArgumentException("Illegal id: " + id + ", legal range: [0, " + max + "[");
    }
  }

  private void percolateDown(int index) {
    if (size == 0) {
      return;
    }
    assert index > 0;
    assert index <= size;
    final int el = tree[index];
    final long val = vals[index];
    int child;
    while ((child = firstChild(index)) <= size) {
      // optimization: this is a very hot code path for performance of k-way merging,
      // so manually-unroll the loop over the 4 child elements to find the minimum value
      int minChild = child;
      long minValue = vals[child], value;
      if (++child <= size) {
        if ((value = vals[child]) < minValue) {
          minChild = child;
          minValue = value;
        }
        if (++child <= size) {
          if ((value = vals[child]) < minValue) {
            minChild = child;
            minValue = value;
          }
          if (++child <= size && (value = vals[child]) < minValue) {
            minChild = child;
            minValue = value;
          }
        }
      }
      if (minValue >= val) {
        break;
      }
      vals[index] = minValue;
      positions[tree[index] = tree[minChild]] = index;
      index = minChild;
    }
    tree[index] = el;
    vals[index] = val;
    positions[el] = index;
  }
}
