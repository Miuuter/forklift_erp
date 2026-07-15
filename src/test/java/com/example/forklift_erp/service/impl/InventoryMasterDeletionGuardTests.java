package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.Customer;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ConfigReplaceLogRepository;
import com.example.forklift_erp.repository.CustomerRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RepairPartUsageRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.ResourceAttachmentRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import com.example.forklift_erp.repository.StocktakingRecordRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.MachineConfigService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.StockLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryMasterDeletionGuardTests {

    private static final Long RESOURCE_ID = 77L;

    @Test
    void machineDeletionRejectsEveryHistoricalReference() {
        List<GuardCase<MachineFixture>> cases = List.of(
                new GuardCase<>("purchase",
                        fixture -> when(fixture.purchaseOrderRepository.existsByResourceTypeAndResourceId(
                                StockLedgerService.RESOURCE_MACHINE, RESOURCE_ID)).thenReturn(true),
                        "Vehicle has purchase records and cannot be deleted"),
                new GuardCase<>("outbound",
                        fixture -> when(fixture.outboundOrderRepository.existsByResourceTypeAndResourceId(
                                StockLedgerService.RESOURCE_MACHINE, RESOURCE_ID)).thenReturn(true),
                        "Vehicle has outbound records and cannot be deleted"),
                new GuardCase<>("rental",
                        fixture -> when(fixture.rentalRecordRepository.existsByMachineId(RESOURCE_ID)).thenReturn(true),
                        "Vehicle has rental records and cannot be deleted"),
                new GuardCase<>("repair",
                        fixture -> when(fixture.repairRecordRepository.existsByMachineId(RESOURCE_ID)).thenReturn(true),
                        "Vehicle has repair records and cannot be deleted"),
                new GuardCase<>("modification",
                        fixture -> when(fixture.modificationWorkOrderRepository.existsByMachineId(RESOURCE_ID)).thenReturn(true),
                        "Vehicle has modification records and cannot be deleted"),
                new GuardCase<>("configuration replacement",
                        fixture -> when(fixture.configReplaceLogRepository.existsByMachineId(RESOURCE_ID)).thenReturn(true),
                        "Vehicle has configuration replacement records and cannot be deleted"),
                new GuardCase<>("removed part source",
                        fixture -> when(fixture.partInventoryRepository.existsBySourceMachineId(RESOURCE_ID)).thenReturn(true),
                        "Vehicle is referenced as the source of a part and cannot be deleted"),
                new GuardCase<>("stocktaking",
                        fixture -> when(fixture.stocktakingRecordRepository.existsByResourceTypeAndResourceId(
                                StockLedgerService.RESOURCE_MACHINE, RESOURCE_ID)).thenReturn(true),
                        "Vehicle has stocktaking records and cannot be deleted"),
                new GuardCase<>("attachment",
                        fixture -> when(fixture.resourceAttachmentRepository
                                .existsByResourceTypeAndResourceIdAndDeletedFalse(
                                        StockLedgerService.RESOURCE_MACHINE, RESOURCE_ID)).thenReturn(true),
                        "Vehicle has active attachments and cannot be deleted"),
                new GuardCase<>("remaining FIFO inventory",
                        fixture -> when(fixture.stockLotRepository
                                .existsByResourceTypeAndResourceIdAndRemainingQuantityGreaterThan(
                                        StockLedgerService.RESOURCE_MACHINE, RESOURCE_ID, 0)).thenReturn(true),
                        "Vehicle has remaining FIFO inventory and cannot be deleted")
        );

        for (GuardCase<MachineFixture> guardCase : cases) {
            MachineFixture fixture = machineFixture();
            guardCase.configure().accept(fixture);

            assertConflict(() -> fixture.service.deleteById(RESOURCE_ID), guardCase.message(), guardCase.name());
            verify(fixture.machineInventoryRepository, never()).deleteById(RESOURCE_ID);
            verify(fixture.machineConfigService, never()).deleteByMachineId(RESOURCE_ID);
        }
    }

    @Test
    void partDeletionRejectsEveryHistoricalReference() {
        List<GuardCase<PartFixture>> cases = List.of(
                new GuardCase<>("purchase",
                        fixture -> when(fixture.purchaseOrderRepository.existsByResourceTypeAndResourceId(
                                StockLedgerService.RESOURCE_PART, RESOURCE_ID)).thenReturn(true),
                        "Part has purchase records and cannot be deleted"),
                new GuardCase<>("outbound",
                        fixture -> when(fixture.outboundOrderRepository.existsByResourceTypeAndResourceId(
                                StockLedgerService.RESOURCE_PART, RESOURCE_ID)).thenReturn(true),
                        "Part has outbound records and cannot be deleted"),
                new GuardCase<>("modification",
                        fixture -> when(fixture.modificationWorkOrderLineRepository.existsByNewPartId(RESOURCE_ID)).thenReturn(true),
                        "Part has modification usage records and cannot be deleted"),
                new GuardCase<>("repair",
                        fixture -> when(fixture.repairPartUsageRepository.existsByPartId(RESOURCE_ID)).thenReturn(true),
                        "Part has repair usage records and cannot be deleted"),
                new GuardCase<>("configuration replacement",
                        fixture -> when(fixture.configReplaceLogRepository.existsByNewPartId(RESOURCE_ID)).thenReturn(true),
                        "Part has configuration replacement records and cannot be deleted"),
                new GuardCase<>("stocktaking",
                        fixture -> when(fixture.stocktakingRecordRepository.existsByResourceTypeAndResourceId(
                                StockLedgerService.RESOURCE_PART, RESOURCE_ID)).thenReturn(true),
                        "Part has stocktaking records and cannot be deleted"),
                new GuardCase<>("attachment",
                        fixture -> when(fixture.resourceAttachmentRepository
                                .existsByResourceTypeAndResourceIdAndDeletedFalse(
                                        StockLedgerService.RESOURCE_PART, RESOURCE_ID)).thenReturn(true),
                        "Part has active attachments and cannot be deleted"),
                new GuardCase<>("remaining FIFO inventory",
                        fixture -> when(fixture.stockLotRepository
                                .existsByResourceTypeAndResourceIdAndRemainingQuantityGreaterThan(
                                        StockLedgerService.RESOURCE_PART, RESOURCE_ID, 0)).thenReturn(true),
                        "Part has remaining FIFO inventory and cannot be deleted")
        );

        for (GuardCase<PartFixture> guardCase : cases) {
            PartFixture fixture = partFixture();
            guardCase.configure().accept(fixture);

            assertConflict(() -> fixture.service.deleteById(RESOURCE_ID), guardCase.message(), guardCase.name());
            verify(fixture.partInventoryRepository, never()).deleteById(RESOURCE_ID);
        }
    }

    @Test
    void customerDeletionRejectsOutboundRentalAndRepairReferences() {
        List<GuardCase<CustomerFixture>> cases = List.of(
                new GuardCase<>("outbound",
                        fixture -> when(fixture.outboundOrderRepository.existsByCustomerId(RESOURCE_ID)).thenReturn(true),
                        "Customer has outbound orders and cannot be deleted"),
                new GuardCase<>("rental",
                        fixture -> when(fixture.rentalRecordRepository.existsByCustomerId(RESOURCE_ID)).thenReturn(true),
                        "Customer has rental records and cannot be deleted"),
                new GuardCase<>("repair",
                        fixture -> when(fixture.repairRecordRepository.existsByCustomerId(RESOURCE_ID)).thenReturn(true),
                        "Customer has repair records and cannot be deleted")
        );

        for (GuardCase<CustomerFixture> guardCase : cases) {
            CustomerFixture fixture = customerFixture();
            guardCase.configure().accept(fixture);

            assertConflict(() -> fixture.service.delete(RESOURCE_ID, 4L), guardCase.message(), guardCase.name());
            verify(fixture.customerRepository, never()).delete(fixture.customer);
        }
    }

    @Test
    void unusedMasterRecordsCanStillBeDeleted() {
        MachineFixture machine = machineFixture();
        PartFixture part = partFixture();
        CustomerFixture customer = customerFixture();

        machine.service.deleteById(RESOURCE_ID);
        part.service.deleteById(RESOURCE_ID);
        customer.service.delete(RESOURCE_ID, 4L);

        verify(machine.stockLedgerService).deleteEmptyBalances(StockLedgerService.RESOURCE_MACHINE, RESOURCE_ID);
        verify(machine.machineConfigService).deleteByMachineId(RESOURCE_ID);
        verify(machine.machineInventoryRepository).deleteById(RESOURCE_ID);
        verify(part.stockLedgerService).deleteEmptyBalances(StockLedgerService.RESOURCE_PART, RESOURCE_ID);
        verify(part.partInventoryRepository).deleteById(RESOURCE_ID);
        verify(customer.customerRepository).delete(customer.customer);
    }

    private MachineFixture machineFixture() {
        MachineInventoryRepository machineInventoryRepository = mock(MachineInventoryRepository.class);
        PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        OutboundOrderRepository outboundOrderRepository = mock(OutboundOrderRepository.class);
        RentalRecordRepository rentalRecordRepository = mock(RentalRecordRepository.class);
        RepairRecordRepository repairRecordRepository = mock(RepairRecordRepository.class);
        ModificationWorkOrderRepository modificationWorkOrderRepository = mock(ModificationWorkOrderRepository.class);
        ConfigReplaceLogRepository configReplaceLogRepository = mock(ConfigReplaceLogRepository.class);
        PartInventoryRepository partInventoryRepository = mock(PartInventoryRepository.class);
        StockLotRepository stockLotRepository = mock(StockLotRepository.class);
        StocktakingRecordRepository stocktakingRecordRepository = mock(StocktakingRecordRepository.class);
        ResourceAttachmentRepository resourceAttachmentRepository = mock(ResourceAttachmentRepository.class);
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);
        MachineConfigService machineConfigService = mock(MachineConfigService.class);

        MachineInventory machine = new MachineInventory();
        machine.setId(RESOURCE_ID);
        machine.setIsLocked(false);
        when(machineInventoryRepository.findByIdAndIsLockedFalseForUpdate(RESOURCE_ID))
                .thenReturn(Optional.of(machine));

        MachineInventoryServiceImpl service = new MachineInventoryServiceImpl();
        ReflectionTestUtils.setField(service, "repository", machineInventoryRepository);
        ReflectionTestUtils.setField(service, "purchaseOrderRepository", purchaseOrderRepository);
        ReflectionTestUtils.setField(service, "outboundOrderRepository", outboundOrderRepository);
        ReflectionTestUtils.setField(service, "rentalRecordRepository", rentalRecordRepository);
        ReflectionTestUtils.setField(service, "repairRecordRepository", repairRecordRepository);
        ReflectionTestUtils.setField(service, "modificationWorkOrderRepository", modificationWorkOrderRepository);
        ReflectionTestUtils.setField(service, "configReplaceLogRepository", configReplaceLogRepository);
        ReflectionTestUtils.setField(service, "partInventoryRepository", partInventoryRepository);
        ReflectionTestUtils.setField(service, "stockLotRepository", stockLotRepository);
        ReflectionTestUtils.setField(service, "stocktakingRecordRepository", stocktakingRecordRepository);
        ReflectionTestUtils.setField(service, "resourceAttachmentRepository", resourceAttachmentRepository);
        ReflectionTestUtils.setField(service, "stockLedgerService", stockLedgerService);
        ReflectionTestUtils.setField(service, "machineConfigService", machineConfigService);
        ReflectionTestUtils.setField(service, "visibilityPolicy", new ResourceVisibilityPolicy());
        ReflectionTestUtils.setField(service, "deletionGuard", new InventoryMasterDeletionGuard(
                purchaseOrderRepository,
                outboundOrderRepository,
                rentalRecordRepository,
                repairRecordRepository,
                modificationWorkOrderRepository,
                mock(ModificationWorkOrderLineRepository.class),
                mock(RepairPartUsageRepository.class),
                configReplaceLogRepository,
                partInventoryRepository,
                stocktakingRecordRepository,
                resourceAttachmentRepository,
                stockLotRepository
        ));

        return new MachineFixture(
                service,
                machineInventoryRepository,
                purchaseOrderRepository,
                outboundOrderRepository,
                rentalRecordRepository,
                repairRecordRepository,
                modificationWorkOrderRepository,
                configReplaceLogRepository,
                partInventoryRepository,
                stockLotRepository,
                stocktakingRecordRepository,
                resourceAttachmentRepository,
                stockLedgerService,
                machineConfigService
        );
    }

    private PartFixture partFixture() {
        PartInventoryRepository partInventoryRepository = mock(PartInventoryRepository.class);
        PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        OutboundOrderRepository outboundOrderRepository = mock(OutboundOrderRepository.class);
        ModificationWorkOrderLineRepository modificationWorkOrderLineRepository =
                mock(ModificationWorkOrderLineRepository.class);
        RepairPartUsageRepository repairPartUsageRepository = mock(RepairPartUsageRepository.class);
        ConfigReplaceLogRepository configReplaceLogRepository = mock(ConfigReplaceLogRepository.class);
        StockLotRepository stockLotRepository = mock(StockLotRepository.class);
        StocktakingRecordRepository stocktakingRecordRepository = mock(StocktakingRecordRepository.class);
        ResourceAttachmentRepository resourceAttachmentRepository = mock(ResourceAttachmentRepository.class);
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);

        PartInventory part = new PartInventory();
        part.setId(RESOURCE_ID);
        part.setIsLocked(false);
        when(partInventoryRepository.findByIdAndIsLockedFalseForUpdate(RESOURCE_ID))
                .thenReturn(Optional.of(part));

        PartInventoryServiceImpl service = new PartInventoryServiceImpl();
        ReflectionTestUtils.setField(service, "partRepository", partInventoryRepository);
        ReflectionTestUtils.setField(service, "purchaseOrderRepository", purchaseOrderRepository);
        ReflectionTestUtils.setField(service, "outboundOrderRepository", outboundOrderRepository);
        ReflectionTestUtils.setField(service, "modificationWorkOrderLineRepository", modificationWorkOrderLineRepository);
        ReflectionTestUtils.setField(service, "repairPartUsageRepository", repairPartUsageRepository);
        ReflectionTestUtils.setField(service, "configReplaceLogRepository", configReplaceLogRepository);
        ReflectionTestUtils.setField(service, "stockLotRepository", stockLotRepository);
        ReflectionTestUtils.setField(service, "stocktakingRecordRepository", stocktakingRecordRepository);
        ReflectionTestUtils.setField(service, "resourceAttachmentRepository", resourceAttachmentRepository);
        ReflectionTestUtils.setField(service, "stockLedgerService", stockLedgerService);
        ReflectionTestUtils.setField(service, "visibilityPolicy", new ResourceVisibilityPolicy());
        ReflectionTestUtils.setField(service, "deletionGuard", new InventoryMasterDeletionGuard(
                purchaseOrderRepository,
                outboundOrderRepository,
                mock(RentalRecordRepository.class),
                mock(RepairRecordRepository.class),
                mock(ModificationWorkOrderRepository.class),
                modificationWorkOrderLineRepository,
                repairPartUsageRepository,
                configReplaceLogRepository,
                partInventoryRepository,
                stocktakingRecordRepository,
                resourceAttachmentRepository,
                stockLotRepository
        ));

        return new PartFixture(
                service,
                partInventoryRepository,
                purchaseOrderRepository,
                outboundOrderRepository,
                modificationWorkOrderLineRepository,
                repairPartUsageRepository,
                configReplaceLogRepository,
                stockLotRepository,
                stocktakingRecordRepository,
                resourceAttachmentRepository,
                stockLedgerService
        );
    }

    private CustomerFixture customerFixture() {
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        OutboundOrderRepository outboundOrderRepository = mock(OutboundOrderRepository.class);
        RentalRecordRepository rentalRecordRepository = mock(RentalRecordRepository.class);
        RepairRecordRepository repairRecordRepository = mock(RepairRecordRepository.class);

        Customer customer = new Customer();
        customer.setId(RESOURCE_ID);
        customer.setVersion(4L);
        customer.setCompanyName("Test customer");
        when(customerRepository.findByIdForUpdate(RESOURCE_ID)).thenReturn(Optional.of(customer));

        CustomerServiceImpl service = new CustomerServiceImpl();
        ReflectionTestUtils.setField(service, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(service, "outboundOrderRepository", outboundOrderRepository);
        ReflectionTestUtils.setField(service, "rentalRecordRepository", rentalRecordRepository);
        ReflectionTestUtils.setField(service, "repairRecordRepository", repairRecordRepository);
        ReflectionTestUtils.setField(service, "collaborationService", mock(CollaborationService.class));
        ReflectionTestUtils.setField(service, "operationAuditService", mock(OperationAuditService.class));

        return new CustomerFixture(
                service,
                customerRepository,
                outboundOrderRepository,
                rentalRecordRepository,
                repairRecordRepository,
                customer
        );
    }

    private void assertConflict(Runnable action, String message, String caseName) {
        assertThatThrownBy(action::run)
                .as(caseName)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessage(message);
    }

    private record GuardCase<T>(String name, Consumer<T> configure, String message) {
    }

    private record MachineFixture(
            MachineInventoryServiceImpl service,
            MachineInventoryRepository machineInventoryRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            OutboundOrderRepository outboundOrderRepository,
            RentalRecordRepository rentalRecordRepository,
            RepairRecordRepository repairRecordRepository,
            ModificationWorkOrderRepository modificationWorkOrderRepository,
            ConfigReplaceLogRepository configReplaceLogRepository,
            PartInventoryRepository partInventoryRepository,
            StockLotRepository stockLotRepository,
            StocktakingRecordRepository stocktakingRecordRepository,
            ResourceAttachmentRepository resourceAttachmentRepository,
            StockLedgerService stockLedgerService,
            MachineConfigService machineConfigService
    ) {
    }

    private record PartFixture(
            PartInventoryServiceImpl service,
            PartInventoryRepository partInventoryRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            OutboundOrderRepository outboundOrderRepository,
            ModificationWorkOrderLineRepository modificationWorkOrderLineRepository,
            RepairPartUsageRepository repairPartUsageRepository,
            ConfigReplaceLogRepository configReplaceLogRepository,
            StockLotRepository stockLotRepository,
            StocktakingRecordRepository stocktakingRecordRepository,
            ResourceAttachmentRepository resourceAttachmentRepository,
            StockLedgerService stockLedgerService
    ) {
    }

    private record CustomerFixture(
            CustomerServiceImpl service,
            CustomerRepository customerRepository,
            OutboundOrderRepository outboundOrderRepository,
            RentalRecordRepository rentalRecordRepository,
            RepairRecordRepository repairRecordRepository,
            Customer customer
    ) {
    }
}
