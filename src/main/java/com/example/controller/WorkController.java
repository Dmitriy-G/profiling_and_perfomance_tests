package com.example.controller;

import com.example.model.WorkResult;
import com.example.service.WorkService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkController {
    private final WorkService workService;

    public WorkController(WorkService workService) {
        this.workService = workService;
    }

    @PostMapping("/work")
    public WorkResult work(@RequestParam(defaultValue = "100") @Min(1) @Max(1000) int iterations) {
        return workService.simulate(iterations);
    }
}
