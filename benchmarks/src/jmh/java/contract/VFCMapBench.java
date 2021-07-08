package contract;

import com.hedera.services.state.merkle.virtual.ContractHashStore;
import com.hedera.services.state.merkle.virtual.ContractLeafStore;
import com.hedera.services.state.merkle.virtual.ContractUint256;
import com.hedera.services.store.models.Id;
import com.swirlds.common.crypto.CryptoFactory;
import com.swirlds.fcmap.VFCMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark tests for the VirtualMap. These benchmarks are just of the tree itself,
 * and not of any underlying storage mechanism. In fact, it just uses an in-memory set of
 * hash maps as the backing VirtualDataSource, and benchmarks those too just so we have
 * baseline numbers.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class VFCMapBench {
    @Param({"5", "10", "15", "20", "25"})
    public int numUpdatesPerOperation;

    @Param({"10000"})
    public int targetOpsPerSecond;

    @Param({"1000"})
    public long maxMillisPerHashRound;

    @Param({"10000", "100000", "1000000", "10000000", "100000000", "1000000000"})
    public long numEntities;

    @Param("true")
    public boolean preFill;

    @Param({"true", "false"})
    public boolean inMemoryIndex;

    @Param({"true", "false"})
    public boolean inMemoryStore;

    private final Random rand = new Random();
    private VFCMap<ContractUint256, ContractUint256> contractMap;
    private Future<?> prevIterationHashingFuture;
    private final ExecutorService background = Executors.newSingleThreadScheduledExecutor();

    @Setup
    public void prepare() throws Exception {
        final var ls = new ContractLeafStore(new Id(1, 2, 3),inMemoryIndex,inMemoryStore);
        final var hs = new ContractHashStore(new Id(1, 2, 3),inMemoryIndex,inMemoryStore);
        contractMap = new VFCMap<>(ls, hs);

        System.out.println("\nUsing inMemoryIndex = " + inMemoryIndex+"  -- inMemoryStore = " + inMemoryStore);

        if (preFill) {
            for (int i = 0; i < numEntities; i++) {
                if (i % 100000 == 0 && i > 0) {
                    System.out.println("Completed: " + i);
                    if (prevIterationHashingFuture != null) {
                        prevIterationHashingFuture.get(); // block in case the previous iteration hasn't finished yet
                    }
                    final var unmodifiableContractMap = contractMap;
                    contractMap = contractMap.copy();
                    prevIterationHashingFuture = hashAndRelease(unmodifiableContractMap);
                }
//                if (i % 1000_000 == 0 && i > 0) {
//                    System.out.println("hashIndex");
//                    hs.printMutationQueueStats();
//                    ls.printMutationQueueStats();
////                    hs.printUniqueHashes();
//                }
                final var key = asContractKey(i);
                final var value = asContractValue(i);
                try {
                    contractMap.put(key, value);
                } catch (Exception e) {
                    e.printStackTrace();
                    System.err.println(i);
                    throw e;
                }
            }

            System.out.println("Completed: " + numEntities);
//            hs.printMutationQueueStats();

            // During setup we perform the full hashing and release the old copy. This way,
            // during the tests, we don't have an initial slow hash.
            if (prevIterationHashingFuture != null) {
                prevIterationHashingFuture.get(); // block in case the previous iteration hasn't finished yet
            }
            final var unmodifiableContractMap = contractMap;
            contractMap = contractMap.copy();
            prevIterationHashingFuture = hashAndRelease(unmodifiableContractMap);
            prevIterationHashingFuture.get(); // Block until the hashing is done
        }

        printDataStoreSize();
    }

    private Future<?> hashAndRelease(VFCMap<ContractUint256, ContractUint256> unmodifiableContractMap) {
        return background.submit(() -> {
            try {
                final var hashFuture = CryptoFactory.getInstance().digestTreeAsync2(unmodifiableContractMap);
                hashFuture.get();
                unmodifiableContractMap.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @TearDown
    public void destroy() {
        printDataStoreSize();
        /*store.close();*/
    }

    private ContractUint256 asContractKey(long index) {
        return new ContractUint256(BigInteger.valueOf(index));
    }

    private ContractUint256 asContractValue(long index) {
        return new ContractUint256(BigInteger.valueOf(index));
    }

    private void printDataStoreSize() {
        // print data dir size
        Path dir =  Path.of("data");
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try {
                long size = Files.walk(dir)
                        .filter(p -> p.toFile().isFile())
                        .mapToLong(p -> p.toFile().length())
                        .sum();
                long count = Files.walk(dir)
                        .filter(p -> p.toFile().isFile())
                        .count();
                System.out.printf("\nTest data storage %d files totalling size: %,.1f Mb\n",count,(double)size/(1024d*1024d));
            } catch (Exception e) {
                System.err.println("Failed to measure size of directory. ["+dir.toFile().getAbsolutePath()+"]");
                e.printStackTrace();
            }
        }
    }

    /**
     * Benchmarks update operations of an existing tree.
     *
     * @throws Exception Any exception should be treated as fatal.
     */
    @Benchmark
    public void update() throws Exception {
        // Release the old contract after hashing has finished. We specifically block here waiting
        // for the old hashing to complete because in the real world we cannot have hashing fall
        // behind, so the fastest we can go is the max of the speed of modifying the latest and
        // hashing the previous.
        if (prevIterationHashingFuture != null) {
            // Wait for the hashing of the previous iteration to complete. If it doesn't complete
            // in maxMillisPerHashRound, then a TimeoutException is raised and the test fails.
            prevIterationHashingFuture.get(maxMillisPerHashRound, TimeUnit.MILLISECONDS);
//            prevIterationHashingFuture.get();
        }

        // Create a new fast copy and start hashing the old one in a background thread.
        final var unmodifiableContractMap = contractMap;
        contractMap = contractMap.copy();
        prevIterationHashingFuture = hashAndRelease(unmodifiableContractMap);

        // Start modifying the new fast copy
        final var iterationsPerRound = numUpdatesPerOperation * targetOpsPerSecond;
        for (int j=0; j<iterationsPerRound; j++) {
            final var i = rand.nextInt((int)numEntities);
            contractMap.put(asContractKey(i), asContractValue(i + numEntities));
        }
    }
}
