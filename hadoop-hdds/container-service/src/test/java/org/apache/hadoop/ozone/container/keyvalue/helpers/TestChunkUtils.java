/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone.container.keyvalue.helpers;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.hdds.scm.container.common.helpers.StorageContainerException;
import org.apache.hadoop.ozone.common.ChunkBuffer;
import org.apache.hadoop.ozone.common.utils.BufferUtils;
import org.apache.hadoop.ozone.container.common.helpers.ChunkInfo;
import org.apache.ozone.test.GenericTestUtils;

import org.apache.commons.io.FileUtils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.UNABLE_TO_FIND_CHUNK;

import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for {@link ChunkUtils}.
 */
public class TestChunkUtils {

  private static final Logger LOG =
      LoggerFactory.getLogger(TestChunkUtils.class);

  private static final String PREFIX = TestChunkUtils.class.getSimpleName();

  @Test
  public void concurrentReadOfSameFile() throws Exception {
    String s = "Hello World";
    byte[] array = s.getBytes(UTF_8);
    ChunkBuffer data = ChunkBuffer.wrap(ByteBuffer.wrap(array));
    Path tempFile = Files.createTempFile(PREFIX, "concurrent");
    try {
      int len = data.limit();
      int offset = 0;
      File file = tempFile.toFile();
      ChunkUtils.writeData(file, data, offset, len, null, true);
      int threads = 10;
      ExecutorService executor = new ThreadPoolExecutor(threads, threads,
          0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
      AtomicInteger processed = new AtomicInteger();
      AtomicBoolean failed = new AtomicBoolean();
      for (int i = 0; i < threads; i++) {
        final int threadNumber = i;
        executor.execute(() -> {
          try {
            ByteBuffer[] readBuffers = BufferUtils.assignByteBuffers(len, len);
            ChunkUtils.readData(file, readBuffers, offset, len, null);

            // There should be only one element in readBuffers
            Assertions.assertEquals(1, readBuffers.length);
            ByteBuffer readBuffer = readBuffers[0];

            LOG.info("Read data ({}): {}", threadNumber,
                new String(readBuffer.array(), UTF_8));
            if (!Arrays.equals(array, readBuffer.array())) {
              failed.set(true);
            }
            assertEquals(len, readBuffer.remaining());
          } catch (Exception e) {
            LOG.error("Failed to read data ({})", threadNumber, e);
            failed.set(true);
          }
          processed.incrementAndGet();
        });
      }
      try {
        GenericTestUtils.waitFor(() -> processed.get() == threads,
            100, (int) TimeUnit.SECONDS.toMillis(5));
      } finally {
        executor.shutdownNow();
      }
      assertFalse(failed.get());
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  public void concurrentProcessing() throws Exception {
    final int perThreadWait = 1000;
    final int maxTotalWait = 5000;
    int threads = 20;
    List<Path> paths = new LinkedList<>();

    try {
      ExecutorService executor = new ThreadPoolExecutor(threads, threads,
          0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
      AtomicInteger processed = new AtomicInteger();
      for (int i = 0; i < threads; i++) {
        Path path = Files.createTempFile(PREFIX, String.valueOf(i));
        paths.add(path);
        executor.execute(() -> {
          try {
            ChunkUtils.processFileExclusively(path, () -> {
              try {
                Thread.sleep(perThreadWait);
              } catch (InterruptedException e) {
                e.printStackTrace();
              }
              processed.incrementAndGet();
              return null;
            });
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        });
      }
      try {
        GenericTestUtils.waitFor(() -> processed.get() == threads,
            100, maxTotalWait);
      } finally {
        executor.shutdownNow();
      }
    } finally {
      for (Path path : paths) {
        FileUtils.deleteQuietly(path.toFile());
      }
    }
  }

  @Test
  public void serialRead() throws Exception {
    String s = "Hello World";
    byte[] array = s.getBytes(UTF_8);
    ChunkBuffer data = ChunkBuffer.wrap(ByteBuffer.wrap(array));
    Path tempFile = Files.createTempFile(PREFIX, "serial");
    try {
      File file = tempFile.toFile();
      int len = data.limit();
      int offset = 0;
      ChunkUtils.writeData(file, data, offset, len, null, true);

      ByteBuffer[] readBuffers = BufferUtils.assignByteBuffers(len, len);
      ChunkUtils.readData(file, readBuffers, offset, len, null);

      // There should be only one element in readBuffers
      Assertions.assertEquals(1, readBuffers.length);
      ByteBuffer readBuffer = readBuffers[0];

      assertArrayEquals(array, readBuffer.array());
      assertEquals(len, readBuffer.remaining());
    } catch (Exception e) {
      LOG.error("Failed to read data", e);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  public void validateChunkForOverwrite() throws IOException {

    Path tempFile = Files.createTempFile(PREFIX, "overwrite");
    FileUtils.write(tempFile.toFile(), "test", UTF_8);

    Assertions.assertTrue(
        ChunkUtils.validateChunkForOverwrite(tempFile.toFile(),
            new ChunkInfo("chunk", 3, 5)));

    Assertions.assertFalse(
        ChunkUtils.validateChunkForOverwrite(tempFile.toFile(),
            new ChunkInfo("chunk", 5, 5)));

    try (FileChannel fileChannel =
             FileChannel.open(tempFile, StandardOpenOption.READ)) {
      Assertions.assertTrue(
          ChunkUtils.validateChunkForOverwrite(fileChannel,
              new ChunkInfo("chunk", 3, 5)));

      Assertions.assertFalse(
          ChunkUtils.validateChunkForOverwrite(fileChannel,
              new ChunkInfo("chunk", 5, 5)));
    }
  }

  @Test
  public void readMissingFile() {
    // given
    int len = 123;
    int offset = 0;
    File nonExistentFile = new File("nosuchfile");
    ByteBuffer[] bufs = BufferUtils.assignByteBuffers(len, len);

    // when
    StorageContainerException e = assertThrows(
        StorageContainerException.class,
        () -> ChunkUtils.readData(nonExistentFile, bufs, offset, len, null));

    // then
    Assertions.assertEquals(UNABLE_TO_FIND_CHUNK, e.getResult());
  }

}
