package com.example.forklift_erp.constant;

public enum MachineStockStatus implements CodedEnum {
    IN_STOCK,
    PENDING_INBOUND,
    OUTBOUND,
    RENTED,
    PENDING_MODIFICATION,
    MODIFYING,
    PENDING_OUTBOUND;

    @Override
    public String code() {
        return name();
    }

    public static boolean isActiveModification(String status) {
        return PENDING_MODIFICATION.code().equals(status) || MODIFYING.code().equals(status);
    }

    public static boolean canSell(String status) {
        return IN_STOCK.code().equals(status) || PENDING_OUTBOUND.code().equals(status);
    }

    public static boolean canRent(String status) {
        return IN_STOCK.code().equals(status);
    }

    public static boolean canTransfer(String status) {
        return IN_STOCK.code().equals(status) || PENDING_OUTBOUND.code().equals(status);
    }
}
