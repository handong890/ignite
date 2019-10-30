/*
 * Copyright 2019 GridGain Systems, Inc. and Contributors.
 *
 * Licensed under the GridGain Community Edition License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gridgain.com/products/software/community-edition/gridgain-community-edition-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.persistence;

import java.io.File;
import java.io.IOException;
import java.nio.file.OpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.failure.StopNodeFailureHandler;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.processors.cache.binary.CacheObjectBinaryProcessorImpl;
import org.apache.ignite.internal.processors.cache.persistence.file.FileIO;
import org.apache.ignite.internal.processors.cache.persistence.file.FileIODecorator;
import org.apache.ignite.internal.processors.cache.persistence.file.FileIOFactory;
import org.apache.ignite.internal.processors.cache.persistence.file.RandomAccessFileIOFactory;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.PRIMARY_SYNC;

/**
 * Tests for verification of binary metadata async writing to disk.
 */
public class IgnitePdsBinaryMetadataAsyncWritingTest extends GridCommonAbstractTest {
    /** */
    private static final AtomicReference<CountDownLatch> fileWriteLatchRef = new AtomicReference<>(null);

    /** */
    private FileIOFactory specialFileIOFactory;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        if (igniteInstanceName.contains("client")) {
            cfg.setClientMode(true);

            return cfg;
        }

        cfg.setDataStorageConfiguration(
            new DataStorageConfiguration()
                .setDefaultDataRegionConfiguration(
                    new DataRegionConfiguration()
                        .setPersistenceEnabled(true)
                )
                .setFileIOFactory(
                    specialFileIOFactory != null ? specialFileIOFactory : new RandomAccessFileIOFactory()
                )
        );

        cfg.setCacheConfiguration(
            new CacheConfiguration(DEFAULT_CACHE_NAME)
                .setBackups(1)
            .setAffinity(new RendezvousAffinityFunction(false, 16))
        );

        cfg.setFailureHandler(new StopNodeFailureHandler());

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        stopAllGrids();

        cleanPersistenceDir();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        if (fileWriteLatchRef != null && fileWriteLatchRef.get() != null)
            fileWriteLatchRef.get().countDown();

        stopAllGrids();

        cleanPersistenceDir();
    }

    /**
     * Verifies that registration of new binary meta does not block discovery thread
     * and new node can join the cluster when binary metadata is in the process of writing.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNodeJoinIsNotBlockedByAsyncMetaWriting() throws Exception {
        final CountDownLatch fileWriteLatch = initSlowFileIOFactory();

        Ignite ig = startGrid(0);
        ig.cluster().active(true);

        IgniteCache<Object, Object> cache = ig.cache(DEFAULT_CACHE_NAME);
        GridTestUtils.runAsync(() -> cache.put(0, new TestAddress(0, "USA", "NYC", "Park Ave")));

        specialFileIOFactory = null;

        startGrid(1);
        waitForTopology(2);

        fileWriteLatch.countDown();
    }

    /**
     * Verifies that metadata is restored on node join even if it was deleted when the node was down.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBinaryMetadataIsRestoredAfterDeletionOnNodeJoin() throws Exception {
        IgniteEx ig0 = startGrid(0);
        IgniteEx ig1 = startGrid(1);

        ig0.cluster().active(true);

        IgniteCache<Object, Object> cache = ig0.cache(DEFAULT_CACHE_NAME);
        int key = findAffinityKeyForNode(ig0.affinity(DEFAULT_CACHE_NAME), ig1.localNode());
        cache.put(key, new TestAddress(0, "USA", "NYC", "Park Ave"));

        String ig1ConsId = ig1.localNode().consistentId().toString();
        stopGrid(1);
        cleanBinaryMetaFolderForNode(ig1ConsId);

        ig1 = startGrid(1);
        stopGrid(0);

        cache = ig1.cache(DEFAULT_CACHE_NAME);
        TestAddress addr = (TestAddress)cache.get(key);
        assertNotNull(addr);
        assertEquals("USA", addr.country);
    }

    /**
     * Verifies that request adding/modifying binary metadata (e.g. put to cache a new value)
     * is blocked until write to disk is finished.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testThreadRequestingUpdateBlockedTillWriteCompletion() throws Exception {
        final CountDownLatch fileWriteLatch = initSlowFileIOFactory();

        Ignite ig = startGrid();

        ig.cluster().active(true);

        IgniteCache<Object, Object> cache = ig.cache(DEFAULT_CACHE_NAME);

        GridTestUtils.runAsync(() -> cache.put(1, new TestPerson(0, "John", "Oliver")));

        assertEquals(0, cache.size(CachePeekMode.PRIMARY));

        fileWriteLatch.countDown();

        assertTrue(GridTestUtils.waitForCondition(() -> cache.size(CachePeekMode.PRIMARY) == 1, 10_000));
    }

    /**
     * @throws Exception
     */
    @Test
    public void testDiscoveryIsNotBlockedOnMetadataWrite() throws Exception {
        final CountDownLatch fileWriteLatch = initSlowFileIOFactory();

        IgniteKernal ig = (IgniteKernal)startGrid();

        ig.cluster().active(true);

        IgniteCache<Object, Object> cache = ig.cache(DEFAULT_CACHE_NAME);

        TestAddress addr = new TestAddress(0, "RUS", "Spb", "Nevsky");
        TestPerson person = new TestPerson(0, "John", "Oliver");
        person.address(addr);
        TestAccount account = new TestAccount(person, 0, 1000);

        GridTestUtils.runAsync(() -> cache.put(0, addr));
        GridTestUtils.runAsync(() -> cache.put(0, person));
        GridTestUtils.runAsync(() -> cache.put(0, account));

        assertEquals(0, cache.size(CachePeekMode.PRIMARY));

        Map locCache = GridTestUtils.getFieldValue(
            (CacheObjectBinaryProcessorImpl)ig.context().cacheObjects(), "metadataLocCache");

        assertTrue(GridTestUtils.waitForCondition(() -> locCache.size() == 3, 5_000));

        fileWriteLatch.countDown();
    }

    /**
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNodeIsStoppedOnExceptionDuringStoringMetadata() throws Exception {
        IgniteEx ig0 = startGrid(0);

        specialFileIOFactory = new FailingFileIOFactory(new RandomAccessFileIOFactory());

        IgniteEx ig1 = startGrid(1);

        ig0.cluster().active(true);

        int ig1Key = findAffinityKeyForNode(ig0.affinity(DEFAULT_CACHE_NAME), ig1.localNode());

        IgniteCache<Object, Object> cache = ig0.cache(DEFAULT_CACHE_NAME);

        cache.put(ig1Key, new TestAddress(0, "USA", "NYC", "6th Ave"));

        waitForTopology(1);
    }

    /**
     * Verifies that no updates are applied to cache on node until all metadata write operations
     * for updated type are fully written to disk.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testParallelUpdatesToBinaryMetadata() throws Exception {
        IgniteEx ig0 = startGrid(0);

        final CountDownLatch fileWriteLatch = initSlowFileIOFactory();
        IgniteEx ig1 = startGrid(1);

        specialFileIOFactory = null;
        IgniteEx ig2 = startGrid(2);

        ig0.cluster().active(true);

        int key0 = findAffinityKeyForNode(ig0.affinity(DEFAULT_CACHE_NAME), ig1.localNode());
        int key1 = findAffinityKeyForNode(ig0.affinity(DEFAULT_CACHE_NAME), ig1.localNode(), key0);

        assertTrue(key0 != key1);

        GridTestUtils.runAsync(() -> ig0.cache(DEFAULT_CACHE_NAME).put(key0, new TestAddress(key0, "Russia", "Moscow")));
        GridTestUtils.runAsync(() -> ig2.cache(DEFAULT_CACHE_NAME).put(key1, new TestAddress(key1, "USA", "NYC", "Park Ave")));

        assertEquals(0, ig0.cache(DEFAULT_CACHE_NAME).size(CachePeekMode.PRIMARY));

        fileWriteLatch.countDown();

        assertTrue(GridTestUtils.
            waitForCondition(() -> ig0.cache(DEFAULT_CACHE_NAME).size(CachePeekMode.PRIMARY) == 2, 10_000));

        stopGrid(0);
        stopGrid(2);

        IgniteCache<Object, Object> cache = ig1.cache(DEFAULT_CACHE_NAME);
        TestAddress addr0 = (TestAddress)cache.get(key0);
        TestAddress addr1 = (TestAddress)cache.get(key1);

        assertEquals("Russia", addr0.country);
        assertEquals("USA", addr1.country);
    }

    /**
     * Verifies that put(key) method called from client on cache in FULL_SYNC mode returns only when
     * all affinity nodes for this key finished writing binary metadata.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPutRequestFromClientIsBlockedIfBinaryMetaWriteIsHanging() throws Exception {
        String cacheName = "testCache";

        CacheConfiguration testCacheCfg = new CacheConfiguration(cacheName)
            .setBackups(2)
            .setAtomicityMode(CacheAtomicityMode.ATOMIC)
            .setCacheMode(CacheMode.PARTITIONED)
            .setWriteSynchronizationMode(FULL_SYNC);

        IgniteEx ig0 = startGrid(0);

        final CountDownLatch fileWriteLatch = initSlowFileIOFactory();
        IgniteEx ig1 = startGrid(1);

        specialFileIOFactory = null;
        IgniteEx ig2 = startGrid(2);

        IgniteEx cl0 = startGrid("client0");
        ig0.cluster().active(true);
        IgniteCache cache0 = cl0.createCache(testCacheCfg);

        int key0 = findAffinityKeyForNode(ig0.affinity(cacheName), ig0.localNode());

        AtomicBoolean putFinished = new AtomicBoolean(false);

        GridTestUtils.runAsync(() -> {
            cache0.put(key0, new TestAddress(key0, "Russia", "Saint-Petersburg"));

            putFinished.set(true);
        });

        assertFalse(GridTestUtils.waitForCondition(() -> putFinished.get(), 5_000));

        fileWriteLatch.countDown();

        assertTrue(GridTestUtils.waitForCondition(() -> putFinished.get(), 5_000));
    }

    /**
     * Verifies that put(key) method called from non-affinity server on cache in FULL_SYNC mode returns only when
     * all affinity nodes for this key finished writing binary metadata.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPutRequestFromServerIsBlockedIfBinaryMetaWriteIsHanging() throws Exception {
        putRequestFromServer(true);
    }

    /**
     * Verifies that put from client to ATOMIC cache in PRIMARY_SYNC mode is not blocked
     * if binary metadata async write operation hangs on backup node and not on primary.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPutRequestFromClientCompletesIfMetadataWriteHangOnBackup() throws Exception {
        String cacheName = "testCache";

        CacheConfiguration testCacheCfg = new CacheConfiguration(cacheName)
            .setBackups(2)
            .setAtomicityMode(CacheAtomicityMode.ATOMIC)
            .setCacheMode(CacheMode.PARTITIONED)
            .setWriteSynchronizationMode(PRIMARY_SYNC);

        IgniteEx ig0 = startGrid(0);

        CountDownLatch fileWriteLatch = initSlowFileIOFactory();
        IgniteEx ig1 = startGrid(1);

        specialFileIOFactory = null;
        IgniteEx ig2 = startGrid(2);

        ig0.cluster().active(true);

        IgniteEx cl0 = startGrid("client0");

        IgniteCache cache = cl0.createCache(testCacheCfg);
        Affinity<Object> aff = cl0.affinity(cacheName);

        AtomicBoolean putCompleted = new AtomicBoolean(false);
        int key = findAffinityKeyForNode(aff, ig0.localNode());
        GridTestUtils.runAsync(() -> {
            cache.put(key, new TestAddress(key, "USA", "NYC"));

            putCompleted.set(true);
        });

        assertTrue(GridTestUtils.waitForCondition(() -> putCompleted.get(), 5_000));

        fileWriteLatch.countDown();
    }

    /**
     * Verifies that put from server to ATOMIC cache in PRIMARY_SYNC mode is not blocked
     * if binary metadata async write operation hangs on backup node and not on primary.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPutRequestFromServerCompletesIfMetadataWriteHangOnBackup() throws Exception {
        putRequestFromServer(false);
    }

    /**
     * Verifies that metadata write hanging on non-affinity node doesn't block on-going put operation.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPutRequestFromClientCompletesIfMetadataWriteHangOnNonAffinityNode() throws Exception {
        String cacheName = "testCache";

        CacheConfiguration testCacheCfg = new CacheConfiguration(cacheName)
            .setBackups(1)
            .setAtomicityMode(CacheAtomicityMode.ATOMIC)
            .setCacheMode(CacheMode.PARTITIONED)
            .setWriteSynchronizationMode(FULL_SYNC);

        IgniteEx ig0 = startGrid(0);

        CountDownLatch fileWriteLatch = initSlowFileIOFactory();
        IgniteEx ig1 = startGrid(1);

        specialFileIOFactory = null;
        IgniteEx ig2 = startGrid(2);

        IgniteEx cl0 = startGrid("client0");
        cl0.cluster().active(true);

        IgniteCache cache = cl0.createCache(testCacheCfg);
        Affinity<Object> aff = cl0.affinity(cacheName);
        int nonAffKey = findNonAffinityKeyForNode(aff, ig1.localNode(), 0);

        AtomicBoolean putCompleted = new AtomicBoolean(false);

        GridTestUtils.runAsync(() -> {
            cache.put(nonAffKey, new TestAddress(nonAffKey, "USA", "NYC"));

            putCompleted.set(true);
        });

        assertTrue(GridTestUtils.waitForCondition(() -> putCompleted.get(), 5_000));

        //internal map in BinaryMetadataFileStore with futures awaiting write operations
        Map map = GridTestUtils.getFieldValue(
            (CacheObjectBinaryProcessorImpl)ig1.context().cacheObjects(), "metadataFileStore", "writeOpFutures");

        assertTrue(!map.isEmpty());

        fileWriteLatch.countDown();

        assertTrue(GridTestUtils.waitForCondition(() -> map.isEmpty(), 5_000));
    }

    /**
     * @param expectedBlocked
     * @throws Exception
     */
    private void putRequestFromServer(boolean expectedBlocked) throws Exception {
        String cacheName = "testCache";

        CacheWriteSynchronizationMode syncMode = expectedBlocked ? FULL_SYNC : PRIMARY_SYNC;

        CacheConfiguration testCacheCfg = new CacheConfiguration(cacheName)
            .setBackups(2)
            .setAtomicityMode(CacheAtomicityMode.ATOMIC)
            .setCacheMode(CacheMode.PARTITIONED)
            .setWriteSynchronizationMode(syncMode);

        IgniteEx ig0 = startGrid(0);

        startGrid(1);
        final CountDownLatch fileWriteLatch = initSlowFileIOFactory();
        IgniteEx ig2 = startGrid(2);

        specialFileIOFactory = null;
        startGrid(3);

        ig0.cluster().active(true);
        IgniteCache cache = ig0.createCache(testCacheCfg);

        int key = 0;
        Affinity<Object> aff = ig0.affinity(cacheName);

        while (true) {
            key = findNonAffinityKeyForNode(aff, ig0.localNode(), key);

            if (aff.isBackup(ig2.localNode(), key))
                break;
            else
                key++;
        }

        AtomicBoolean putFinished = new AtomicBoolean(false);

        int key0 = key;
        GridTestUtils.runAsync(() -> {
            cache.put(key0, new TestAddress(key0, "USA", "NYC"));

            putFinished.set(true);
        });

        if (expectedBlocked) {
            assertFalse(GridTestUtils.waitForCondition(() -> putFinished.get(), 5_000));

            fileWriteLatch.countDown();

            assertTrue(GridTestUtils.waitForCondition(() -> putFinished.get(), 5_000));
        }
        else
            assertTrue(GridTestUtils.waitForCondition(() -> putFinished.get(), 5_000));
    }

    /**
     * Initializes special FileIOFactory emulating slow write to disk.
     *
     * @return Latch to release write operation.
     */
    private CountDownLatch initSlowFileIOFactory() {
        CountDownLatch cdl = new CountDownLatch(1);

        specialFileIOFactory = new SlowFileIOFactory(new RandomAccessFileIOFactory());
        fileWriteLatchRef.set(cdl);

        return cdl;
    }

    /**
     * Deletes directory with persisted binary metadata for a node with given Consistent ID.
     */
    private void cleanBinaryMetaFolderForNode(String consId) throws IgniteCheckedException {
        String dfltWorkDir = U.defaultWorkDirectory();
        File metaDir = U.resolveWorkDirectory(dfltWorkDir, "binary_meta", false);

        for (File subDir : metaDir.listFiles()) {
            if (subDir.getName().contains(consId)) {
                U.delete(subDir);

                return;
            }
        }
    }

    /** Finds a key that target node is neither primary or backup. */
    private int findNonAffinityKeyForNode(Affinity aff, ClusterNode targetNode, int startFrom) {
        int key = startFrom;

        while (true) {
            if (!aff.isPrimaryOrBackup(targetNode, key))
                return key;

            key++;
        }
    }

    /** Finds a key that target node is a primary node for. */
    private int findAffinityKeyForNode(Affinity aff, ClusterNode targetNode, Integer... excludeKeys) {
        int key = 0;

        while (true) {
            if (aff.isPrimary(targetNode, key)
                && (excludeKeys != null ? !Arrays.asList(excludeKeys).contains(Integer.valueOf(key)) : true))
                return key;

            key++;
        }
    }

    /** */
    static final class TestPerson {
        /** */
        private final int id;
        /** */
        private final String firstName;
        /** */
        private final String surname;
        /** */
        private TestAddress addr;

        /** */
        TestPerson(int id, String firstName, String surname) {
            this.id = id;
            this.firstName = firstName;
            this.surname = surname;
        }

        /** */
        void address(TestAddress addr) {
            this.addr = addr;
        }
    }

    /** */
    static final class TestAddress {
        /** */
        private final int id;
        /** */
        private final String country;
        /** */
        private final String city;
        /** */
        private final String address;

        /** */
        TestAddress(int id, String country, String city) {
            this.id = id;
            this.country = country;
            this.city = city;
            this.address = null;
        }

        /** */
        TestAddress(int id, String country, String city, String street) {
            this.id = id;
            this.country = country;
            this.city = city;
            this.address = street;
        }
    }

    /** */
    static final class TestAccount {
        /** */
        private final TestPerson person;
        /** */
        private final int accountId;
        /** */
        private final long accountBalance;
        /** */
        TestAccount(
            TestPerson person, int id, long balance) {
            this.person = person;
            accountId = id;
            accountBalance = balance;
        }
    }

    /** */
    private static boolean isBinaryMetaFile(File file) {
        return file.getPath().contains("binary_meta");
    }

    /** */
    static final class SlowFileIOFactory implements FileIOFactory {
        /** */
        private final FileIOFactory delegateFactory;

        /**
         * @param delegateFactory Delegate factory.
         */
        SlowFileIOFactory(FileIOFactory delegateFactory) {
            this.delegateFactory = delegateFactory;
        }

        /** {@inheritDoc} */
        @Override public FileIO create(File file, OpenOption... modes) throws IOException {
            FileIO delegate = delegateFactory.create(file, modes);

            if (isBinaryMetaFile(file))
                return new SlowFileIO(delegate, fileWriteLatchRef.get());

            return delegate;
        }
    }

    /** */
    static class SlowFileIO extends FileIODecorator {
        /** */
        private final CountDownLatch fileWriteLatch;

        /**
         * @param delegate File I/O delegate
         */
        public SlowFileIO(FileIO delegate, CountDownLatch fileWriteLatch) {
            super(delegate);

            this.fileWriteLatch = fileWriteLatch;
        }

        /** {@inheritDoc} */
        @Override public int write(byte[] buf, int off, int len) throws IOException {
            try {
                fileWriteLatch.await();
            }
            catch (InterruptedException e) {
                // No-op.
            }

            return super.write(buf, off, len);
        }
    }

    /** */
    static final class FailingFileIOFactory implements FileIOFactory {
        /** */
        private final FileIOFactory delegateFactory;

        /**
         * @param factory Delegate factory.
         */
        FailingFileIOFactory(FileIOFactory factory) {
            delegateFactory = factory;
        }

        /** {@inheritDoc}*/
        @Override public FileIO create(File file, OpenOption... modes) throws IOException {
            FileIO delegate = delegateFactory.create(file, modes);

            if (isBinaryMetaFile(file))
                return new FailingFileIO(delegate);

            return delegate;
        }
    }

    /** */
    static final class FailingFileIO extends FileIODecorator {
        /**
         * @param delegate File I/O delegate
         */
        public FailingFileIO(FileIO delegate) {
            super(delegate);
        }

        /** {@inheritDoc}*/
        @Override public int write(byte[] buf, int off, int len) throws IOException {
            throw new IOException("Error occured during write of binary metadata");
        }
    }
}
