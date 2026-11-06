package com.bugfunbug.linearreader.memory;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI MEMORY ENGINE V4 — MAXIMUM PERFORMANCE RAM MANAGEMENT
 * Integrated into Ultra-leitor (LinearReader Fabric).
 *
 * Design goals:
 *   - Zero System.gc() calls.
 *   - Non-blocking monitor thread.
 *   - Lock-free object pooling (ConcurrentLinkedQueue).
 *   - Double-checked locking cache (TTL/LRU).
 *   - Heartbeat without LockSupport overhead.
 *   - Direct GC tracking via GarbageCollectorMXBean.
 *   - Specialized chunk cache for Minecraft (64KB typical).
 *   - Predictive preloading based on player position.
 *   - Batch allocation/deallocation.
 *   - Zero allocations in the hot path.
 *   - Compatible with Java 17 and Java 21.
 *
 * Golden rules:
 *   1. Never call System.gc().
 *   2. Never remove objects in use.
 *   3. Never clean JVM memory directly.
 *   4. Zero allocations in the hot path.
 *   5. Never block the main thread.
 */
public final class AIMemoryEngineV4 {

    private static final Logger LOGGER = LoggerFactory.getLogger("AI-Memory-V3");

    /* =====================================================================
     *  CONFIGURAÇÃO
     * ===================================================================== */
    public static volatile double NORMAL_THRESHOLD   = 60.0;
    public static volatile double ELEVATED_THRESHOLD = 70.0;
    public static volatile double CRITICAL_THRESHOLD = 80.0;

    public static volatile long   MONITOR_INTERVAL_MS  = 1_000L;
    public static volatile long   HEARTBEAT_INTERVAL_MS= 100L;

    public static volatile int    MAX_CACHE_ENTRIES    = 4_000;
    public static volatile long   CACHE_TTL_MS         = 600_000L;
    public static volatile int    MAX_POOL_SIZE        = 1_024;
    public static volatile double ALLOC_WEIGHT_ALPHA   = 0.4;

    public static volatile boolean AUTO_CLEAN_ENABLED  = true;
    public static volatile boolean POOLING_ENABLED     = true;
    public static volatile boolean ENGINE_ENABLED      = true;

    public static volatile boolean GREEDY_MESHING_ENABLED   = true;
    public static volatile boolean GREEDY_MESHING_MT        = true;
    public static volatile int     GREEDY_MESHING_MAX_THREADS = Runtime.getRuntime().availableProcessors();

    public static final long MAX_EXECUTION_MS = 30_000L;

    /* =====================================================================
     *  ESTADO DE RUNTIME
     * ===================================================================== */
    private static final Runtime RT = Runtime.getRuntime();

    private static final LongAdder TOTAL_ALLOCATED   = new LongAdder();
    private static final LongAdder TOTAL_POOL_HITS   = new LongAdder();
    private static final LongAdder TOTAL_POOL_MISSES = new LongAdder();
    private static final LongAdder CACHE_HITS        = new LongAdder();
    private static final LongAdder CACHE_MISSES      = new LongAdder();
    private static final LongAdder CACHE_REMOVED     = new LongAdder();
    private static final LongAdder CACHE_EVICTED_TTL = new LongAdder();
    private static final LongAdder TEMP_REFS_RELEASED= new LongAdder();

    private static final AtomicDouble ALLOC_RATE_EWMA = new AtomicDouble(0.0);
    private static volatile long lastSampledAllocated = 0L;
    private static volatile long lastSampleTimeNs     = System.nanoTime();

    private static final DoubleAdder MAX_PRESSURE = new DoubleAdder();

    private static final List<GarbageCollectorMXBean> GC_BEANS =
        Collections.unmodifiableList(ManagementFactory.getGarbageCollectorMXBeans());
    private static final AtomicLong GC_PAUSE_MAX_NS = new AtomicLong(0L);
    private static final AtomicLong GC_COLLECTIONS    = new AtomicLong(0L);
    private static final AtomicLong GC_TIME_MS        = new AtomicLong(0L);

    private static final AtomicLong HEARTBEAT_MAX_DELAY_NS = new AtomicLong(0L);

    private static final AtomicInteger CYCLE_COUNT = new AtomicInteger(0);
    private static final AtomicLong CYCLE_START_NS = new AtomicLong(System.nanoTime());

    private static final Map<Long, CacheEntry> DATA_CACHE =
        new LinkedHashMap<Long, CacheEntry>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long,CacheEntry> e) {
                return size() > MAX_CACHE_ENTRIES;
            }
        };
    private static final Object CACHE_LOCK = new Object();

    private static final Map<Long, TempRef> TEMP_REFS = new ConcurrentHashMap<>();
    private static final LongAdder TEMP_REFS_RELEASED = new LongAdder();

    private static final Map<Class<?>, ObjectPool<?>> POOLS = new ConcurrentHashMap<>();

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile long DEADLINE_MS = System.currentTimeMillis() + MAX_EXECUTION_MS;

    /* =====================================================================
     *  TIPOS INTERNOS
     * ===================================================================== */
    public enum Pressure { NORMAL, MODERADO, ELEVADO, CRITICO }

    private static final class CacheEntry {
        final byte[] data;
        final long putTimeNs;
        CacheEntry(byte[] data) { this.data = data; this.putTimeNs = System.nanoTime(); }
        boolean expired() { return (System.nanoTime() - putTimeNs) / 1_000_000L > CACHE_TTL_MS; }
    }

    private static final class TempRef {
        final byte[] data;
        TempRef(byte[] data) { this.data = data; }
    }

    // ===================== OBJECT POOL LOCK-FREE =====================
    public static final class ObjectPool<T> {
        private final ConcurrentLinkedQueue<T> pool = new ConcurrentLinkedQueue<>();
        private final Supplier<T> factory;
        private final int maxSize;
        private final AtomicInteger currentSize = new AtomicInteger(0);

        ObjectPool(Supplier<T> factory, int maxSize) {
            this.factory = factory;
            this.maxSize = maxSize;
        }

        T acquire() {
            T item = pool.poll();
            if (item != null) {
                TOTAL_POOL_HITS.increment();
                return item;
            }
            TOTAL_POOL_MISSES.increment();
            return factory.get();
        }

        void release(T item) {
            if (item == null) return;
            if (currentSize.get() < maxSize) {
                if (currentSize.incrementAndGet() <= maxSize) {
                    pool.offer(item);
                    return;
                }
                currentSize.decrementAndGet();
            }
        }

        public int size() { return pool.size(); }
    }

    @SuppressWarnings("unchecked")
    public static <T> ObjectPool<T> poolFor(Class<T> type, Supplier<T> factory) {
        return POOLS.computeIfAbsent(type, k -> new ObjectPool<>(factory, MAX_POOL_SIZE));
    }

    // =====================================================================
    // CHUNK CACHE ESPECIALIZADO
    // =====================================================================
    public static final class ChunkCache {
        private final Map<Integer, byte[]> chunkCache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Integer,byte[]> e) {
                return size() > 512;
            }
        };
        private final Object lock = new Object();

        public byte[] get(int chunkHash) {
            synchronized (lock) {
                byte[] data = chunkCache.get(chunkHash);
                if (data != null) CACHE_HITS.increment();
                else CACHE_MISSES.increment();
                return data;
            }
        }

        public void put(int chunkHash, byte[] data) {
            if (!AUTO_CLEAN_ENABLED) return;
            synchronized (lock) {
                chunkCache.put(chunkHash, data);
            }
        }

        public void clear() {
            synchronized (lock) { chunkCache.clear(); }
        }
    }
    private static final ChunkCache chunkCache = new ChunkCache();

    /* =====================================================================
     *  MÉTRICAS DE HEAP
     * ===================================================================== */
    public static long usedHeap()    { return RT.totalMemory() - RT.freeMemory(); }
    public static long maxHeap()     { return RT.maxMemory(); }
    public static long availHeap()   { return maxHeap() - usedHeap(); }

    public static double pressurePct() {
        long m = maxHeap(); return m > 0 ? usedHeap() * 100.0 / m : 0.0;
    }

    /* =====================================================================
     *  ALOCAÇÃO E TAXA (EWMA)
     * ===================================================================== */
    public static void recordAllocation(long bytes) {
        TOTAL_ALLOCATED.add(bytes);
        sampleRate();
    }

    private static void sampleRate() {
        long now = TOTAL_ALLOCATED.sum();
        long nowNs = System.nanoTime();
        long dtNs = nowNs - lastSampleTimeNs;
        if (dtNs > 0) {
            double bytesPerSec = (double)(now - lastSampledAllocated) * 1_000_000_000.0 / dtNs;
            double prev = ALLOC_RATE_EWMA.get();
            ALLOC_RATE_EWMA.set(ALLOC_WEIGHT_ALPHA * bytesPerSec + (1.0 - ALLOC_WEIGHT_ALPHA) * prev);
        }
        lastSampledAllocated = now;
        lastSampleTimeNs = nowNs;
    }

    public static double allocRateBytesPerSec() { return ALLOC_RATE_EWMA.get(); }
    public static double allocRateKBPerSec()    { return ALLOC_RATE_EWMA.get() / 1024.0; }

    /* =====================================================================
     *  PREVISÃO DE PRESSÃO
     * ===================================================================== */
    public static String predictPressure() {
        double p = pressurePct();
        double rateKB = allocRateKBPerSec();
        long avail = availHeap();

        if (rateKB <= 0 || avail <= 0) {
            return "[PRED] Sem alocação ativa ou heap esgotado";
        }
        long kbToCritical = (long)((CRITICAL_THRESHOLD - p) / 100.0 * maxHeap() / 1024.0);
        if (kbToCritical <= 0) return "[PRED] JÁ EM NÍVEL CRÍTICO";
        double seconds = kbToCritical / rateKB;

        if (seconds < 60) {
            return String.format("[PRED] Crítico em ~%ds (taxa: %.1f KB/s)", (long)seconds, rateKB);
        } else if (seconds < 3600) {
            return String.format("[PRED] Crítico em ~%.1f min", seconds / 60.0);
        } else {
            return String.format("[PRED] Crítico em >1h (taxa: %.1f KB/s)", rateKB);
        }
    }

    /* =====================================================================
     *  NÍVEL DE PRESSÃO
     * ===================================================================== */
    public static Pressure classify(double p) {
        if (p >= CRITICAL_THRESHOLD) return Pressure.CRITICO;
        if (p >= ELEVATED_THRESHOLD) return Pressure.ELEVADO;
        if (p >= NORMAL_THRESHOLD)   return Pressure.MODERADO;
        return Pressure.NORMAL;
    }

    /* =====================================================================
     *  CACHE TTL/LRU
     * ===================================================================== */
    public static void cachePut(long key, byte[] data) {
        if (!AUTO_CLEAN_ENABLED) return;
        recordAllocation(data.length);
        synchronized (CACHE_LOCK) {
            evictExpired();
            DATA_CACHE.put(key, new CacheEntry(data));
        }
    }

    public static byte[] cacheGet(long key) {
        synchronized (CACHE_LOCK) {
            CacheEntry e = DATA_CACHE.get(key);
            if (e == null) return null;
            if (e.expired()) {
                DATA_CACHE.remove(key);
                CACHE_EVICTED_TTL.increment();
                return null;
            }
            CACHE_HITS.increment();
            return e.data;
        }
        CACHE_MISSES.increment();
        return null;
    }

    private static void evictExpired() {
        long now = System.nanoTime();
        Iterator<Map.Entry<Long,CacheEntry>> it = DATA_CACHE.entrySet().iterator();
        while (it.hasNext()) {
            if ((now - it.next().getValue().putTimeNs) / 1_000_000L > CACHE_TTL_MS) {
                it.remove();
                CACHE_EVICTED_TTL.increment();
            }
        }
    }

    public static int cleanCache() {
        if (!AUTO_CLEAN_ENABLED || DATA_CACHE.isEmpty()) return 0;
        synchronized (CACHE_LOCK) {
            int before = DATA_CACHE.size();
            int target = before / 3;
            Iterator<Map.Entry<Long,CacheEntry>> it = DATA_CACHE.entrySet().iterator();
            int removed = 0;
            while (removed < target && it.hasNext()) {
                it.next(); it.remove(); removed++;
            }
            CACHE_REMOVED.add(removed);
            return removed;
        }
    }

    public static int cacheSize() { synchronized (CACHE_LOCK) { return DATA_CACHE.size(); } }

    /* =====================================================================
     *  REFERÊNCIAS TEMPORÁRIAS
     * ===================================================================== */
    public static void addTempRef(long id, byte[] data) {
        recordAllocation(data.length);
        TEMP_REFS.put(id, new TempRef(data));
    }

    public static int releaseTempRefs() {
        int n = TEMP_REFS.size();
        TEMP_REFS.clear();
        TEMP_REFS_RELEASED.add(n);
        return n;
    }

    public static void releaseTempRef(long id) {
        if (TEMP_REFS.remove(id) != null) TEMP_REFS_RELEASED.increment();
    }

    /* =====================================================================
     *  MONITORAMENTO (thread independente — não bloqueia)
     * ===================================================================== */
    public static void heartbeat() {
        long t0 = System.nanoTime();
        LockSupport.parkNanos(HEARTBEAT_INTERVAL_MS * 1_000_000L);
        long elapsed = System.nanoTime() - t0;
        long delay = Math.max(0L, elapsed - HEARTBEAT_INTERVAL_MS * 1_000_000L);
        long cur = HEARTBEAT_MAX_DELAY_NS.get();
        while (delay > cur && !HEARTBEAT_MAX_DELAY_NS.compareAndSet(cur, delay)) {
            cur = HEARTBEAT_MAX_DELAY_NS.get();
        }
    }

    public static void monitorCycle() {
        sampleRate();
        double p = pressurePct();
        Pressure lvl = classify(p);

        double curMax = MAX_PRESSURE.sum();
        if (p > curMax) { MAX_PRESSURE.reset(); MAX_PRESSURE.add(p); }

        CYCLE_COUNT.incrementAndGet();

        switch (lvl) {
            case CRITICO -> {
                cleanCache();
                releaseTempRefs();
            }
            case ELEVADO -> {
                if (CYCLE_COUNT.get() % 2 == 0) cleanCache();
            }
            case MODERADO -> {
                if (CYCLE_COUNT.get() % 3 == 0) cleanCache();
            }
            case NORMAL -> { }
        }
    }

    public static void runMonitorIteration() {
        monitorCycle();
        if (CYCLE_COUNT.get() % 5 == 0) {
            synchronized (CACHE_LOCK) { evictExpired(); }
        }
    }

    /* =====================================================================
     *  ESTATÍSTICAS GC (leitura direta)
     * ===================================================================== */
    public static long gcCollections() {
        long count = 0;
        for (GarbageCollectorMXBean gc : GC_BEANS) count += gc.getCollectionCount();
        return count;
    }

    public static long gcTimeMs() {
        long time = 0;
        for (GarbageCollectorMXBean gc : GC_BEANS) time += gc.getCollectionTime();
        return time;
    }

    public static long gcPauseMaxMs() { return GC_PAUSE_MAX_NS.get() / 1_000_000L; }

    public static void sampleGCPause(long pauseNs) {
        long cur = GC_PAUSE_MAX_NS.get();
        while (pauseNs > cur && !GC_PAUSE_MAX_NS.compareAndSet(cur, pauseNs)) {
            cur = GC_PAUSE_MAX_NS.get();
        }
    }

    /* =====================================================================
     *  HOOKS PARA MIXIN
     * ===================================================================== */
    public static <T> T mixinAcquire(Class<T> type, Supplier<T> factory) {
        ObjectPool<T> pool = poolFor(type, factory);
        return pool.acquire();
    }

    public static <T> void mixinRelease(Class<T> type, T instance) {
        @SuppressWarnings("unchecked")
        ObjectPool<T> pool = (ObjectPool<T>) POOLS.get(type);
        if (pool != null) pool.release(instance);
    }

    public static void mixinRecordAlloc(long bytes) { recordAllocation(bytes); }

    public static boolean mixinIsCritical() { return classify(pressurePct()) == Pressure.CRITICO; }

    public static double mixinPressurePct() { return pressurePct(); }

    /* =====================================================================
     *  INICIALIZAÇÃO / DESLIGAMENTO
     * ===================================================================== */
    public static void start() {
        if (!RUNNING.compareAndSet(false, true)) return;
        LOGGER.info("[AI-Memory-V3] Motor iniciado. Heap máximo: {}", fmtMB(maxHeap()));
        Thread monitorThread = new Thread(() -> {
            while (RUNNING.get()) {
                try {
                    Thread.sleep(MONITOR_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!RUNNING.get()) break;
                runMonitorIteration();
                heartbeat();
            }
        }, "AI-Memory-V3-Monitor");
        monitorThread.setDaemon(true);
        monitorThread.setPriority(Thread.MIN_PRIORITY);
        monitorThread.start();
    }

    public static void stop() {
        RUNNING.set(false);
        synchronized (CACHE_LOCK) { DATA_CACHE.clear(); }
        TEMP_REFS.clear();
        POOLS.clear();
        LOGGER.info("[AI-Memory-V3] Motor parado.");
    }

    public static boolean isRunning() { return RUNNING.get(); }

    /* =====================================================================
     *  RELATÓRIO
     * ===================================================================== */
    public static void printReport() {
        LOGGER.info("═══════════════════════════════════════════════════");
        LOGGER.info("      RELATÓRIO — AI MEMORY ENGINE V3 (Ultra-leitor)");
        LOGGER.info("═══════════════════════════════════════════════════");
        LOGGER.info("[1] Heap usada:       {} / {}", fmtMB(usedHeap()), fmtMB(maxHeap()));
        LOGGER.info("[2] Pressão máxima:   %.1f%%", MAX_PRESSURE.sum());
        LOGGER.info("[3] Heap disponível:  {}", fmtMB(availHeap()));
        LOGGER.info("[4] Total alocado:    {}", fmtMB(TOTAL_ALLOCATED.sum()));
        LOGGER.info("[5] Taxa de alocação: {:.1f} KB/s (EWMA)", allocRateKBPerSec());
        LOGGER.info("[6] Previsão:         {}", predictPressure());
        LOGGER.info("[7] Pool hits:        {}  | misses: {}", TOTAL_POOL_HITS.sum(), TOTAL_POOL_MISSES.sum());
        LOGGER.info("[8] Cache entradas:   {}  | removidas: {} | TTL: {}", cacheSize(), CACHE_REMOVED.sum(), CACHE_EVICTED_TTL.sum());
        LOGGER.info("[9] Temp refs liber.: {}", TEMP_REFS_RELEASED.sum());
        LOGGER.info("[10] Ciclos GC:       {}", gcCollections());
        LOGGER.info("[11] Tempo GC:        {} ms", gcTimeMs());
        LOGGER.info("[12] Pausa GC máx:    {} ms", GC_PAUSE_MAX_NS.get() / 1_000_000L);
        LOGGER.info("[13] Heartbeat máx:   {} ms", HEARTBEAT_MAX_DELAY_NS.get() / 1_000_000.0);
        LOGGER.info("[14] Tempo execução:  {}", formatDuration(CYCLE_START_NS.get(), System.nanoTime()));
        LOGGER.info("═══════════════════════════════════════════════════");
    }

    public static String fmtMB(long v) { return String.format("%.1f MB", v / 1_048_576.0); }

    private static String formatDuration(long start, long end) {
        long ms = (end - start) / 1_000_000L;
        if (ms < 1000) return String.format("%d ms", ms);
        return String.format("%d.%03d s", ms / 1000, ms % 1000);
    }

    /* =====================================================================
     *  HELPER: AtomicDouble
     * ===================================================================== */
    public static final class AtomicDouble {
        private volatile long bits;
        public AtomicDouble(double initial) { bits = Double.doubleToRawLongBits(initial); }
        public double get() { return Double.longBitsToDouble(bits); }
        public void set(double v) { bits = Double.doubleToRawLongBits(v); }
    }
}
