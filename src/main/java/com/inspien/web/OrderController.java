package com.inspien.web;

import com.inspien.dto.OrderPreviewDTO;
import com.inspien.service.OrderService;
import com.inspien.util.OrderPreviewMapper;
import com.inspien.util.OrderXmlParser;
import lombok.RequiredArgsConstructor;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrderController {

    @Value("${inspien.applicant-key}")
    private String applicantKey;

    private final OrderXmlParser xmlParser;
    private final OrderPreviewMapper previewMapper;

    private final OrderService orderService;

    @PostMapping(
            value = "/orders/preview",
            produces = MediaType.APPLICATION_JSON_VALUE
    )   
    public ResponseEntity<?> preview(@RequestBody String xml) {
        String traceId = MDC.get("traceId");

        try {
            var parsed = xmlParser.parse(xml);
            var rows = previewMapper.toOrderRows(parsed.headers(), parsed.items(), applicantKey);

            return ResponseEntity.ok(
                    OrderPreviewDTO.builder()
                            .traceId(traceId)
                            .success(true)
                            .recordCount(rows.size())
                            .rows(rows)
                            .build()
            );

        } catch (IllegalArgumentException e) {

            return ResponseEntity.badRequest().body(
                    OrderPreviewDTO.builder()
                            .traceId(traceId)
                            .success(false)
                            .recordCount(0)
                            .rows(null)
                            .message(e.getMessage())
                            .build()
            );
        }
    }

    @PostMapping(
            value = "/orders",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> createOrder(@RequestBody String xml) {
        String traceId = MDC.get("traceId");

        try {
            return ResponseEntity.ok(orderService.create(xml));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "traceId", traceId,
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }
}
