package com.example.forklift_erp.service;

import com.example.forklift_erp.config.MaintenanceOperation;
import com.example.forklift_erp.config.MaintenanceWriteGate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("maintenance-gate-test")
public class MaintenanceGateProbeService {
    private final MaintenanceWriteGate gate;

    public MaintenanceGateProbeService(MaintenanceWriteGate gate) {
        this.gate = gate;
    }

    public String businessCall() {
        return "business";
    }

    @MaintenanceOperation
    public String maintenanceCall() {
        return gate.isMaintenanceRequested() ? "maintenance" : "ungated";
    }
}
