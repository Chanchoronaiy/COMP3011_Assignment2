/*
 * New file author: Touch Chanchoronaiy (a1978787).
 * PURPOSE: ordered effect stages with bounded hand-offs.
 * WHY: overlap work across cores w/out concurrent calls into one effect's mutable arrays.
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
 * Ordered production line, inspo: Practical 7's paper-plane factory.
 * different stages work on different frames concurrently, but a frame visitsall its effects in the requested order 
 * each effect belongs to ONE stage. This protects the reused arrays in Bleeder, Jitterer and Mottler w/out 
 * putting a giant synchronized block around all CPU work.
 */
final class FramePipeline implements AutoCloseable {
//----------WORK MESSAGE / RESOURCE CLEANUP----------
    // final class cannot be subclassed. AutoCloseable supplies close(), allowing try (FramePipeline p = ...) to clean up when leaving the block.
    // A record groups data and generates accessors such as frame() and info().!! DOES NOT make the referenced native Frame immutable or thread-safe.
    record Work(Frame frame, InfoFrame info, long preparedAtNs, boolean end) {
        static Work endMarker() { return new Work(null, null, 0, true); }
        void release() { if (frame != null) frame.close(); }
    }

//----------SHARED COORDINATION STATE----------
    // BlockingQueue<Work> is a thread-safe FIFO hand-off of Work messages.
    private final List<BlockingQueue<Work>> queues = new ArrayList<>();
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    // AtomicReference lets competing failures conditionally store the FIRST cause in one indivisible compare-and-set. doesn't lock the whole pipeline.
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

//----------PARTITION EFFECTS / START PLATFORM THREADS----------
    // S stages need S+1 queues: input, intermediate hand-offs and final output. 
    // Even no effects uses a pass-through stage to keep one consistent interface.
    FramePipeline(List<FrameProcessor> effects, int workerBudget) {
        int stages = Math.max(1, Math.min(effects.size(), Math.max(1, workerBudget)));
        // Backpressure = full bounded queue slows the producer until the next stage catches up bc
        //  memory must not grow with the length of the movie.
        for (int i = 0; i <= stages; i++) {
            queues.add(new ArrayBlockingQueue<>(2)); // Bounded memory/back pressure (Topic 7).
        }
        for (int i = 0; i < stages; i++) {
            int stage = i;
            // Integer division partitions a contiguous range [first,last)
            // Contiguous groups preserve CLI order. equal effect counts are a simple policy, not equal CPU cost (one Bleeder may dominate a stage)
            int first = i * effects.size() / stages;
            int last = (i + 1) * effects.size() / stages;
            List<FrameProcessor> ownedEffects = List.copyOf(effects.subList(first, last));
            // lambda supplies Runnable.run()    start() creates another control flow
            // calling run() directly would still use the caller's thread.
            // Platform threads can execute CPU work on different cores. Virtual threads help many waiting tasks, not the cost of these pixel loops
            workers.add(Thread.ofPlatform().name("video-effects-" + i).unstarted(
                    () -> runStage(stage, ownedEffects)));
        }
        workers.forEach(Thread::start); // Fully construct the shared structure BEFORE publishing it.
    }

//----------FRAME OWNERSHIP / CRITICAL REGION----------
    // Topic 8 safety: only one stage may mutate a particular frame or effect scratch state at a time. 
    // Successful insertion transfers the frame, sender must NEVER touch its pixels again. Immutable data may be shared.
    // BlockingQueue also publishes earlier writes to the next thread (Java's happens-before guarantee), not just the reference to the pixels
    void submit(Work work) throws InterruptedException {
        // throws propagates an interrupted wait to the producer. finally still releases the frame if ownership was NOT successfully transferred.
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

//----------CANCELLABLE HAND-OFF----------
    // offer(...,50,...) waits at most 50 ms before checking cancellation again.
    // A worker's take() below waits for data; the UI instead uses non-blocking poll().
    private boolean transfer(BlockingQueue<Work> queue, Work work) throws InterruptedException {
        while (!cancelled.get()) {
            if (queue.offer(work, 50, TimeUnit.MILLISECONDS)) return true;
        }
        return false;
    }

//----------ONE WORKER: TAKE -> APPLY IN ORDER -> TRANSFER----------
    // One worker loops serially for its stage, preserving per-effect frame order.
    // Different workers may process DIFFERENT frames at the same time (Practical 7).
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
            // Interrupt is the normal wake-up mechanism during cancellation.
            // Restore the flag because catching InterruptedException clears it.
            if (!cancelled.get()) fail(e);
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            fail(e);
        } finally {
            if (owned != null) owned.release();
        }
    }

//----------FAILURE / CANCELLATION----------
    // Throwable includes exceptions and errors. 
    //record the cause so the model reports the fault instead of treating an abruptly ended stage as normal EOF.
    private void fail(Throwable error) {
        failure.compareAndSet(null, error); // Preserve the original cause, not a later cancellation.
        cancel();
    }

    void cancel() {
        cancelled.set(true); // Atomic/visible to every producer and consumer.
        workers.forEach(Thread::interrupt); // Wake take()/offer() when stopping or seeking.
    }

//----------WAIT AND FREE NATIVE FRAMES----------
    // join() waits for thread termination. Only background lifecycle code uses it.
    void awaitWorkers() throws InterruptedException {
        // Topic 8 deadlock reasoning: no application lock is held, and the UI
        // NEVER calls join(). Its heartbeat keeps draining the final queue.
        for (Thread worker : workers) worker.join();
    }

    @Override public void close() {
        //caller must stop the external producer first (PlaybackSession does this before dispose). Then stop/join stages before draining their queues
        cancel();
        boolean interrupted = false;
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                try { worker.join(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        // Only after producers terminate may leftover native frames be freed
        for (BlockingQueue<Work> queue : queues) {
            Work work;
            while ((work = queue.poll()) != null) work.release();
        }
        // Finish cleanup even if interrupted, then preserve the caller's signal
        if (interrupted) Thread.currentThread().interrupt();
    }
}
