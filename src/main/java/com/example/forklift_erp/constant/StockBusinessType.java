package com.example.forklift_erp.constant;

/**
 * Business meaning of a stock movement. The movement direction remains in the
 * line quantity; this code prevents financial reports from guessing meaning
 * from generic INBOUND/OUTBOUND/ADJUST labels.
 */
public final class StockBusinessType {
    public static final String INITIAL_BALANCE = "INITIAL_BALANCE";
    public static final String OTHER_INBOUND = "OTHER_INBOUND";
    public static final String OTHER_OUTBOUND = "OTHER_OUTBOUND";
    public static final String PURCHASE_RECEIPT = "PURCHASE_RECEIPT";
    public static final String PURCHASE_RECEIPT_REVERSAL = "PURCHASE_RECEIPT_REVERSAL";
    public static final String SALE_OUTBOUND = "SALE_OUTBOUND";
    public static final String REPAIR_USE = "REPAIR_USE";
    public static final String REPAIR_RESTORE = "REPAIR_RESTORE";
    public static final String MODIFICATION_USE = "MODIFICATION_USE";
    public static final String MODIFICATION_RETURN = "MODIFICATION_RETURN";
    public static final String STOCKTAKING_GAIN = "STOCKTAKING_GAIN";
    public static final String STOCKTAKING_LOSS = "STOCKTAKING_LOSS";
    public static final String STOCK_ADJUSTMENT = "STOCK_ADJUSTMENT";
    public static final String TRANSFER = "TRANSFER";
    public static final String RENT_OUT = "RENT_OUT";
    public static final String RENT_RETURN = "RENT_RETURN";

    private StockBusinessType() {
    }
}
