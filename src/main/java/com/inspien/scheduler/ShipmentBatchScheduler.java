package com.inspien.scheduler;

import com.inspien.service.ShipmentBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentBatchScheduler {

    private final ShipmentBatchService shipmentBatchService;

    /**
     * 동일 배치가 겹쳐 실행되는 것을 방지, pool.size=2 로 설정했기 때문에 충돌 X
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 5분(fixedDelay) 주기
     * - 처음에는 30초 뒤 실행, 실행이 끝난 뒤 5분 후 재실행
     */
    @Scheduled(
      initialDelayString = "${inspien.shipment.batch.initialDelayMs:30000}",
      fixedDelayString = "${inspien.shipment.batch.fixedDelayMs:300000}"
    )
    public void run() {
        if (!lock.tryLock()) {
            log.warn("Shipment batch skipped: previous run still in progress.");
            return;
        }
        try {
            shipmentBatchService.runOnce();
        } catch (Exception e) {
            // 예외가 나면 @Transactional로 롤백되어 STATUS=N 그대로 남아 다음 배치에서 재처리 가능
            log.error("Shipment batch failed: {}", e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }
}
