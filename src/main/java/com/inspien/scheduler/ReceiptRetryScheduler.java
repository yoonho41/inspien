package com.inspien.scheduler;

import com.inspien.dto.OrderDTO;
import com.inspien.infra.*;
import com.inspien.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReceiptRetryScheduler {

    private final ReceiptOutbox outbox;
    private final SftpUploader sftpUploader;
    private final OrderMapper orderMapper;

    @Value("${inspien.sftp.retry.maxAttempts:10}")
    private int maxAttempts;

    @Value("${inspien.sftp.retry.initialDelayMs:90000}")
    private long initialRetryDelayMs;

    private final ReentrantLock lock = new ReentrantLock();

    @Scheduled(fixedDelayString = "${inspien.sftp.retry.fixedDelayMs:60000}")
    public void retryPending() {
        if (!lock.tryLock()) return;
        try {
            outbox.ensureDirs();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(outbox.pendingDir(), "*.meta.json")) {
                for (Path metaPath : stream) {
                    ReceiptMetaDTO meta = outbox.readMeta(metaPath);

                    // 재시도 로그에도 아까 실패했던 요청의 traceId를 적용해서 추적을 용이하게 함
                    MDC.put("traceId", meta.getTraceId());
                    try {
                        if (meta.getAttempts() == 0 && (meta.getLastError() == null || meta.getLastError().isBlank())) {
                            continue; // 실패 이력 없는 영수증이면 혹시 모를 작업 충돌 방지를 위해서 스킵
                        }

                        long now = Instant.now().toEpochMilli();
                        if (meta.getNextAttemptAtEpochMs() > now) continue;

                        // 영수증 파일이 없으면 DB에서 재생성 시도
                        Path receiptFile = outbox.receiptPathInPending(meta.getFileName());
                        if (!Files.exists(receiptFile)) {
                            log.warn("Receipt file missing. Will recreate from DB. fileName={}", meta.getFileName());

                            List<OrderDTO> rows = orderMapper.selectOrdersByIds(meta.getApplicantKey(), meta.getOrderIds());
                            if (rows == null || rows.isEmpty()) {
                                // DB에서도 못 찾으면 사실상 재전송 불가능, failed로 이동
                                log.error("Cannot recreate receipt: DB rows not found. orderIds={}", meta.getOrderIds());
                                outbox.markFailed(meta.getFileName());
                                continue;
                            }

                            String content = buildReceiptContent(rows);
                            outbox.writeReceiptToPending(meta.getFileName(), content);
                            log.info("Receipt file recreated. fileName={}", meta.getFileName());
                        }

                        // SFTP 재전송
                        try {
                            log.info("SFTP RETRY start. fileName={}, attempts={}", meta.getFileName(), meta.getAttempts());
                            sftpUploader.upload(receiptFile, meta.getFileName());
                            log.info("SFTP RETRY success. fileName={}", meta.getFileName());

                            outbox.markSent(meta.getFileName());
                        } catch (Exception e) {
                            handleRetryFail(meta, metaPath, e);
                        }

                    } finally {
                        MDC.remove("traceId");
                    }
                }
            }

        } catch (Exception e) {
            log.error("ReceiptRetryScheduler failed: {}", e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    // maxAttempts 만큼 전송 재시도, 최대 횟수 도달 시 failed로 이동
    private void handleRetryFail(ReceiptMetaDTO meta, Path metaPath, Exception e) {
        int nextAttempts = meta.getAttempts() + 1;
        meta.setAttempts(nextAttempts);
        meta.setLastError(e.getMessage());

        if (nextAttempts >= maxAttempts) {
            log.error("SFTP RETRY final-fail. fileName={}, attempts={}, msg={}",
                    meta.getFileName(), nextAttempts, e.getMessage());
            outbox.updateMeta(metaPath, meta);
            outbox.markFailed(meta.getFileName());
        } else {
            meta.setNextAttemptAtEpochMs(System.currentTimeMillis());
            outbox.updateMeta(metaPath, meta);
            log.warn("SFTP RETRY fail (will retry). fileName={}, attempts={}, nextAt={}, msg={}",
                    meta.getFileName(), nextAttempts, meta.getNextAttemptAtEpochMs(), e.getMessage());
        }
    }

    // DB 조회 결과를 기반으로 아까 생성에 실패했던 영수증을 재생성
    private String buildReceiptContent(List<OrderDTO> rows) {
        return rows.stream()
                .map(r -> String.join("^",
                        r.getOrderId(),
                        r.getUserId(),
                        r.getItemId(),
                        r.getApplicantKey(),
                        r.getName(),
                        r.getAddress(),
                        r.getItemName(),
                        r.getPrice()
                ))
                .collect(Collectors.joining("\n")) + "\n";
    }
}
