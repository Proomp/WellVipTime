package com.wellsetups.wellviptime.storage;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AsyncWork implements AutoCloseable {

    @FunctionalInterface
    public interface Job<T> {

        T run() throws java.sql.SQLException, java.io.IOException;
    }

    private final ThreadPoolExecutor executor;

    private final int shutdownSeconds;

    private final AtomicBoolean closing = new AtomicBoolean();

    private final java.util.Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    public AsyncWork(int workers, int queueSize, int shutdownSeconds) {
        this.shutdownSeconds = shutdownSeconds;
        executor =
                new ThreadPoolExecutor(
                        workers,
                        workers,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(queueSize),
                        job -> {
                            Thread thread = new Thread(job, "WellVipTime-IO");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<T> submit(Job<T> job) {
        if (closing.get()) {
            return CompletableFuture.failedFuture(
                    new RejectedExecutionException("WellVipTime is stopping"));
        }
        var result = new CompletableFuture<T>();
        pending.add(result);
        result.whenComplete((ignored, error) -> pending.remove(result));
        try {
            executor.execute(
                    () -> {
                        try {
                            result.complete(job.run());
                        } catch (java.sql.SQLException | java.io.IOException | RuntimeException e) {
                            result.completeExceptionally(e);
                        } catch (Error error) {
                            // A fatal worker failure must not leave API callers waiting forever.
                            result.completeExceptionally(error);
                            throw error;
                        }
                    });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    public boolean closing() {
        return closing.get();
    }

    @Override
    public void close() {
        closing.set(true);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pending.forEach(
                future ->
                        future.completeExceptionally(
                                new RejectedExecutionException(
                                        "WellVipTime shutdown; check durable operation status"
                                                + " before retrying")));
    }
}
