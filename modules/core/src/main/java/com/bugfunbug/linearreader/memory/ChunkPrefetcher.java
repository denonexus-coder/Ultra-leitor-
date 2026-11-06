package com.bugfunbug.linearreader.memory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Chunk prefetcher para Ultra-leitor.
 * Pré-carrega regiões vizinhas enquanto o jogador se move.
 * Não bloqueia a thread principal.
 */
public final class ChunkPrefetcher {

    private static final int PREFETCH_RADIUS = 1; // ±1 chunk em cada direção
    private static final int MAX_PREFETCH_QUEUE = 32;
    private static final int MAX_CONCURRENT_LOADS = 4;

    private static final ExecutorService prefetchExecutor =
        Executors.newFixedThreadPool(MAX_CONCURRENT_LOADS, r -> {
            Thread t = new Thread(r, "lr-prefetch");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });

    private static final BlockingQueue<Path> prefetchQueue = new LinkedBlockingQueue<>(MAX_PREFETCH_QUEUE);
    private static final Set<Path> inFlight = ConcurrentHashMap.newKeySet();
    private static final Map<Path, byte[]> prefetchCache = new ConcurrentHashMap<>();
    private static final int PREFETCH_CACHE_MAX = 200;

    private static final AtomicBoolean running = new AtomicBoolean(false);

    public static void start() {
        if (!running.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            while (running.get()) {
                try {
                    Path f = prefetchQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (f == null) continue;
                    if (inFlight.contains(f)) continue;
                    if (!inFlight.add(f)) continue;
                    try {
                        byte[] data = readChunkBytes(f);
                        if (data != null) {
                            synchronized (prefetchCache) {
                                if (prefetchCache.size() >= PREFETCH_CACHE_MAX) {
                                    Iterator<Map.Entry<Path, byte[]>> it = prefetchCache.entrySet().iterator();
                                    if (it.hasNext()) {
                                        it.next();
                                        it.remove();
                                    }
                                }
                                prefetchCache.put(f, data);
                            }
                        }
                    } finally {
                        inFlight.remove(f);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                }
            }
        }, "lr-prefetch-worker");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        worker.start();
    }

    public static void stop() {
        running.set(false);
        prefetchExecutor.shutdownNow();
    }

    public static boolean isRunning() {
        return running.get();
    }

    /**
     * Pré-carrega uma região no background. Não bloqueia.
     */
    public static void prefetch(Path path) {
        if (!running.get()) return;
        if (!inFlight.contains(path) && !prefetchCache.containsKey(path)) {
            prefetchQueue.offer(path);
        }
    }

    /**
     * Retorna dados pré-carregados se disponíveis, null caso contrário.
     */
    public static byte[] getPrefetched(Path path) {
        return prefetchCache.get(path);
    }

    public static void removeFromCache(Path path) {
        prefetchCache.remove(path);
    }

    public static int cacheSize() {
        synchronized (prefetchCache) {
            return prefetchCache.size();
        }
    }

    private static byte[] readChunkBytes(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, java.nio.file.StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < 40L) return null;
            ByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            byte[] data = new byte[(int) fileSize];
            mapped.get(data);
            return data;
        }
    }
}
