package com.inspien.service;

import com.inspien.dto.OrderDTO;
import com.inspien.dto.ShipmentDTO;
import com.inspien.mapper.ShipmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentBatchService {

    private final ShipmentMapper shipmentMapper;

    @Value("${inspien.applicant-key}")
    private String applicantKey;

    @Value("${inspien.shipment.batch.fetchLimit:200}")
    private int fetchLimit;

    /**
     * 1회 배치 실행 단위 :
     *  ORDER_TB select(행 잠금) -> SHIPMENT_TB insert -> ORDER_TB update를 하나의 트랜잭션으로 묶음
     *  중간에 실패 시 롤백되어 재처리 가능(STATUS가 N인 채로 남음)
     */
    @Transactional
    public int runOnce() {
        // 배치 실행도 로그 추적이 쉽도록 traceId 형태로 MDC에 넣어줌
        String batchTraceId = "SHIPBATCH-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        MDC.put("traceId", batchTraceId);

        try {
            log.info("Shipment batch start. applicantKey={}, fetchLimit={}", applicantKey, fetchLimit);

            // 1) 미전송 주문 조회 + lock
            List<OrderDTO> orders = shipmentMapper.selectUnsentOrdersForUpdate(applicantKey, fetchLimit);

            if (orders == null || orders.isEmpty()) {
                log.info("Shipment batch end: no target rows.");
                return 0;
            }

            // 2) 위에서 조회해온 데이터를 SHIPMENT_TB 형식에 맞게 변환 후 insert
            List<ShipmentDTO> shipments = orders.stream().map(o -> {
                ShipmentDTO s = new ShipmentDTO();
                s.setShipmentId(o.getOrderId()); // ORDER_ID 재사용
                s.setOrderId(o.getOrderId());
                s.setItemId(o.getItemId());
                s.setApplicantKey(o.getApplicantKey());
                s.setAddress(o.getAddress());
                return s;
            }).toList();

            shipmentMapper.insertShipments(shipments);

            // 3) ORDER_TB STATUS를 'Y'로 update
            List<String> orderIds = orders.stream().map(OrderDTO::getOrderId).toList();
            int updated = shipmentMapper.updateOrderStatusY(applicantKey, orderIds);

            log.info("Shipment batch success. inserted={}, updated={}", shipments.size(), updated);
            return shipments.size();

        } finally {
            MDC.remove("traceId");
        }
    }
}
