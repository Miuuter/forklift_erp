package com.example.forklift_erp.config;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.service.MaintenanceGateProbeService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(MaintenanceWriteGateTests.TestConfiguration.class)
@ActiveProfiles("maintenance-gate-test")
class MaintenanceWriteGateTests {

    @Test
    void maintenanceWaitsForInFlightCallAndRejectsNewCalls() throws Exception {
        MaintenanceWriteGate gate = new MaintenanceWriteGate();
        CountDownLatch businessEntered = new CountDownLatch(1);
        CountDownLatch releaseBusiness = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> business = executor.submit(() -> invoke(() -> gate.withBusinessCall(() -> {
                businessEntered.countDown();
                releaseBusiness.await(5, TimeUnit.SECONDS);
                return "business";
            })));
            assertThat(businessEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<String> maintenance = executor.submit(() -> invoke(
                    () -> gate.withExclusiveMaintenance(() -> "maintenance")));
            awaitMaintenanceRequested(gate);

            assertThatThrownBy(() -> gate.withBusinessCall(() -> "late"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getCode())
                            .isEqualTo(ResultCode.CONFLICT.getCode()));
            assertThat(maintenance.isDone()).isFalse();

            releaseBusiness.countDown();
            assertThat(business.get(5, TimeUnit.SECONDS)).isEqualTo("business");
            assertThat(maintenance.get(5, TimeUnit.SECONDS)).isEqualTo("maintenance");
        } finally {
            releaseBusiness.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentMaintenanceOperationIsRejected() throws Exception {
        MaintenanceWriteGate gate = new MaintenanceWriteGate();
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = executor.submit(() -> invoke(
                    () -> gate.withExclusiveMaintenance(() -> {
                        release.await(5, TimeUnit.SECONDS);
                        return "first";
                    })));
            awaitMaintenanceRequested(gate);

            assertThatThrownBy(() -> gate.withExclusiveMaintenance(() -> "second"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getCode())
                            .isEqualTo(ResultCode.CONFLICT.getCode()));

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void maintenanceOwnerMayCallNestedBusinessService() throws Throwable {
        MaintenanceWriteGate gate = new MaintenanceWriteGate();

        String result = gate.withExclusiveMaintenance(() ->
                gate.withBusinessCall(() -> "nested"));

        assertThat(result).isEqualTo("nested");
        assertThat(gate.isMaintenanceRequested()).isFalse();
    }

    @Test
    void aspectGatesSpringServiceBeans() {
        MaintenanceGateProbeService probe = probeService;

        assertThat(probe.businessCall()).isEqualTo("business");
        assertThat(probe.maintenanceCall()).isEqualTo("maintenance");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private MaintenanceGateProbeService probeService;

    @org.springframework.beans.factory.annotation.Autowired
    private MaintenanceWriteGate springGate;

    @Test
    void aspectRejectsBusinessServiceDuringMaintenance() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Void> maintenance = executor.submit(() -> invoke(
                    () -> springGate.withExclusiveMaintenance(() -> {
                        entered.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return null;
                    })));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> probeService.businessCall())
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getCode())
                            .isEqualTo(ResultCode.CONFLICT.getCode()));

            release.countDown();
            maintenance.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitMaintenanceRequested(MaintenanceWriteGate gate) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!gate.isMaintenanceRequested() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(gate.isMaintenanceRequested()).isTrue();
    }

    private <T> T invoke(MaintenanceWriteGate.ThrowingSupplier<T> action) throws Exception {
        try {
            return action.get();
        } catch (Exception exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({MaintenanceWriteGate.class, MaintenanceWriteGateAspect.class,
            MaintenanceGateProbeService.class})
    static class TestConfiguration {
    }
}
