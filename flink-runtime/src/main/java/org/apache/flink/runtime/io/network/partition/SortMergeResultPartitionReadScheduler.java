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

package org.apache.flink.runtime.io.network.partition;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.runtime.io.disk.BatchShuffleReadBufferPool;
import org.apache.flink.runtime.io.network.buffer.BufferRecycler;
import org.apache.flink.util.FatalExitExceptionHandler;
import org.apache.flink.util.IOUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Data reader for {@link SortMergeResultPartition} which can read data for all downstream tasks
 * consuming the corresponding {@link SortMergeResultPartition}. It always tries to read shuffle
 * data in order of file offset, which maximums the sequential read so can improve the blocking
 * shuffle performance.
 */
class SortMergeResultPartitionReadScheduler implements Runnable, BufferRecycler {

    private static final Logger LOG =
            LoggerFactory.getLogger(SortMergeResultPartitionReadScheduler.class);

    /**
     * Default maximum time (5min) to wait when requesting read buffers from the buffer pool before
     * throwing an exception.
     */
    private static final Duration DEFAULT_BUFFER_REQUEST_TIMEOUT = Duration.ofMinutes(5);

    /** Lock used to synchronize multi-thread access to thread-unsafe fields. */
    private final Object lock;

    /**
     * A {@link CompletableFuture} to be completed when this read scheduler including all resources
     * is released.
     */
    private final CompletableFuture<?> releaseFuture = new CompletableFuture<>();

    /** Buffer pool from which to allocate buffers for shuffle data reading. */
    private final BatchShuffleReadBufferPool bufferPool;

    /** Executor to run the shuffle data reading task. */
    private final Executor ioExecutor;

    /** Maximum number of buffers can be allocated by this partition reader. */
    private final int maxRequestedBuffers;

    /**
     * Maximum time to wait when requesting read buffers from the buffer pool before throwing an
     * exception.
     */
    private final Duration bufferRequestTimeout;

    /** All failed subpartition readers to be released. */
    @GuardedBy("lock")
    private final Set<SortMergeSubpartitionReader> failedReaders = new HashSet<>();

    /** All readers waiting to read data of different subpartitions. */
    @GuardedBy("lock")
    private final Set<SortMergeSubpartitionReader> allReaders = new HashSet<>();

    /**
     * All readers to be read in order. This queue sorts all readers by file offset to achieve
     * better sequential IO.
     */
    @GuardedBy("lock")
    private final Queue<SortMergeSubpartitionReader> sortedReaders = new PriorityQueue<>();

    /** File channel shared by all subpartitions to read data from. */
    @GuardedBy("lock")
    private FileChannel dataFileChannel;

    /** File channel shared by all subpartitions to read index from. */
    @GuardedBy("lock")
    private FileChannel indexFileChannel;

    /**
     * Whether the data reading task is currently running or not. This flag is used when trying to
     * submit the data reading task.
     */
    @GuardedBy("lock")
    private boolean isRunning;

    /** Number of buffers already allocated and still not recycled by this partition reader. */
    @GuardedBy("lock")
    private volatile int numRequestedBuffers;

    /** Whether this reader has been released or not. */
    @GuardedBy("lock")
    private volatile boolean isReleased;

    SortMergeResultPartitionReadScheduler(
            int numSubpartitions,
            BatchShuffleReadBufferPool bufferPool,
            Executor ioExecutor,
            Object lock) {
        this(numSubpartitions, bufferPool, ioExecutor, lock, DEFAULT_BUFFER_REQUEST_TIMEOUT);
    }

    SortMergeResultPartitionReadScheduler(
            int numSubpartitions,
            BatchShuffleReadBufferPool bufferPool,
            Executor ioExecutor,
            Object lock,
            Duration bufferRequestTimeout) {

        this.lock = checkNotNull(lock);
        this.bufferPool = checkNotNull(bufferPool);
        this.ioExecutor = checkNotNull(ioExecutor);
        // one partition reader can consume at most Math.max(16M, numSubpartitions) (the expected
        // buffers per request is 4M) buffers for data read, which means larger parallelism, more
        // buffers. Currently, it is only an empirical strategy which can not be configured.
        this.maxRequestedBuffers =
                Math.max(4 * bufferPool.getNumBuffersPerRequest(), numSubpartitions);
        this.bufferRequestTimeout = checkNotNull(bufferRequestTimeout);
    }

    @Override
    public synchronized void run() {
        Set<SortMergeSubpartitionReader> finishedReaders = new HashSet<>();
        Queue<MemorySegment> buffers;
        try {
            buffers = allocateBuffers();
        } catch (Throwable throwable) {
            // fail all pending subpartition readers immediately if any exception occurs
            LOG.error("Failed to request buffers for data reading.", throwable);
            failSubpartitionReaders(getAllReaders(), throwable);
            removeFinishedAndFailedReaders(0, finishedReaders);
            return;
        }
        int numBuffersAllocated = buffers.size();

        SortMergeSubpartitionReader subpartitionReader = addPreviousAndGetNextReader(null, true);
        while (subpartitionReader != null && !buffers.isEmpty()) {
            try {
                if (!subpartitionReader.readBuffers(buffers, this)) {
                    // there is no resource to release for finished readers currently
                    finishedReaders.add(subpartitionReader);
                    subpartitionReader = null;
                }
            } catch (Throwable throwable) {
                failSubpartitionReaders(Collections.singletonList(subpartitionReader), throwable);
                subpartitionReader = null;
                LOG.error("Failed to read shuffle data.", throwable);
            }
            subpartitionReader =
                    addPreviousAndGetNextReader(subpartitionReader, !buffers.isEmpty());
        }

        int numBuffersRead = numBuffersAllocated - buffers.size();
        releaseBuffers(buffers);

        removeFinishedAndFailedReaders(numBuffersRead, finishedReaders);
    }

    @VisibleForTesting
    Queue<MemorySegment> allocateBuffers() throws Exception {
        long timeoutTime = getBufferRequestTimeoutTime();
        do {
            List<MemorySegment> buffers = bufferPool.requestBuffers();
            if (!buffers.isEmpty()) {
                return new ArrayDeque<>(buffers);
            }
            checkState(!isReleased, "Result partition has been already released.");
        } while (System.nanoTime() < timeoutTime
                || System.nanoTime() < (timeoutTime = getBufferRequestTimeoutTime()));

        if (numRequestedBuffers <= 0) {
            throw new TimeoutException(
                    String.format(
                            "Buffer request timeout, this means there is a fierce contention of"
                                    + " the batch shuffle read memory, please increase '%s'.",
                            TaskManagerOptions.NETWORK_BATCH_SHUFFLE_READ_MEMORY.key()));
        }
        return new ArrayDeque<>();
    }

    private long getBufferRequestTimeoutTime() {
        return bufferPool.getLastBufferOperationTimestamp() + bufferRequestTimeout.toNanos();
    }

    private void releaseBuffers(Queue<MemorySegment> buffers) {
        if (!buffers.isEmpty()) {
            try {
                bufferPool.recycle(buffers);
                buffers.clear();
            } catch (Throwable throwable) {
                // this should never happen so just trigger fatal error
                FatalExitExceptionHandler.INSTANCE.uncaughtException(
                        Thread.currentThread(), throwable);
            }
        }
    }

    private void failSubpartitionReaders(
            Collection<SortMergeSubpartitionReader> readers, Throwable failureCause) {
        synchronized (lock) {
            failedReaders.addAll(readers);
        }

        for (SortMergeSubpartitionReader reader : readers) {
            try {
                reader.fail(failureCause);
            } catch (Throwable throwable) {
                // this should never happen so just trigger fatal error
                FatalExitExceptionHandler.INSTANCE.uncaughtException(
                        Thread.currentThread(), throwable);
            }
        }
    }

    private void removeFinishedAndFailedReaders(
            int numBuffersRead, Set<SortMergeSubpartitionReader> finishedReaders) {
        synchronized (lock) {
            for (SortMergeSubpartitionReader reader : finishedReaders) {
                allReaders.remove(reader);
            }
            finishedReaders.clear();

            for (SortMergeSubpartitionReader reader : failedReaders) {
                allReaders.remove(reader);
            }
            failedReaders.clear();

            if (allReaders.isEmpty()) {
                bufferPool.unregisterRequester(this);
                closeFileChannels();
                sortedReaders.clear();
            }

            numRequestedBuffers += numBuffersRead;
            isRunning = false;
            mayTriggerReading();
            mayNotifyReleased();
        }
    }

    private void mayNotifyReleased() {
        assert Thread.holdsLock(lock);

        if (isReleased && allReaders.isEmpty()) {
            releaseFuture.complete(null);
        }
    }

    private Queue<SortMergeSubpartitionReader> getAllReaders() {
        synchronized (lock) {
            if (isReleased) {
                return new ArrayDeque<>();
            }
            return new ArrayDeque<>(allReaders);
        }
    }

    @Nullable
    private SortMergeSubpartitionReader addPreviousAndGetNextReader(
            SortMergeSubpartitionReader previousReader, boolean pollNext) {
        synchronized (lock) {
            if (previousReader != null) {
                sortedReaders.add(previousReader);
            }
            if (!pollNext) {
                return null;
            }
            SortMergeSubpartitionReader subpartitionReader = sortedReaders.poll();
            while (subpartitionReader != null && failedReaders.contains(subpartitionReader)) {
                subpartitionReader = sortedReaders.poll();
            }
            return subpartitionReader;
        }
    }

    SortMergeSubpartitionReader createSubpartitionReader(
            BufferAvailabilityListener availabilityListener,
            int targetSubpartition,
            PartitionedFile resultFile)
            throws IOException {
        synchronized (lock) {
            checkState(!isReleased, "Partition is already released.");

            PartitionedFileReader fileReader = createFileReader(resultFile, targetSubpartition);
            SortMergeSubpartitionReader subpartitionReader =
                    new SortMergeSubpartitionReader(availabilityListener, fileReader);
            if (allReaders.isEmpty()) {
                bufferPool.registerRequester(this);
            }
            allReaders.add(subpartitionReader);
            sortedReaders.add(subpartitionReader);
            subpartitionReader
                    .getReleaseFuture()
                    .thenRun(() -> releaseSubpartitionReader(subpartitionReader));

            mayTriggerReading();
            return subpartitionReader;
        }
    }

    private void releaseSubpartitionReader(SortMergeSubpartitionReader subpartitionReader) {
        synchronized (lock) {
            if (allReaders.contains(subpartitionReader)) {
                failedReaders.add(subpartitionReader);
            }
        }
    }

    private PartitionedFileReader createFileReader(
            PartitionedFile resultFile, int targetSubpartition) throws IOException {
        assert Thread.holdsLock(lock);

        try {
            if (allReaders.isEmpty()) {
                openFileChannels(resultFile);
            }
            return new PartitionedFileReader(
                    resultFile, targetSubpartition, dataFileChannel, indexFileChannel);
        } catch (Throwable throwable) {
            if (allReaders.isEmpty()) {
                closeFileChannels();
            }
            throw throwable;
        }
    }

    private void openFileChannels(PartitionedFile resultFile) throws IOException {
        assert Thread.holdsLock(lock);

        closeFileChannels();
        dataFileChannel = openFileChannel(resultFile.getDataFilePath());
        indexFileChannel = openFileChannel(resultFile.getIndexFilePath());
    }

    private void closeFileChannels() {
        assert Thread.holdsLock(lock);

        IOUtils.closeAllQuietly(dataFileChannel, indexFileChannel);
        dataFileChannel = null;
        indexFileChannel = null;
    }

    @Override
    public void recycle(MemorySegment segment) {
        synchronized (lock) {
            bufferPool.recycle(segment);
            --numRequestedBuffers;

            mayTriggerReading();
        }
    }

    private void mayTriggerReading() {
        assert Thread.holdsLock(lock);

        if (!isRunning
                && !allReaders.isEmpty()
                && numRequestedBuffers + bufferPool.getNumBuffersPerRequest() <= maxRequestedBuffers
                && numRequestedBuffers < bufferPool.getAverageBuffersPerRequester()) {
            isRunning = true;
            ioExecutor.execute(this);
        }
    }

    /**
     * Releases this read scheduler and returns a {@link CompletableFuture} which will be completed
     * when all resources are released.
     */
    CompletableFuture<?> release() {
        List<SortMergeSubpartitionReader> pendingReaders;
        synchronized (lock) {
            if (isReleased) {
                return releaseFuture;
            }
            isReleased = true;

            failedReaders.addAll(allReaders);
            pendingReaders = new ArrayList<>(allReaders);
            mayNotifyReleased();
        }

        failSubpartitionReaders(
                pendingReaders,
                new IllegalStateException("Result partition has been already released."));
        return releaseFuture;
    }

    private static FileChannel openFileChannel(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.READ);
    }

    @VisibleForTesting
    int getNumPendingReaders() {
        synchronized (lock) {
            return allReaders.size();
        }
    }

    @VisibleForTesting
    FileChannel getDataFileChannel() {
        synchronized (lock) {
            return dataFileChannel;
        }
    }

    @VisibleForTesting
    FileChannel getIndexFileChannel() {
        synchronized (lock) {
            return indexFileChannel;
        }
    }

    @VisibleForTesting
    CompletableFuture<?> getReleaseFuture() {
        return releaseFuture;
    }

    @VisibleForTesting
    boolean isRunning() {
        synchronized (lock) {
            return isRunning;
        }
    }
}
