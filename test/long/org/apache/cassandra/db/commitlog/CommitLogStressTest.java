package org.apache.cassandra.db.commitlog;

/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.util.concurrent.RateLimiter;
import org.junit.*;

import org.apache.cassandra.*;
import org.apache.cassandra.config.Config.CommitLogSync;
import org.apache.cassandra.config.*;
import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.io.util.DataInputBuffer;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.security.EncryptionContext;
import org.apache.cassandra.security.EncryptionContextGenerator;


public class CommitLogStressTest
{
    public static ByteBuffer dataSource;

    public static int NUM_THREADS = 4 * Runtime.getRuntime().availableProcessors() - 1;
    public static int numCells = 1;
    public static int cellSize = 1024;
    public static int rateLimit = 0;
    public static int runTimeMs = 10000;

    public static String location = DatabaseDescriptor.getCommitLogLocation() + "/stress";

    public static int hash(int hash, ByteBuffer bytes)
    {
        int shift = 0;
        for (int i = 0; i < bytes.limit(); i++)
        {
            hash += (bytes.get(i) & 0xFF) << shift;
            shift = (shift + 8) & 0x1F;
        }
        return hash;
    }

    public static void main(String[] args) throws Exception
    {
        try
        {
            if (args.length >= 1)
            {
                NUM_THREADS = Integer.parseInt(args[0]);
                System.out.println("Setting num threads to: " + NUM_THREADS);
            }

            if (args.length >= 2)
            {
                numCells = Integer.parseInt(args[1]);
                System.out.println("Setting num cells to: " + numCells);
            }

            if (args.length >= 3)
            {
                cellSize = Integer.parseInt(args[1]);
                System.out.println("Setting cell size to: " + cellSize + " be aware the source corpus may be small");
            }

            if (args.length >= 4)
            {
                rateLimit = Integer.parseInt(args[1]);
                System.out.println("Setting per thread rate limit to: " + rateLimit);
            }
            initialize();

            CommitLogStressTest tester = new CommitLogStressTest();
            tester.testFixedSize();
        }
        catch (Throwable e)
        {
            e.printStackTrace(System.err);
        }
        finally
        {
            System.exit(0);
        }
    }

    boolean failed = false;
    volatile boolean stop = false;
    boolean randomSize = false;
    boolean discardedRun = false;
    CommitLogPosition discardedPos;

    @BeforeClass
    static public void initialize() throws IOException
    {
        try (FileInputStream fis = new FileInputStream("CHANGES.txt"))
        {
            dataSource = ByteBuffer.allocateDirect((int) fis.getChannel().size());
            while (dataSource.hasRemaining())
            {
                fis.getChannel().read(dataSource);
            }
            dataSource.flip();
        }

        SchemaLoader.loadSchema();
        SchemaLoader.schemaDefinition(""); // leave def. blank to maintain old behaviour
        CommitLog.instance.stopUnsafe(true);
    }

    @Before
    public void cleanDir() throws IOException
    {
        File dir = new File(location);
        if (dir.isDirectory())
        {
            File[] files = dir.listFiles();

            for (File f : files)
                if (!f.delete())
                    Assert.fail("Failed to delete " + f);
        }
        else
        {
            dir.mkdir();
        }
    }

    @Test
    public void testRandomSize() throws Exception
    {
        randomSize = true;
        discardedRun = false;
        testAllLogConfigs();
    }

    @Test
    public void testFixedSize() throws Exception
    {
        randomSize = false;
        discardedRun = false;

        testAllLogConfigs();
    }

    @Test
    public void testDiscardedRun() throws Exception
    {
        randomSize = true;
        discardedRun = true;

        testAllLogConfigs();
    }

    public void testAllLogConfigs() throws IOException, InterruptedException, ExecutionException
    {
        failed = false;
        DatabaseDescriptor.setCommitLogSyncBatchWindow(1);
        DatabaseDescriptor.setCommitLogSyncPeriod(30);
        DatabaseDescriptor.setCommitLogSegmentSize(32);

        // test plain vanilla commit logs (the choice of 98% of users)
        testLog(null, EncryptionContextGenerator.createDisabledContext());

        // test the compression types
        testLog(new ParameterizedClass("LZ4Compressor", null), EncryptionContextGenerator.createDisabledContext());
        testLog(new ParameterizedClass("SnappyCompressor", null), EncryptionContextGenerator.createDisabledContext());
        testLog(new ParameterizedClass("DeflateCompressor", null), EncryptionContextGenerator.createDisabledContext());

        // test the encrypted commit log
        testLog(null, EncryptionContextGenerator.createContext(true));
    }

    public void testLog(ParameterizedClass compression, EncryptionContext encryptionContext) throws IOException, InterruptedException, ExecutionException
    {
        DatabaseDescriptor.setCommitLogCompression(compression);
        DatabaseDescriptor.setEncryptionContext(encryptionContext);

        String originalDir = DatabaseDescriptor.getCommitLogLocation();
        try
        {
            DatabaseDescriptor.setCommitLogLocation(location);
            for (CommitLogSync sync : CommitLogSync.values())
            {
                DatabaseDescriptor.setCommitLogSync(sync);
                CommitLog commitLog = new CommitLog(CommitLogArchiver.disabled());
                commitLog.segmentManager.enableReserveSegmentCreation();
                commitLog.start();
                testLog(commitLog);
                assert !failed;
            }
        }
        finally
        {
            DatabaseDescriptor.setCommitLogLocation(originalDir);
        }
    }

    public void testLog(CommitLog commitLog) throws IOException, InterruptedException, ExecutionException
    {
        System.out.format("\nTesting commit log size %.0fmb, compressor: %s, encryption enabled: %b, sync %s%s%s\n",
                           mb(DatabaseDescriptor.getCommitLogSegmentSize()),
                           commitLog.configuration.getCompressorName(),
                           commitLog.configuration.useEncryption(),
                           commitLog.executor.getClass().getSimpleName(),
                           randomSize ? " random size" : "",
                           discardedRun ? " with discarded run" : "");

        final List<CommitlogThread> threads = new ArrayList<>();
        ScheduledExecutorService scheduled = startThreads(commitLog, threads);

        discardedPos = CommitLogPosition.NONE;
        if (discardedRun)
        {
            // Makes sure post-break data is not deleted, and that replayer correctly rejects earlier mutations.
            Thread.sleep(runTimeMs / 3);
            stop = true;
            scheduled.shutdown();
            scheduled.awaitTermination(2, TimeUnit.SECONDS);

            for (CommitlogThread t: threads)
            {
                t.join();
                if (t.clsp.compareTo(discardedPos) > 0)
                    discardedPos = t.clsp;
            }
            verifySizes(commitLog);

            commitLog.discardCompletedSegments(Schema.instance.getCFMetaData("Keyspace1", "Standard1").cfId,
                    CommitLogPosition.NONE, discardedPos);
            threads.clear();

            System.out.format("Discarded at %s\n", discardedPos);
            verifySizes(commitLog);

            scheduled = startThreads(commitLog, threads);
        }

        Thread.sleep(runTimeMs);
        stop = true;
        scheduled.shutdown();
        scheduled.awaitTermination(2, TimeUnit.SECONDS);

        int hash = 0;
        int cells = 0;
        for (CommitlogThread t: threads)
        {
            t.join();
            hash += t.hash;
            cells += t.cells;
        }
        verifySizes(commitLog);

        commitLog.shutdownBlocking();

        System.out.println("Stopped. Replaying... ");
        System.out.flush();
        Reader reader = new Reader();
        File[] files = new File(location).listFiles();

        DummyHandler handler = new DummyHandler();
        reader.readAllFiles(handler, files);

        for (File f : files)
            if (!f.delete())
                Assert.fail("Failed to delete " + f);

        if (hash == reader.hash && cells == reader.cells)
            System.out.format("Test success. compressor = %s, encryption enabled = %b; discarded = %d, skipped = %d\n",
                              commitLog.configuration.getCompressorName(),
                              commitLog.configuration.useEncryption(),
                              reader.discarded, reader.skipped);
        else
        {
            System.out.format("Test failed (compressor = %s, encryption enabled = %b). Cells %d, expected %d, diff %d; discarded = %d, skipped = %d -  hash %d expected %d.\n",
                              commitLog.configuration.getCompressorName(),
                              commitLog.configuration.useEncryption(),
                              reader.cells, cells, cells - reader.cells, reader.discarded, reader.skipped,
                              reader.hash, hash);
            failed = true;
        }
    }

    private void verifySizes(CommitLog commitLog)
    {
        // Complete anything that's still left to write.
        commitLog.executor.requestExtraSync().awaitUninterruptibly();
        // One await() does not suffice as we may be signalled when an ongoing sync finished. Request another
        // (which shouldn't write anything) to make sure the first we triggered completes.
        // FIXME: The executor should give us a chance to await completion of the sync we requested.
        commitLog.executor.requestExtraSync().awaitUninterruptibly();

        // Wait for any pending deletes or segment allocations to complete.
        commitLog.segmentManager.awaitManagementTasksCompletion();

        long combinedSize = 0;
        for (File f : new File(DatabaseDescriptor.getCommitLogLocation()).listFiles())
            combinedSize += f.length();
        Assert.assertEquals(combinedSize, commitLog.getActiveOnDiskSize());

        List<String> logFileNames = commitLog.getActiveSegmentNames();
        Map<String, Double> ratios = commitLog.getActiveSegmentCompressionRatios();
        Collection<CommitLogSegment> segments = commitLog.segmentManager.getActiveSegments();

        for (CommitLogSegment segment : segments)
        {
            Assert.assertTrue(logFileNames.remove(segment.getName()));
            Double ratio = ratios.remove(segment.getName());

            Assert.assertEquals(segment.logFile.length(), segment.onDiskSize());
            Assert.assertEquals(segment.onDiskSize() * 1.0 / segment.contentSize(), ratio, 0.01);
        }
        Assert.assertTrue(logFileNames.isEmpty());
        Assert.assertTrue(ratios.isEmpty());
    }

    public ScheduledExecutorService startThreads(final CommitLog commitLog, final List<CommitlogThread> threads)
    {
        stop = false;
        for (int ii = 0; ii < NUM_THREADS; ii++) {
            final CommitlogThread t = new CommitlogThread(commitLog, new Random(ii));
            threads.add(t);
            t.start();
        }

        final long start = System.currentTimeMillis();
        Runnable printRunnable = new Runnable()
        {
            long lastUpdate = 0;

            public void run()
            {
                Runtime runtime = Runtime.getRuntime();
                long maxMemory = runtime.maxMemory();
                long allocatedMemory = runtime.totalMemory();
                long freeMemory = runtime.freeMemory();
                long temp = 0;
                long sz = 0;
                for (CommitlogThread clt : threads)
                {
                    temp += clt.counter.get();
                    sz += clt.dataSize;
                }
                double time = (System.currentTimeMillis() - start) / 1000.0;
                double avg = (temp / time);
                System.out.println(
                        String.format("second %d mem max %.0fmb allocated %.0fmb free %.0fmb mutations %d since start %d avg %.3f content %.1fmb ondisk %.1fmb transfer %.3fmb",
                                      ((System.currentTimeMillis() - start) / 1000),
                                      mb(maxMemory),
                                      mb(allocatedMemory),
                                      mb(freeMemory),
                                      (temp - lastUpdate),
                                      lastUpdate,
                                      avg,
                                      mb(commitLog.getActiveContentSize()),
                                      mb(commitLog.getActiveOnDiskSize()),
                                      mb(sz / time)));
                lastUpdate = temp;
            }
        };
        ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(1);
        scheduled.scheduleAtFixedRate(printRunnable, 1, 1, TimeUnit.SECONDS);
        return scheduled;
    }

    private static double mb(long maxMemory)
    {
        return maxMemory / (1024.0 * 1024);
    }

    private static double mb(double maxMemory)
    {
        return maxMemory / (1024 * 1024);
    }

    public static ByteBuffer randomBytes(int quantity, Random tlr)
    {
        ByteBuffer slice = ByteBuffer.allocate(quantity);
        ByteBuffer source = dataSource.duplicate();
        source.position(tlr.nextInt(source.capacity() - quantity));
        source.limit(source.position() + quantity);
        slice.put(source);
        slice.flip();
        return slice;
    }

    public class CommitlogThread extends Thread {
        final AtomicLong counter = new AtomicLong();
        int hash = 0;
        int cells = 0;
        int dataSize = 0;
        final CommitLog commitLog;
        final Random random;
        final AtomicInteger threadID = new AtomicInteger(0);

        volatile CommitLogPosition clsp;

        public CommitlogThread(CommitLog commitLog, Random rand)
        {
            this.commitLog = commitLog;
            this.random = rand;
        }

        public void run()
        {
            Thread.currentThread().setName("CommitLogThread-" + threadID.getAndIncrement());
            RateLimiter rl = rateLimit != 0 ? RateLimiter.create(rateLimit) : null;
            final Random rand = random != null ? random : ThreadLocalRandom.current();
            while (!stop)
            {
                if (rl != null)
                    rl.acquire();
                ByteBuffer key = randomBytes(16, rand);

                UpdateBuilder builder = UpdateBuilder.create(Schema.instance.getCFMetaData("Keyspace1", "Standard1"), Util.dk(key));
                for (int ii = 0; ii < numCells; ii++)
                {
                    int sz = randomSize ? rand.nextInt(cellSize) : cellSize;
                    ByteBuffer bytes = randomBytes(sz, rand);
                    builder.newRow("name" + ii).add("val", bytes);
                    hash = hash(hash, bytes);
                    ++cells;
                    dataSize += sz;
                }

                clsp = commitLog.add(new Mutation(builder.build()));
                counter.incrementAndGet();
            }
        }
    }

    class Reader extends CommitLogReader
    {
        int hash;
        int cells;
        int discarded;
        int skipped;

        @Override
        protected void readMutation(CommitLogReadHandler handler,
                                    byte[] inputBuffer,
                                    int size,
                                    CommitLogPosition minPosition,
                                    final int entryLocation,
                                    final CommitLogDescriptor desc) throws IOException
        {
            if (desc.id < discardedPos.segmentId)
            {
                System.out.format("Mutation from discarded segment, segment %d pos %d\n", desc.id, entryLocation);
                discarded++;
                return;
            }
            else if (desc.id == discardedPos.segmentId && entryLocation <= discardedPos.position)
            {
                // Skip over this mutation.
                skipped++;
                return;
            }

            DataInputPlus bufIn = new DataInputBuffer(inputBuffer, 0, size);
            Mutation mutation;
            try
            {
                mutation = Mutation.serializer.deserialize(bufIn,
                                                           desc.getMessagingVersion(),
                                                           SerializationHelper.Flag.LOCAL);
            }
            catch (IOException e)
            {
                // Test fails.
                throw new AssertionError(e);
            }

            for (PartitionUpdate cf : mutation.getPartitionUpdates())
            {

                Iterator<Row> rowIterator = cf.iterator();

                while (rowIterator.hasNext())
                {
                    Row row = rowIterator.next();
                    if (!(UTF8Type.instance.compose(row.clustering().get(0)).startsWith("name")))
                        continue;

                    for (Cell cell : row.cells())
                    {
                        hash = hash(hash, cell.value());
                        ++cells;
                    }
                }
            }
        }
    }

    class DummyHandler implements CommitLogReadHandler
    {
        public boolean shouldSkipSegmentOnError(CommitLogReadException exception) throws IOException { return false; }

        public void handleUnrecoverableError(CommitLogReadException exception) throws IOException { }

        public void handleMutation(Mutation m, int size, int entryLocation, CommitLogDescriptor desc) { }
    }
}
