package com.inspien.util;

import com.inspien.dto.*;

import java.util.*;

import org.springframework.stereotype.Component;

@Component
public class OrderPreviewMapper {

    public List<OrderDTO> toOrderRows(
        List<OrderHeaderDTO> headers,
        List<OrderItemDTO> items,
        String applicantKey
      ) {

        if (headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException("No HEADER elements found.");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("No ITEM elements found.");
        }
        if (isBlank(applicantKey)) {
            throw new IllegalArgumentException("applicantKey is not configured.");
        }

        Map<String, OrderHeaderDTO> headerMap = new HashMap<>();
        
        for (OrderHeaderDTO h : headers) {
            if (isBlank(h.getUserId())) throw new IllegalArgumentException("HEADER.USER_ID is required.");
            headerMap.put(h.getUserId(), h);
        }

        List<OrderDTO> rows = new ArrayList<>();
        for (OrderItemDTO it : items) {
            if (isBlank(it.getUserId())) throw new IllegalArgumentException("ITEM.USER_ID is required.");
            if (isBlank(it.getItemId())) throw new IllegalArgumentException("ITEM.ITEM_ID is required.");
            if (isBlank(it.getItemName())) throw new IllegalArgumentException("ITEM.ITEM_NAME is required.");
            if (isBlank(it.getPrice()) || !it.getPrice().matches("\\d+")) {
                throw new IllegalArgumentException("ITEM.PRICE must be numeric.");
            }

            OrderHeaderDTO h = headerMap.get(it.getUserId());
            if (h == null) {
                throw new IllegalArgumentException("No matching HEADER for ITEM.USER_ID=" + it.getUserId());
            }

            OrderDTO r = new OrderDTO();
            r.setOrderId("T000"); // 테스트용 OrderId
            r.setUserId(it.getUserId());
            r.setItemId(it.getItemId());
            r.setApplicantKey(applicantKey);
            r.setName(h.getName());
            r.setAddress(h.getAddress());
            r.setItemName(it.getItemName());
            r.setPrice(it.getPrice());
            r.setStatus(isBlank(h.getStatus()) ? "N" : h.getStatus().trim());

            rows.add(r);
        }

        return rows;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
