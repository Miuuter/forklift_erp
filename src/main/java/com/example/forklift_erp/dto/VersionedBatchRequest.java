package com.example.forklift_erp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class VersionedBatchRequest {
    @Valid
    @NotEmpty(message = "Batch items are required")
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        @NotNull(message = "Item ID is required")
        private Long id;

        @NotNull(message = "Item version is required")
        private Long version;
    }
}
