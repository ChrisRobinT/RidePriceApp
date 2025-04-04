package com.crt.fujitsu.ride_pricing_app.controller;

import com.crt.fujitsu.ride_pricing_app.dto.RidePriceEstimate;
import com.crt.fujitsu.ride_pricing_app.service_logic.PricingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

@RestController
public class PricingController {

    private final PricingService pricingService;

    public PricingController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @GetMapping("/ride-price")
    public ResponseEntity<?> getRidePrice(
            @RequestParam String city,
            @RequestParam String vehicle,
            @RequestParam(required = false) String rideTime) {

        LocalDateTime time;
        try {
            time = (rideTime != null && !rideTime.isEmpty()) ? LocalDateTime.parse(rideTime) : LocalDateTime.now();
        } catch (DateTimeParseException ex) {
            return ResponseEntity.badRequest().body("Invalid rideTime format. Expected ISO 8601 format.");
        }

        RidePriceEstimate estimate = pricingService.calculateRidePrice(city, vehicle, time);
        return ResponseEntity.ok(estimate);
    }
}
