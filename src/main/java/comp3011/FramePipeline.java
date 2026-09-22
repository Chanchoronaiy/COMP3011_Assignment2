/*
 * New Assignment 2 code generated with Codex for Touch Chanchoronaiy (a1978787).
 * Student review and understanding are required before submission.
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bytedeco.javacv.Frame;

/**
 * Ordered production line, inspired by Practical 7's paper-plane factory.
 * Different stages work on different frames concurrently, but a frame visits
 * all its effects in the requested order. Each effect belongs to ONE stage.
 * This protects the reused arrays in Bleeder, Jitterer and Mottler without
 * putting a giant synchronized block around all CPU work.
 */
final class FramePipeline implements AutoCloseable {
    record Work(Frame frame, InfoFrame info, long preparedAtNs, boolean end) {
        static Work endMarker() { return new Work(null, null, 0, true); }
        void release() { if (frame != null) frame.close(); }
    }

    private final List<BlockingQueue<Work>> queues = new ArrayList<>();
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    FramePipeline(List<FrameProcessor> effects, int workerBudget) {
        int stages = Math.max(1, Math.min(effects.size(), Math.max(1, workerBudget)));
        for (int i = 0; i <= stages; i++) {
            queues.add(new ArrayBlockingQueue<>(2)); // Bounded memory / back pressure (Topic 7).
        }
        for (int i = 0; i < stages; i++) {
            int stage = i;
            int first = i * effects.size() / stages;
            int last = (i + 1) * effects.size() / stages;
            List<FrameProcessor> ownedEffects = List.copyOf(effects.subList(first, last));
            // A lambda supplies Runnable.run(). start() creates another control
            // flow; calling run() directly would still use the caller's thread.
            workers.add(Thread.ofPlatform().name("video-effects-" + i).unstarted(
                    () -> runStage(stage, ownedEffects)));
        }
        workers.forEach(Thread::start); // Fully construct the shared structure BEFORE publishing it.
    }

    //----------FRAME OWNERSHIP / CRITICAL REGION----------
    // Topic 8: a resource must not have two owners. Successful queue insertion
    // transfers ownership: the sender must NEVER touch that frame again.
    // BlockingQueue also publishes earlier writes to the next thread (Java's
    // happens-before guarantee), not just the reference to the pixels.
    // Source: Java SE BlockingQueue API, linked in README.
    void submit(Work work) throws InterruptedException {
        boolean transferred = false;
        try {
            transferred = transfer(queues.getFirst(), work);
        } finally {
            if (!transferred) work.release();
        }
    }

    Work poll() { return queues.getLast().poll(); } // Never wait on the JavaFX UI thread.
    Throwable failure() { return failure.get(); }
    int workerCount() { return workers.size(); }

    private boolean transfer(BlockingQueue<Work> queue, Work work) throws InterruptedException {
        while (!cancelled.get()) {
            if (queue.offer(work, 50, TimeUnit.MILLISECONDS)) return true;
        }
        return false;
    }

    private void runStage(int stage, List<FrameProcessor> effects) {
        Work owned = null;
        try {
            while (!cancelled.get()) {
                owned = queues.get(stage).take(); // Wait efficiently rather than busy-spinning.
                if (!owned.end()) {
                    for (FrameProcessor effect : effects) {
                        if (cancelled.get()) return;
                        effect.process(owned.frame(), owned.info());
                    }
                    if (stage == workers.size() - 1) {
                        owned = new Work(owned.frame(), owned.info(), System.nanoTime(), false);
                    }
                }
                boolean end = owned.end();
                if (!transfer(queues.get(stage + 1), owned)) return;
                owned = null; // The NEXT stage now owns it.
                if (end) return; // End travels AFTER every frame, preserving FIFO order.
            }
        } catch (InterruptedException e) {
            if (!cancelled.get()) fail(e);
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            fail(e);
        } finally {
            if (owned != null) owned.release();
        }
    }

    private void fail(Throwable error) {
        failure.compareAndSet(null, error); // Preserve the original cause, not a later cancellation.
        cancel();
    }

    void cancel() {
        cancelled.set(true); // Atomic/visible to every producer and consumer.
        workers.forEach(Thread::interrupt); // Wake take()/offer() when stopping or seeking.
    }

    void awaitWorkers() throws InterruptedException {
        // Topic 8 deadlock reasoning: no application lock is held, and the UI
        // NEVER calls join(). Its heartbeat keeps draining the final queue.
        for (Thread worker : workers) worker.join();
    }

    @Override public void close() {
        cancel();
        boolean interrupted = false;
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                try { worker.join(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        // Only after producers terminate may leftover native frames be freed.
        for (BlockingQueue<Work> queue : queues) {
            Work work;
            while ((work = queue.poll()) != null) work.release();
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
