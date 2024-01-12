package com.onthegomap.planetiler.util;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.LongConsumer;

/**
 * {@link OutputStream} decorator that notifies the callback about the written bytes.
 */
public class CountingOutputStream extends DelegatingOutputStream {

  private final LongConsumer writtenBytesConsumer;

  public CountingOutputStream(OutputStream wrapped, LongConsumer writtenBytesConsumer) {
    super(wrapped);
    this.writtenBytesConsumer = writtenBytesConsumer;
  }

  @Override
  public void write(int i) throws IOException {
    super.write(i);
    writtenBytesConsumer.accept(1L);
  }

  @Override
  public void write(byte[] b) throws IOException {
    super.write(b);
    writtenBytesConsumer.accept(b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    super.write(b, off, len);
    writtenBytesConsumer.accept(len);
  }
}
