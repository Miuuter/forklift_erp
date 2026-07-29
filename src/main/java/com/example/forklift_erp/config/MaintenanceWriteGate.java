package com.example.forklift_erp.config;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Coordinates application service calls with destructive maintenance work.
 * The fair lock prevents new calls from overtaking a waiting maintenance
 * operation, while the request flag lets callers fail fast instead of
 * applying a stale write after a restore or reset.
 */
@Component
public class MaintenanceWriteGate {
    private static final String MAINTENANCE_MESSAGE = "System maintenance is in progress";

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final AtomicBoolean maintenanceRequested = new AtomicBoolean();
    private final AtomicLong maintenanceGeneration = new AtomicLong();
    private final ThreadLocal<Integer> readDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> exclusiveDepth = ThreadLocal.withInitial(() -> 0);

    public <T> T withBusinessCall(ThrowingSupplier<T> action) throws Throwable {
        boolean nested = readDepth.get() > 0 || exclusiveDepth.get() > 0;
        long observedGeneration = maintenanceGeneration.get();
        if (!nested && maintenanceRequested.get()) {
            throw maintenanceConflict();
        }

        lock.readLock().lock();
        readDepth.set(readDepth.get() + 1);
        try {
            if (!nested && (maintenanceRequested.get()
                    || maintenanceGeneration.get() != observedGeneration)) {
                throw maintenanceConflict();
            }
            return action.get();
        } finally {
            int remaining = readDepth.get() - 1;
            if (remaining == 0) {
                readDepth.remove();
            } else {
                readDepth.set(remaining);
            }
            lock.readLock().unlock();
        }
    }

    public <T> T withExclusiveMaintenance(ThrowingSupplier<T> action) throws Throwable {
        if (readDepth.get() > 0 && exclusiveDepth.get() == 0) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR,
                    "Maintenance cannot start from inside a business call");
        }

        if (exclusiveDepth.get() > 0) {
            exclusiveDepth.set(exclusiveDepth.get() + 1);
            try {
                return action.get();
            } finally {
                exclusiveDepth.set(exclusiveDepth.get() - 1);
            }
        }

        if (!maintenanceRequested.compareAndSet(false, true)) {
            throw maintenanceConflict();
        }
        maintenanceGeneration.incrementAndGet();
        lock.writeLock().lock();
        exclusiveDepth.set(1);
        try {
            return action.get();
        } finally {
            exclusiveDepth.remove();
            lock.writeLock().unlock();
            maintenanceRequested.set(false);
        }
    }

    public boolean isMaintenanceRequested() {
        return maintenanceRequested.get();
    }

    private BusinessException maintenanceConflict() {
        return new BusinessException(ResultCode.CONFLICT, MAINTENANCE_MESSAGE);
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }
}
