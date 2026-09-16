package com.sun.java.fontmgr;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Thread-safety and client-thread dispatch for decoupled plugins.
 *
 * <p>All automated workflow triggers are queued here and executed only when
 * {@link #pump()} runs on the client's internal execution thread. The drain is
 * hooked at the start of {@code GameEngine.clientTick} (ASM prepend, same
 * idiom as {@code MouseHandler}) so {@link #assertClientThread()} names the
 * real client thread, not the agent-tick poller.
 *
 * <p>{@code TickEngine} must not call {@link #pump()}: that used to mark the
 * {@code agent-tick} daemon as the client thread, which made
 * {@link #assertClientThread()} a no-op and let {@code UiExecutor} (a plain
 * background pool) mutate client state. Delays still use wall-clock
 * {@link #invokeAfter(long, Runnable)} deadlines so AHK-safe inventory gaps
 * are not quantized to a 600 ms game tick; {@code clientTick} runs every
 * client cycle (~20 ms) and only due tasks fire.
 *
 * <p>{@link #pump()} is the <em>sole</em> client-thread marker. There is no
 * host bind-dispatcher: forwarding {@link #invokeLater} to an unmarked sink
 * re-entered work on a thread that was never marked and looped. Hosts must
 * prepend {@link ClientHooks#onClientTick()} via the GameEngine hook.
 *
 * <p>Inter-state delays use a clipped Gaussian (bell-curve) distribution so
 * polling and state transitions are not metronomic.
 *
 * <h3>Integration</h3>
 * <pre>{@code
 * // Injected at the start of GameEngine.clientTick — never from agent-tick:
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

    /**
     * Two game ticks. Pending work with no {@link #pump()} in this window is
     * a missed GameEngine hook, not a slow click.
     */
    public static final long STALL_MS = 1200L;

    /** Cadence window: fewer than three pumps here looks like a 600 ms tick. */
    public static final long CADENCE_WINDOW_MIN_MS = 400L;

    private final Object queueLock = new Object();
    private final List<QueuedTask> queue = new ArrayList<>();

    /** Thread that is allowed to mutate client-facing state (set by {@link #pump()}). */
    private final AtomicReference<Thread> clientThread = new AtomicReference<>();

    /** Successful {@link #pump()} calls — watchdog / tests. */
    private final AtomicLong pumps = new AtomicLong();

    /** Wall clock of the last {@link #pump()}, or 0 if the hook has never fired. */
    private final AtomicLong lastPumpMs = new AtomicLong();

    /** One-shot: queued work before the first pump. Reset with {@link #resetForTest()}. */
    private final AtomicBoolean warnedNeverPumped = new AtomicBoolean();

    private ClientThreadGuard() {}

    public static ClientThreadGuard get() {
        return INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Binding — {@link #pump()} is the sole client-thread marker
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Mark the calling thread as the client execution thread. Only
     * {@link #pump()} (the GameEngine tick hook) calls this.
     */
    private void markClientThread() {
        clientThread.set(Thread.currentThread());
    }

    public boolean isClientThread() {
        Thread marked = clientThread.get();
        return marked != null && marked == Thread.currentThread();
    }

    /** True after {@link #pump()} has marked a thread. */
    public boolean hasClientThread() {
        return clientThread.get() != null;
    }

    public long pumpCount() {
        return pumps.get();
    }

    /** Wall clock of the last {@link #pump()}, or {@code 0} if never pumped. */
    public long lastPumpMs() {
        return lastPumpMs.get();
    }

    /**
     * Pending work with no {@link #pump()} for {@link #STALL_MS} (or never).
     * Independent of {@link #hasClientThread()}: an empty pump latches that
     * flag true and must not hide a later stall.
     */
    public boolean queueIsStalled(long nowMs) {
        if (pendingCount() <= 0) return false;
        long last = lastPumpMs.get();
        if (last == 0L) return true;
        return nowMs - last >= STALL_MS;
    }

    /**
     * True when pump spacing looks like a 600 ms game tick instead of ~20 ms
     * client cycles. {@code TickEngine} uses this so the "gaps bunch" caveat
     * is a runtime WARN, not just a comment.
     */
    public static boolean pumpCadenceLooksLikeGameTick(long pumpsInWindow, long windowMs) {
        return windowMs >= CADENCE_WINDOW_MIN_MS && pumpsInWindow <= 2L;
    }

    /**
     * Fail if this is not the thread that last drained the queue.
     *
     * <p>Throws when no client thread has been marked yet (the GameEngine hook
     * has not run) as well as when a different thread tries to mutate. The
     * previous check skipped the unmarked case, so {@code agent-tick} calling
     * {@link #pump()} made every later caller look legitimate.
     */
    public void assertClientThread() {
        Thread marked = clientThread.get();
        Thread current = Thread.currentThread();
        if (marked == null) {
            throw new IllegalStateException(
                    "client-state mutation before GameEngine tick hook marked a thread: "
                            + current.getName());
        }
        if (marked != current) {
            throw new IllegalStateException(
                    "client-state mutation off client thread: "
                            + current.getName() + " (client=" + marked.getName() + ")");
        }
    }

    /**
     * Test-only: drop queued work and forget the marked thread. Production
     * attach never calls this.
     */
    public void resetForTest() {
        clear();
        clientThread.set(null);
        pumps.set(0L);
        lastPumpMs.set(0L);
        warnedNeverPumped.set(false);
    }

    /**
     * Test-only: pretend the last pump happened {@code agoMs} ago so stall
     * checks can fire without sleeping {@link #STALL_MS}.
     */
    public void rewindLastPumpForTest(long agoMs) {
        lastPumpMs.set(System.currentTimeMillis() - Math.max(0L, agoMs));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Dispatch — queue only; execute on the client thread via {@link #pump()}
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Enqueue work for the client thread. Safe from any thread. Does not spawn
     * a background executor for the action itself. Always sits in
     * {@link #queue} until {@link #pump()} — never forwarded to an external
     * sink that might not be {@link #markClientThread()}ed.
     */
    public void invokeLater(Runnable action) {
        Objects.requireNonNull(action, "action");
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
     * ({@code GameEngine.clientTick} via {@link ClientHooks#onClientTick()}).
     * Do not call from {@code TickEngine} / {@code agent-tick}.
     *
     * @return number of tasks executed
     */
    public int pump() {
        markClientThread();
        long now = System.currentTimeMillis();
        lastPumpMs.set(now);
        pumps.incrementAndGet();
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
        warnIfNeverPumped();
    }

    /**
     * One-shot so a missed GameEngine hook is visible the moment a swap hops
     * onto the queue, not 600 ms later on the agent tick. {@link TickEngine}
     * still repeats the stall if the hook never arrives.
     */
    private void warnIfNeverPumped() {
        if (lastPumpMs.get() != 0L) return;
        if (!warnedNeverPumped.compareAndSet(false, true)) return;
        FontManager.warn("[ClientThreadGuard] queued work before GameEngine.clientTick has pumped; "
                + "if this persists the tick hook missed and swaps will stall. "
                + "pump() is the sole client-thread marker.");
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
