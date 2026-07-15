package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.constant.RentalStatus;
import com.example.forklift_erp.dto.RentalRecordCreateDTO;
import com.example.forklift_erp.dto.RentalRecordUpdateDTO;
import com.example.forklift_erp.dto.RentalRecordVO;
import com.example.forklift_erp.dto.RentalBillVO;
import com.example.forklift_erp.entity.Customer;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.RentalBill;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.CustomerRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.PaymentRecordRepository;
import com.example.forklift_erp.repository.RentalBillRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.FinancialEventService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.RentalRecordService;
import com.example.forklift_erp.service.RentalRevenueCalculator;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.util.BusinessNumberGenerator;
import com.example.forklift_erp.util.ListPageSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

@Service
public class RentalRecordServiceImpl implements RentalRecordService {
    private static final String SOURCE_TYPE = "RENTAL_RECORD";

    @Autowired
    private RentalRecordRepository rentalRecordRepository;

    @Autowired
    private MachineInventoryRepository machineRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CollaborationService collaborationService;

    @Autowired
    private OperationAuditService operationAuditService;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private RentalBillRepository rentalBillRepository;

    @Autowired
    private PaymentRecordRepository paymentRecordRepository;

    @Autowired
    private RentalRevenueCalculator rentalRevenueCalculator;

    @Autowired
    private FinancialEventService financialEventService;

    @Override
    @Transactional(readOnly = true)
    public List<RentalRecordVO> findAll() {
        return rentalRecordRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(RentalRecordVO::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RentalRecordVO> findPage(String keyword, Integer page, Integer size) {
        return findPage(keyword, null, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RentalRecordVO> findPage(String keyword, String status, Integer page, Integer size) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String normalizedStatus = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.now();
        Page<RentalRecord> records = rentalRecordRepository.searchPage(
                normalizedKeyword,
                normalizedStatus,
                today,
                today.plusDays(7),
                ListPageSupport.pageRequest(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return PageResult.of(
                records.getContent().stream().map(RentalRecordVO::fromEntity).toList(),
                records.getNumber(),
                records.getSize(),
                records.getTotalElements()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public RentalRecordVO findById(Long id) {
        return rentalRecordRepository.findById(id)
                .map(RentalRecordVO::fromEntity)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "租赁记录不存在"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RentalBillVO> findBills(Long id) {
        if (!rentalRecordRepository.existsById(id)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "租赁记录不存在");
        }
        return rentalBillRepository.findByRentalIdOrderByBillPeriodAsc(id).stream()
                .map(bill -> RentalBillVO.fromEntity(
                        bill,
                        paymentRecordRepository.totalForSource(
                                FinancialEventService.SOURCE_RENTAL_BILL,
                                bill.getId(),
                                com.example.forklift_erp.entity.PaymentRecord.DIRECTION_RECEIPT
                        )
                ))
                .toList();
    }

    @Override
    @Transactional
    public RentalRecordVO create(RentalRecordCreateDTO request) {
        MachineInventory machine = machineRepository.findByIdForUpdate(request.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "租赁车辆不存在"));
        if (Boolean.TRUE.equals(machine.getModelOnly())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "车型模板不能直接登记租赁，请选择具体库存车号");
        }
        if (Boolean.TRUE.equals(machine.getIsLocked())) {
            throw new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "租赁车辆不存在或已锁定");
        }
        Long warehouseId = stockLedgerService.resolveWarehouseId(request.getWarehouseId());
        if (machine.getWarehouseId() != null && !machine.getWarehouseId().equals(warehouseId)) {
            throw new BusinessException(ResultCode.CONFLICT, "Rental vehicle is not in the selected warehouse");
        }
        int inventoryCount = stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId(), warehouseId);
        if (inventoryCount < 1) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "车辆不在库，不能登记租赁");
        }
        if (!MachineStockStatus.canRent(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle status does not allow rental: " + machine.getStockStatus());
        }
        if (rentalRecordRepository.existsByMachineIdAndStatus(machine.getId(), RentalRecord.STATUS_ACTIVE)) {
            throw new BusinessException(ResultCode.CONFLICT, "该车辆已有进行中的租赁记录");
        }
        collaborationService.validateWrite(machine, request.getMachineVersion());

        RentalRecord record = new RentalRecord();
        record.setRentalNo(nextRentalNo());
        copyMachine(record, machine);
        record.setWarehouseId(warehouseId);
        copyCustomer(record, request.getCustomerId(), request.getDestination());
        BigDecimal monthlyPrice = resolveMonthlyPrice(request.getMonthlyRentalPrice(), request.getRentalPrice());
        record.setMonthlyRentalPrice(monthlyPrice);
        record.setRentalPrice(monthlyPrice);
        record.setStartDate(request.getStartDate() == null ? LocalDate.now() : request.getStartDate());
        record.setEndDate(request.getEndDate());
        record.setReturnDate(null);
        record.setStatus(RentalRecord.STATUS_ACTIVE);
        record.setOperator(blankToNull(request.getOperator()));
        record.setRemark(blankToNull(request.getRemark()));
        collaborationService.stampWrite(record);
        RentalRecord saved = rentalRecordRepository.saveAndFlush(record);
        stockLedgerService.freezeForRental(
                StockLedgerService.RESOURCE_MACHINE,
                machine.getId(),
                machine.getVehicleProductNumber(),
                machine.getName(),
                warehouseId,
                1,
                saved.getOperator(),
                saved.getRemark(),
                SOURCE_TYPE,
                saved.getId(),
                saved.getStartDate()
        );
        machine.setInventoryCount(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId()));
        machine.setStockStatus(MachineStockStatus.RENTED.code());
        collaborationService.stampWrite(machine);
        machineRepository.save(machine);

        operationAuditService.record("租赁管理", "CREATE", "RENTAL_RECORD", saved.getId(),
                saved.getRentalNo(), saved.getVehicleNumber(),
                "新增车辆租赁记录：" + saved.getDestination(), saved.getOperator(), saved.getRemark(),
                SOURCE_TYPE, saved.getId());
        return RentalRecordVO.fromEntity(saved);
    }

    @Override
    @Transactional
    public RentalRecordVO update(Long id, RentalRecordUpdateDTO request) {
        RentalRecord record = rentalRecordRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "租赁记录不存在"));
        collaborationService.validateWrite(record, request.getVersion());

        RentalRecord before = new RentalRecord();
        before.setStatus(record.getStatus());
        before.setWarehouseId(record.getWarehouseId());

        String nextStatus = normalizeStatus(request.getStatus());
        if (RentalRecord.STATUS_ACTIVE.equals(record.getStatus())
                && request.getWarehouseId() != null
                && !request.getWarehouseId().equals(record.getWarehouseId())) {
            throw new BusinessException(ResultCode.CONFLICT, "An active rental cannot change its warehouse; return it first");
        }
        if (!RentalRecord.STATUS_ACTIVE.equals(record.getStatus())
                && RentalRecord.STATUS_ACTIVE.equals(nextStatus)) {
            validateRentalReactivation(record, request.getWarehouseId());
        }

        copyCustomer(record, request.getCustomerId(), request.getDestination());
        BigDecimal monthlyPrice = resolveMonthlyPrice(request.getMonthlyRentalPrice(), request.getRentalPrice());
        record.setMonthlyRentalPrice(monthlyPrice);
        record.setRentalPrice(monthlyPrice);
        record.setStartDate(request.getStartDate());
        record.setEndDate(request.getEndDate());
        if (request.getWarehouseId() != null) {
            record.setWarehouseId(stockLedgerService.resolveWarehouseId(request.getWarehouseId()));
        }
        record.setReturnDate(request.getReturnDate());
        if (RentalRecord.STATUS_RETURNED.equals(nextStatus)) {
            LocalDate returnDate = request.getReturnDate() == null ? request.getEndDate() : request.getReturnDate();
            if (returnDate == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "A returned rental must confirm a return date");
            }
            record.setReturnDate(returnDate);
            record.setEndDate(returnDate);
        }
        record.setStatus(nextStatus);
        record.setOperator(blankToNull(request.getOperator()));
        record.setRemark(blankToNull(request.getRemark()));
        collaborationService.stampWrite(record);
        RentalRecord saved = rentalRecordRepository.saveAndFlush(record);
        if (RentalRecord.STATUS_ACTIVE.equals(before.getStatus()) && RentalRecord.STATUS_RETURNED.equals(saved.getStatus())) {
            releaseRentalVehicle(saved);
            generateRentalBills(saved, true);
        } else if (RentalRecord.STATUS_RETURNED.equals(before.getStatus()) && RentalRecord.STATUS_ACTIVE.equals(saved.getStatus())) {
            freezeRentalVehicle(saved);
        }

        operationAuditService.record("租赁管理", "UPDATE", "RENTAL_RECORD", saved.getId(),
                saved.getRentalNo(), saved.getVehicleNumber(),
                "更新车辆租赁记录：" + before.getStatus() + " -> " + saved.getStatus(),
                saved.getOperator(), saved.getRemark(), SOURCE_TYPE, saved.getId());
        return RentalRecordVO.fromEntity(saved);
    }

    @Override
    @Transactional
    public void delete(Long id, Long version) {
        RentalRecord record = rentalRecordRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "租赁记录不存在"));
        collaborationService.validateWrite(record, version);
        if (RentalRecord.STATUS_ACTIVE.equals(record.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "进行中的租赁记录不能删除，请先办理归还");
        }
        if (!rentalBillRepository.findByRentalIdOrderByBillPeriodAsc(record.getId()).isEmpty()) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "已生成租赁账单的记录不能删除，以免破坏应收和收款历史");
        }
        rentalRecordRepository.delete(record);
        operationAuditService.record("租赁管理", "DELETE", "RENTAL_RECORD", record.getId(),
                record.getRentalNo(), record.getVehicleNumber(),
                "删除车辆租赁记录", record.getOperator(), record.getRemark(), SOURCE_TYPE, record.getId());
    }

    private void copyMachine(RentalRecord record, MachineInventory machine) {
        record.setMachineId(machine.getId());
        record.setVehicleNumber(machine.getVehicleProductNumber());
        record.setMachineName(machine.getName());
        record.setSpecificationModel(machine.getSpecificationModel());
    }

    private void validateRentalReactivation(RentalRecord record, Long requestedWarehouseId) {
        MachineInventory machine = machineRepository.findByIdForUpdate(record.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Rental vehicle does not exist"));
        if (Boolean.TRUE.equals(machine.getModelOnly())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Vehicle model templates cannot be rented");
        }
        if (Boolean.TRUE.equals(machine.getIsLocked())) {
            throw new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Rental vehicle does not exist or is locked");
        }
        if (rentalRecordRepository.existsByMachineIdAndStatus(machine.getId(), RentalRecord.STATUS_ACTIVE)) {
            throw new BusinessException(ResultCode.CONFLICT, "Vehicle already has an active rental record");
        }
        if (!rentalBillRepository.findByRentalIdOrderByBillPeriodAsc(record.getId()).isEmpty()) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "A billed rental cannot be reactivated because its receivable history has already been posted");
        }
        Long warehouseId = stockLedgerService.resolveWarehouseId(
                requestedWarehouseId == null ? record.getWarehouseId() : requestedWarehouseId);
        int inventoryCount = stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId(), warehouseId);
        if (inventoryCount < 1) {
            throw new BusinessException(ResultCode.INSUFFICIENT_STOCK, "Vehicle is not in stock and cannot be rented");
        }
        if (!MachineStockStatus.canRent(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle status does not allow rental: " + machine.getStockStatus());
        }
    }

    private void freezeRentalVehicle(RentalRecord record) {
        MachineInventory machine = machineRepository.findByIdForUpdate(record.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Rental vehicle does not exist"));
        Long warehouseId = stockLedgerService.resolveWarehouseId(record.getWarehouseId());
        stockLedgerService.freezeForRental(
                StockLedgerService.RESOURCE_MACHINE,
                machine.getId(),
                machine.getVehicleProductNumber(),
                machine.getName(),
                warehouseId,
                1,
                record.getOperator(),
                record.getRemark(),
                SOURCE_TYPE,
                record.getId(),
                record.getStartDate()
        );
        record.setWarehouseId(warehouseId);
        machine.setInventoryCount(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId()));
        machine.setStockStatus(MachineStockStatus.RENTED.code());
        collaborationService.stampWrite(machine);
        machineRepository.save(machine);
    }

    private void releaseRentalVehicle(RentalRecord record) {
        MachineInventory machine = machineRepository.findByIdForUpdate(record.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Rental vehicle does not exist"));
        Long warehouseId = stockLedgerService.resolveWarehouseId(record.getWarehouseId());
        LocalDate returnDate = record.getReturnDate() == null ? record.getEndDate() : record.getReturnDate();
        if (returnDate == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "A rental return date is required");
        }
        stockLedgerService.releaseRental(
                StockLedgerService.RESOURCE_MACHINE,
                machine.getId(),
                machine.getVehicleProductNumber(),
                machine.getName(),
                warehouseId,
                1,
                record.getOperator(),
                record.getRemark(),
                SOURCE_TYPE,
                record.getId(),
                returnDate
        );
        machine.setInventoryCount(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId()));
        machine.setStockStatus(machine.getInventoryCount() > 0
                ? MachineStockStatus.IN_STOCK.code()
                : MachineStockStatus.PENDING_INBOUND.code());
        collaborationService.stampWrite(machine);
        machineRepository.save(machine);
    }

    private void generateRentalBills(RentalRecord record, boolean finalBill) {
        LocalDate start = record.getStartDate();
        if (start == null) {
            return;
        }
        LocalDate end;
        if (finalBill) {
            end = record.getReturnDate() == null ? record.getEndDate() : record.getReturnDate();
        } else {
            end = LocalDate.now();
        }
        if (end == null || end.isBefore(start)) {
            return;
        }
        rentalRevenueCalculator.monthlyAmounts(record, start, end).forEach((period, amount) -> {
            LocalDate billPeriod = period.atDay(1);
            if (rentalBillRepository.findByRentalIdAndBillPeriod(record.getId(), billPeriod).isPresent()) {
                return;
            }
            RentalBill bill = new RentalBill();
            bill.setRentalId(record.getId());
            bill.setBillPeriod(billPeriod);
            bill.setBusinessDate(finalBill && period.equals(YearMonth.from(end)) ? end : period.atEndOfMonth());
            bill.setAmount(amount);
            RentalBill savedBill = rentalBillRepository.save(bill);
            var event = financialEventService.postRentalBill(
                    savedBill.getId(),
                    record.getId(),
                    record.getCustomerId(),
                    record.getCustomerName(),
                    savedBill.getBusinessDate(),
                    amount
            );
            savedBill.setFinancialEventId(event.getId());
            rentalBillRepository.save(savedBill);
            record.setFinancialPosted(true);
        });
        rentalRecordRepository.save(record);
    }

    private void copyCustomer(RentalRecord record, Long customerId, String destination) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "租赁客户不存在"));
        record.setCustomerId(customer.getId());
        record.setCustomerName(customer.getCompanyName());
        record.setCustomerAddress(customer.getAddress());
        String resolvedDestination = blankToNull(destination);
        if (resolvedDestination == null) {
            resolvedDestination = blankToNull(customer.getAddress());
        }
        if (resolvedDestination == null) {
            resolvedDestination = customer.getCompanyName();
        }
        record.setDestination(resolvedDestination);
    }

    private BigDecimal resolveMonthlyPrice(BigDecimal monthlyRentalPrice, BigDecimal legacyRentalPrice) {
        BigDecimal price = monthlyRentalPrice != null ? monthlyRentalPrice : legacyRentalPrice;
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "月租价格必须大于0");
        }
        return price;
    }

    private String normalizeStatus(String status) {
        String value = blankToNull(status);
        if (value == null) {
            return RentalStatus.ACTIVE.code();
        }
        if (RentalStatus.isValid(value)) {
            return RentalStatus.normalizeOrDefault(value, RentalStatus.ACTIVE);
        }
        throw new BusinessException(ResultCode.PARAM_ERROR, "租赁状态仅支持 ACTIVE 或 RETURNED");
    }

    private String nextRentalNo() {
        return BusinessNumberGenerator.next("RT", 6);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
