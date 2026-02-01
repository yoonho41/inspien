package com.inspien.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OrderPreviewDTO {
    private String traceId;
    private boolean success;
    private int recordCount;
    private List<OrderDTO> rows;

    // 실패 시 첨부할 메세지
    private String message;
}
