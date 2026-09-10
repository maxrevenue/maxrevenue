package com.sun.java.fontmgr;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Thread-safety and client-thread dispatch for decoupled plugins.
 *
 * <p>All automated workflow triggers are queued here and executed only when
 * {@link #pump()} runs on the client's internal execution thread (tick /
 * invoke-later). Actions are never started on unmanaged background threads.
 *
 * <p>Inter-state delays use a clipped Gaussian (bell-curve) distribution so
 * polling and state transitions are not metronomic.
 *
 * <h3>Integration</h3>
 * <pre>{@code
 * // Once, from the client tick / combat drain path:
 * ClientThreadGuard.get().pump();
 *
 * // From hotkeys, sockets, or UI — never touches client state directly:
 * ClientThreadGuard.get().invokeLater(() -> doAction(...));
 * ClientThreadGuard.get().invokeLaterHuman(180, 45, () -> switchLoadout());
 * }</pre>
 */
public final class ClientThreadGuard {

    private static final ClientThreadGuard INSTANCE = new ClientThreadGuard();

    /** Hard cap on pending work to avoid unbounded growth under attach storms. */
    private static final int MAX_QUEUE = 256;

    private final Object queueLock = new Object();
    private final List<QueuedTask> queue = new ArrayList<>();

    /**
     * Optional host-provided sink. When set, {@link #invokeLater} forwards
     * immediately to the client's own invoke-later; when null, tasks sit in
     * {@link #queue} until {@link #pump()}.
     */
    private volatile Consumer<Runnable> clientDispatcher;

    /** Thread that is allowed to mutate client-facing state (set by host). */
    private final AtomicReference<Thread> clientThread = new AtomicReference<>();

    private ClientThreadGuard() {}

    public static ClientThreadGuard get() {
        return INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Binding — host wires the client execution thread
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Bind an external client invoke-later (e.g. RuneLite {@code ClientThread.invoke}).
     * Pass {@code null} to rely solely on {@link #pump()}.
     */
    public void bindDispatcher(Consumer<Runnable> dispatcher) {
        this.clientDispatcher = dispatcher;
    }

    /**
     * Mark the calling thread as the client execution thread. Call once from
     * the tick / drain loop so {@link #assertClientThread()} can enforce affinity.
     */
    public void markClientThread() {
        clientThread.set(Thread.currentThread());
    }

    public boolean isClientThread() {
        Thread marked = clientThread.get();
        return marked != null && marked == Thread.currentThread();
    }

    public void assertClientThread() {
        Thread marked = clientThread.get();
        if (marked != null && marked != Thread.currentThread()) {
            throw new IllegalStateException(
                    "client-state mutation off client thread: "
                            + Thread.currentThread().getName());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Dispatch — queue only; execute on client thread via pump / dispatcher
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Enqueue work for the client thread. Safe from any thread. Does not spawn
     * a background executor for the action itself.
     */
    public void invokeLater(Runnable action) {
        Objects.requireNonNull(action, "action");
        Consumer<Runnable> sink = clientDispatcher;
        if (sink != null) {
            try {
                sink.accept(wrapSafe(action));
                return;
            } catch (Throwable t) {
                FontManager.log("[ClientThreadGuard] dispatcher reject: " + t.getMessage());
                // Fall through to internal queue.
            }
        }
        enqueue(0L, action);
    }

    /**
     * Enqueue with a human-like Gaussian delay before the task becomes runnable.
     * Delay is measured in milliseconds from {@link System#currentTimeMillis()};
     * {@link #pump()} skips tasks whose deadline has not arrived.
     *
     * @param meanMs   center of the bell curve (must be &gt; 0)
     * @param stdDevMs spread; larger = more human variance
     */
    public void invokeLaterHuman(long meanMs, long stdDevMs, Runnable action) {
        Objects.requireNonNull(action, "action");
        long delay = gaussianDelayMs(meanMs, stdDevMs);
        enqueue(System.currentTimeMillis() + delay, action);
    }

    /**
     * Convenience: Gaussian delay with mean {@code meanMs} and σ = mean/4
     * (typical human reaction spread), clipped to a sane floor/ceiling.
     */
    public void invokeLaterHuman(long meanMs, Runnable action) {
        long sigma = Math.max(8L, meanMs / 4L);
        invokeLaterHuman(meanMs, sigma, action);
    }

    /**
     * Gear-swap timing profile — mean from {@link Humanizer#gearSwapMeanMs()},
     * always above Roat AHK thresholds.
     */
    public void invokeLaterGearSwap(Runnable action) {
        long mean = Humanizer.gearSwapMeanMs();
        invokeLaterHuman(mean, Math.max(10L, mean / 4L), action);
    }

    /**
     * Enqueue with a <em>fixed</em> delay (no extra Gaussian). Use this when
     * the caller already chose the gap — e.g. swap lines that must stay in
     * order and above Roat's 70ms inventory-click detector.
     */
    public void invokeAfter(long delayMs, Runnable action) {
        Objects.requireNonNull(action, "action");
        enqueue(System.currentTimeMillis() + Math.max(0L, delayMs), action);
    }

    /** Inter-equip gap; always {@code > 70} so {@code AhkDetection} stays quiet. */
    public static long ahkSafeInvGapMs() {
        return Humanizer.invGapMs();
    }

    /** First inventory click of a swap chain. */
    public static long firstInvClickDelayMs() {
        return Humanizer.firstEquipDelayMs();
    }

    /** Tiny ordered gap for prayers/spec/attack that can share a game tick. */
    public static long sameTickGapMs() {
        return Humanizer.sameTickClickGapMs();
    }

    /**
     * Drain due tasks. <b>Must</b> be called from the client's execution thread
     * (tick listener, combat drain, or host invoke-later loop).
     *
     * @return number of tasks executed
     */
    public int pump() {
        markClientThread();
        long now = System.currentTimeMillis();
        List<QueuedTask> ready;
        synchronized (queueLock) {
            if (queue.isEmpty()) return 0;
            ready = new ArrayList<>(queue.size());
            List<QueuedTask> deferred = new ArrayList<>(queue.size());
            for (QueuedTask t : queue) {
                if (t.notBeforeMs <= now) ready.add(t);
                else deferred.add(t);
            }
            queue.clear();
            queue.addAll(deferred);
        }
        int ran = 0;
        for (QueuedTask t : ready) {
            try {
                t.action.run();
                ran++;
            } catch (Throwable ex) {
                FontManager.log("[ClientThreadGuard] task failed: " + ex.getMessage());
            }
        }
        return ran;
    }

    /** Drop all pending work (pause / detach / emergency stop). */
    public void clear() {
        synchronized (queueLock) {
            queue.clear();
        }
    }

    public int pendingCount() {
        synchronized (queueLock) {
            return queue.size();
        }
    }

    private void enqueue(long notBeforeMs, Runnable action) {
        synchronized (queueLock) {
            if (queue.size() >= MAX_QUEUE) {
                FontManager.log("[ClientThreadGuard] queue full; dropping task");
                return;
            }
            queue.add(new QueuedTask(notBeforeMs, wrapSafe(action)));
        }
    }

    private static Runnable wrapSafe(Runnable action) {
        return () -> {
            try {
                action.run();
            } catch (Throwable t) {
                FontManager.log("[ClientThreadGuard] " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        };
    }

    private static final class QueuedTask {
        final long notBeforeMs;
        final Runnable action;

        QueuedTask(long notBeforeMs, Runnable action) {
            this.notBeforeMs = notBeforeMs;
            this.action = action;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Human-like delays — clipped Gaussian (Box–Muller)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Sample a delay from {@code N(mean, stdDev²)}, clipped to
     * {@code [max(1, mean - 3σ), mean + 3σ]} so outliers never collapse to
     * a tight poll loop or an unbounded sleep.
     */
    public static long gaussianDelayMs(long meanMs, long stdDevMs) {
        if (meanMs <= 0L) return 1L;
        double sigma = Math.max(1.0, stdDevMs);
        // Box–Muller
        double u1 = ThreadLocalRandom.current().nextDouble();
        double u2 = ThreadLocalRandom.current().nextDouble();
        // Avoid log(0)
        u1 = Math.max(u1, 1e-12);
        double z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
        double sample = meanMs + z * sigma;
        double lo = Math.max(1.0, meanMs - 3.0 * sigma);
        double hi = meanMs + 3.0 * sigma;
        if (sample < lo) sample = lo;
        if (sample > hi) sample = hi;
        return Math.round(sample);
    }

    /**
     * Busy-wait free: returns how many ms remain before {@code sinceMs + delay}
     * elapses, or {@code 0} if the interval has passed. Use from {@link #pump()}
     * gates instead of spinning.
     */
    public static long remainingMs(long sinceMs, long delayMs) {
        long due = sinceMs + Math.max(0L, delayMs);
        long left = due - System.currentTimeMillis();
        return Math.max(0L, left);
    }

    public static boolean intervalElapsed(long sinceMs, long delayMs) {
        return remainingMs(sinceMs, delayMs) == 0L;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Null-safety wrappers
    // ════════════════════════════════════════════════════════════════════════

    public static <T> T require(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    public static <T> T orElse(T value, T fallback) {
        return value != null ? value : fallback;
    }

    public static <T> T orElseGet(T value, Supplier<T> fallback) {
        return value != null ? value : fallback.get();
    }

    /** Map when non-null; otherwise return {@code null} without NPE. */
    public static <T, R> R mapNullable(T value, Function<T, R> mapper) {
        return value == null ? null : mapper.apply(value);
    }

    /** Run only when {@code value} is non-null. */
    public static <T> void ifPresent(T value, Consumer<T> consumer) {
        if (value != null) consumer.accept(value);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Locked state cell — protects plugin runtime consistency
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Mutable reference guarded by a read/write lock. Use for cross-thread
     * readable plugin state that is only written from the client thread (or
     * under {@link #write}).
     */
    public static final class StateLock<T> {
        private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        private T value;

        public StateLock() {
            this(null);
        }

        public StateLock(T initial) {
            this.value = initial;
        }

        public T get() {
            rw.readLock().lock();
            try {
                return value;
            } finally {
                rw.readLock().unlock();
            }
        }

        public T getOr(T fallback) {
            T v = get();
            return v != null ? v : fallback;
        }

        public void set(T next) {
            rw.writeLock().lock();
            try {
                value = next;
            } finally {
                rw.writeLock().unlock();
            }
        }

        /** Compare-and-set under the write lock. */
        public boolean compareAndSet(T expect, T update) {
            rw.writeLock().lock();
            try {
                if (value != expect && (value == null || !value.equals(expect))) {
                    return false;
                }
                value = update;
                return true;
            } finally {
                rw.writeLock().unlock();
            }
        }

        public void write(Consumer<StateLock<T>> mutator) {
            rw.writeLock().lock();
            try {
                mutator.accept(this);
            } finally {
                rw.writeLock().unlock();
            }
        }

        public <R> R read(Function<T, R> view) {
            rw.readLock().lock();
            try {
                return view.apply(value);
            } finally {
                rw.readLock().unlock();
            }
        }

        /** Mutate non-null state; no-op when currently null. */
        public void ifPresentWrite(Consumer<T> mutator) {
            rw.writeLock().lock();
            try {
                if (value != null) mutator.accept(value);
            } finally {
                rw.writeLock().unlock();
            }
        }
    }

    /**
     * Run {@code body} under {@code lock}'s monitor. Null-safe: no-ops when
     * either argument is null.
     */
    public static void withLock(Object lock, Runnable body) {
        if (lock == null || body == null) return;
        synchronized (lock) {
            body.run();
        }
    }

    public static <T> T withLock(Object lock, Supplier<T> body, T onNull) {
        if (lock == null || body == null) return onNull;
        synchronized (lock) {
            T result = body.get();
            return result != null ? result : onNull;
        }
    }
}
