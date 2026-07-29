package com.example.forklift_erp;

import com.example.forklift_erp.dto.MachineInventoryCreateDTO;
import com.example.forklift_erp.dto.ModificationWorkOrderCreateDTO;
import com.example.forklift_erp.dto.OutboundOrderUpdateDTO;
import com.example.forklift_erp.dto.PartInventoryCreateDTO;
import com.example.forklift_erp.dto.PartOutboundOrderCreateDTO;
import com.example.forklift_erp.dto.PaymentRecordCreateDTO;
import com.example.forklift_erp.dto.PurchaseOrderDTO;
import com.example.forklift_erp.dto.RentalRecordCreateDTO;
import com.example.forklift_erp.dto.RentalRecordUpdateDTO;
import com.example.forklift_erp.dto.RepairRecordCreateDTO;
import com.example.forklift_erp.dto.RepairPartUsageDTO;
import com.example.forklift_erp.dto.VehicleOutboundOrderCreateDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DtoMoneyValidationTests {
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void machineInventoryRejectsNegativePricesAndQuantity() {
        MachineInventoryCreateDTO dto = new MachineInventoryCreateDTO();
        dto.setName("Forklift");
        dto.setSpecificationModel("CPD30");
        dto.setPurchasePrice(amount("-1"));
        dto.setSalePrice(amount("-2"));
        dto.setSettlementPrice(amount("-3"));
        dto.setInventoryCount(-1);

        assertInvalidProperties(dto, "purchasePrice", "salePrice", "settlementPrice", "inventoryCount");
    }

    @Test
    void partInventoryRejectsNegativePrices() {
        PartInventoryCreateDTO dto = new PartInventoryCreateDTO();
        dto.setPartCode("P-001");
        dto.setPartName("Filter");
        dto.setQuantity(0);
        dto.setPurchasePrice(amount("-1"));
        dto.setSalePrice(amount("-2"));
        dto.setSettlementPrice(amount("-3"));

        assertInvalidProperties(dto, "purchasePrice", "salePrice", "settlementPrice");
    }

    @Test
    void repairRecordRejectsNegativeAmounts() {
        RepairRecordCreateDTO dto = new RepairRecordCreateDTO();
        dto.setRepairDate(LocalDateTime.now());
        dto.setFaultDescription("Check hydraulic leak");
        dto.setWorkHours(amount("-1"));
        dto.setRepairFee(amount("-2"));
        dto.setRepairExpense(amount("-3"));
        dto.setPartsFee(amount("-4"));
        dto.setTotalFee(amount("-5"));

        assertInvalidProperties(dto, "workHours", "repairFee", "repairExpense", "partsFee", "totalFee");
    }

    @Test
    void outboundOrderDtosRejectNegativeAmounts() {
        VehicleOutboundOrderCreateDTO vehicleDto = new VehicleOutboundOrderCreateDTO();
        vehicleDto.setMachineId(1L);
        vehicleDto.setCustomerId(1L);
        vehicleDto.setSettlementPrice(amount("-1"));
        vehicleDto.setSalePrice(amount("-2"));
        vehicleDto.setReceivableAmount(amount("-3"));
        vehicleDto.setReceivedAmount(amount("-4"));
        assertInvalidProperties(vehicleDto, "settlementPrice", "salePrice", "receivableAmount", "receivedAmount");

        PartOutboundOrderCreateDTO partDto = new PartOutboundOrderCreateDTO();
        partDto.setPartCode("P-001");
        partDto.setQuantity(1);
        partDto.setCustomerId(1L);
        partDto.setSettlementPrice(amount("-1"));
        partDto.setReceivableAmount(amount("-2"));
        partDto.setReceivedAmount(amount("-3"));
        assertInvalidProperties(partDto, "settlementPrice", "receivableAmount", "receivedAmount");

        OutboundOrderUpdateDTO updateDto = new OutboundOrderUpdateDTO();
        updateDto.setSettlementPrice(amount("-1"));
        updateDto.setSalePrice(amount("-2"));
        updateDto.setReceivableAmount(amount("-3"));
        updateDto.setReceivedAmount(amount("-4"));
        assertInvalidProperties(updateDto, "settlementPrice", "salePrice", "receivableAmount", "receivedAmount");
    }

    @Test
    void purchaseAndRentalDtosRejectNegativeAmounts() {
        PurchaseOrderDTO purchaseDto = new PurchaseOrderDTO();
        purchaseDto.setQuantity(1);
        purchaseDto.setUnitPrice(amount("-1"));
        purchaseDto.setTotalAmount(amount("-2"));
        purchaseDto.setFreightAmount(amount("-3"));
        assertInvalidProperties(purchaseDto, "unitPrice", "totalAmount", "freightAmount");

        RentalRecordCreateDTO rentalCreateDto = new RentalRecordCreateDTO();
        rentalCreateDto.setMachineId(1L);
        rentalCreateDto.setCustomerId(1L);
        rentalCreateDto.setMonthlyRentalPrice(amount("1"));
        rentalCreateDto.setRentalPrice(amount("-1"));
        assertInvalidProperties(rentalCreateDto, "rentalPrice");

        RentalRecordUpdateDTO rentalUpdateDto = new RentalRecordUpdateDTO();
        rentalUpdateDto.setCustomerId(1L);
        rentalUpdateDto.setMonthlyRentalPrice(amount("1"));
        rentalUpdateDto.setRentalPrice(amount("-1"));
        assertInvalidProperties(rentalUpdateDto, "rentalPrice");
    }

    @Test
    void moneyDtosRejectSilentDatabaseRoundingAndOverflow() {
        MachineInventoryCreateDTO machine = new MachineInventoryCreateDTO();
        machine.setPurchasePrice(amount("1.001"));
        assertInvalidProperties(machine, "purchasePrice");

        PaymentRecordCreateDTO payment = new PaymentRecordCreateDTO();
        payment.setAmount(amount("12.345"));
        assertInvalidProperties(payment, "amount");

        OutboundOrderUpdateDTO outbound = new OutboundOrderUpdateDTO();
        outbound.setLineAmount(amount("1234567890123.45"));
        assertInvalidProperties(outbound, "lineAmount");

        RepairPartUsageDTO usage = new RepairPartUsageDTO();
        usage.setChargeUnitPrice(amount("1.999"));
        assertInvalidProperties(usage, "chargeUnitPrice");

        ModificationWorkOrderCreateDTO.Line line = new ModificationWorkOrderCreateDTO.Line();
        line.setOldPartUnitCost(amount("10000000000.00"));
        assertInvalidProperties(line, "oldPartUnitCost");
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private static void assertInvalidProperties(Object dto, String... expectedProperties) {
        Set<String> invalidProperties = validator.validate(dto).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertThat(invalidProperties).contains(expectedProperties);
    }
}
