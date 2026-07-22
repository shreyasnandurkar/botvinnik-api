package com.shreyasnandurkar.botvinnikapi.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class DashboardController {

    @GetMapping("/dashboard")
    public ResponseEntity<Void> dashboard() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/dashboard/index.html"))
                .build();
    }
}
