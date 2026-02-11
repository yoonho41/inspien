package com.inspien.web;

import com.inspien.service.AdminReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminReceiptController {

    private final AdminReceiptService adminReceiptService;

    @PostMapping(
            value = "/receipts/retry",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> retryByTraceId(
        @RequestBody String xml,
        @RequestHeader(value = "adminkey", required = false) String adminKey
    ) {
        return ResponseEntity.ok(adminReceiptService.retryByTraceId(xml, adminKey));
    }
}
