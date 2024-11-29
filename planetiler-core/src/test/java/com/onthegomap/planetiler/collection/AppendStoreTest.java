package com.onthegomap.planetiler.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

abstract class AppendStoreTest {

  abstract static class IntsTest {

    protected AppendStore.Ints store;

    @AfterEach
    void close() throws IOException {
      store.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
    void writeThenRead(int num) {
      for (int i = 0; i < num; i++) {
        store.appendInt(i + 1);
      }
      for (int i = 0; i < num; i++) {
        assertEquals(i + 1, store.getInt(i));
      }
      assertThrows(IndexOutOfBoundsException.class, () -> store.getInt(num));
      assertThrows(IndexOutOfBoundsException.class, () -> store.getInt(num + 1));
    }

    @Test
    void readBig() {
      store.appendInt(Integer.MAX_VALUE);
      store.appendInt(Integer.MAX_VALUE - 1);
      store.appendInt(Integer.MAX_VALUE - 2);
      assertEquals(Integer.MAX_VALUE, store.getInt(0));
      assertEquals(Integer.MAX_VALUE - 1, store.getInt(1));
      assertEquals(Integer.MAX_VALUE - 2, store.getInt(2));
    }
  }

  abstract static class LongsTest {

    protected AppendStore.Longs store;

    @AfterEach
    void close() throws IOException {
      store.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
    void writeThenRead(int num) {
      for (int i = 0; i < num; i++) {
        store.appendLong(i + 1);
      }
      for (int i = 0; i < num; i++) {
        assertEquals(i + 1, store.getLong(i));
      }
      assertThrows(IndexOutOfBoundsException.class, () -> store.getLong(num));
      assertThrows(IndexOutOfBoundsException.class, () -> store.getLong(num + 1));
    }

    private static final long MAX_INT = Integer.MAX_VALUE;

    @ParameterizedTest
    @ValueSource(longs = {MAX_INT - 1,
      MAX_INT, MAX_INT + 1, 2 * MAX_INT - 1, 2 * MAX_INT, 5 * MAX_INT - 1, 5 * MAX_INT + 1})
    void readBig(long value) {
      store.appendLong(value);
      assertEquals(value, store.getLong(0));
    }

  }

  static class RamIntTest extends IntsTest {

    @BeforeEach
    void setup() {
      this.store = new AppendStoreRam.Ints(false, 4 << 2);
    }
  }

  static class MMapIntTest extends IntsTest {

    @BeforeEach
    void setup(@TempDir Path path) {
      this.store = new AppendStoreMmap.Ints(path.resolve("ints"), 4 << 2, true);
    }
  }

  static class DirectIntTest extends IntsTest {

    @BeforeEach
    void setup() {
      this.store = new AppendStoreRam.Ints(true, 4 << 2);
    }
  }

  static class RamLongTest extends LongsTest {

    @BeforeEach
    void setup() {
      this.store = new AppendStoreRam.Longs(false, 4 << 2);
    }
  }

  static class MMapLongTest extends LongsTest {

    @BeforeEach
    void setup(@TempDir Path path) {
      this.store = new AppendStoreMmap.Longs(path.resolve("longs"), 4 << 2, true);
    }
  }

  static class DirectLongTest extends LongsTest {

    @BeforeEach
    void setup() {
      this.store = new AppendStoreRam.Longs(true, 4 << 2);
    }
  }

  static class MMapSmallLongTest extends LongsTest {

    @BeforeEach
    void setup(@TempDir Path path) {
      this.store = new AppendStore.SmallLongs(
        i -> new AppendStoreMmap.Ints(path.resolve("smalllongs" + i), 4 << 2, true));
    }
  }

  static class RamSmallLongTest extends LongsTest {

    @BeforeEach
    void setup() {
      this.store = new AppendStore.SmallLongs(i -> new AppendStoreRam.Ints(false, 4 << 2));
    }
  }

  static class DirectSmallLongTest extends LongsTest {

    @BeforeEach
    void setup() {
      this.store = new AppendStore.SmallLongs(i -> new AppendStoreRam.Ints(true, 4 << 2));
    }
  }
}
