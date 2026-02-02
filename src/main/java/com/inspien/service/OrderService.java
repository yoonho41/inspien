package com.inspien.service;

import com.inspien.dto.OrderDTO;
import com.inspien.infra.ReceiptMetaDTO;
import com.inspien.infra.ReceiptOutbox;
import com.inspien.infra.SftpUploader;
import com.inspien.mapper.OrderMapper;
import com.inspien.util.OrderPreviewMapper;
import com.inspien.util.OrderXmlParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final PlatformTransactionManager txManager;

    private final OrderXmlParser xmlParser = new OrderXmlParser();
    private final OrderPreviewMapper previewMapper = new OrderPreviewMapper();

    @Value("${inspien.applicant-key}")
    private String applicantKey;

    private static final int MAX_RETRY = 5;
    private static final int CHUNK_SIZE = 200;


    private final SftpUploader sftpUploader;

    @Value("${inspien.receipt.participant-name}")
    private String participantName;

    @Value("${inspien.receipt.local-dir}")
    private String receiptLocalDir;


    private final ReceiptOutbox receiptOutbox;

    @Value("${inspien.sftp.retry.maxAttempts:10}")
    private int maxAttempts;


    public Map<String, Object> create(String xml) {
        String traceId = MDC.get("traceId");

        var parsed = xmlParser.parse(xml);

        // 테스트에서 사용했던 toOrderRows 재사용
        List<OrderDTO> rows = previewMapper.toOrderRows(parsed.headers(), parsed.items(), applicantKey);

        insertWithId(rows);

        String receiptFileName = buildReceiptFileName();

        ReceiptMetaDTO meta = new ReceiptMetaDTO();
        meta.setTraceId(traceId);
        meta.setApplicantKey(applicantKey);
        meta.setFileName(receiptFileName);
        meta.setOrderIds(rows.stream().map(OrderDTO::getOrderId).collect(Collectors.toList()));
        meta.setAttempts(0);
        meta.setNextAttemptAtEpochMs(System.currentTimeMillis());
        meta.setLastError(null);

        receiptOutbox.writeMetaToPending(meta);

        boolean isReceiptCreated = false;

        try {
            String content = buildReceiptContent(rows);
            receiptOutbox.writeReceiptToPending(receiptFileName, content);
            isReceiptCreated = true;
        } catch (Exception e) {
            // 파일 생성 실패해도 meta가 있으므로 스케줄러가 DB로 재생성 가능
            meta.setAttempts(1);
            meta.setLastError("RECEIPT_CREATE_FAIL: " + e.getMessage());
            meta.setNextAttemptAtEpochMs(receiptOutbox.calcNextAttemptAt(meta.getAttempts()));
            receiptOutbox.updateMeta(receiptOutbox.metaPathInPending(receiptFileName), meta);

            log.error("Receipt create failed. Will retry via scheduler. traceId={}, fileName={}, msg={}",
                    traceId, receiptFileName, e.getMessage(), e);
        }

        boolean sftpUploaded = false;

        if (isReceiptCreated) {
            Path pendingFile = receiptOutbox.receiptPathInPending(receiptFileName);

            try {
                sftpUploader.upload(pendingFile, receiptFileName);
                sftpUploaded = true;

                // 성공하면 sent로 이동
                receiptOutbox.markSent(receiptFileName);

            } catch (Exception e) {
                // SFTP 업로드 실패하면 pending에 남기고 스케줄러가 재시도
                int nextAttempts = meta.getAttempts() + 1;
                meta.setAttempts(nextAttempts);
                meta.setLastError("SFTP_FAIL: " + e.getMessage());

                if (nextAttempts >= maxAttempts) {
                    receiptOutbox.updateMeta(receiptOutbox.metaPathInPending(receiptFileName), meta);
                    receiptOutbox.markFailed(receiptFileName);

                    log.error("SFTP final-fail. moved to failed. traceId={}, fileName={}, attempts={}, msg={}",
                            traceId, receiptFileName, nextAttempts, e.getMessage(), e);

                } else {
                    meta.setNextAttemptAtEpochMs(receiptOutbox.calcNextAttemptAt(nextAttempts));
                    receiptOutbox.updateMeta(receiptOutbox.metaPathInPending(receiptFileName), meta);

                    log.error("SFTP upload failed. Will retry via scheduler. traceId={}, fileName={}, attempts={}, msg={}",
                            traceId, receiptFileName, nextAttempts, e.getMessage(), e);
                }
            }
        }

        log.info("Receipt prepared. traceId={}, fileName={}, isReceiptCreated={}, sftpUploaded={}",
                traceId, receiptFileName, isReceiptCreated, sftpUploaded);


        // 요청자에게 응답
        if (!sftpUploaded) {
            return Map.of(
                    "traceId", traceId,
                    "success", false,
                    "dbInserted", true,
                    "sftpUploaded", false,
                    "receiptFileName", receiptFileName,
                    "recordCount", rows.size(),
                    "orderIds", rows.stream().map(OrderDTO::getOrderId).collect(Collectors.toList()),
                    "message", "DB insert succeeded but SFTP upload failed. Receipt kept locally for retry."
            );
        }

        return Map.of(
                "traceId", traceId,
                "success", true,
                "dbInserted", true,
                "sftpUploaded", true,
                "receiptFileName", receiptFileName,
                "recordCount", rows.size(),
                "orderIds", rows.stream().map(OrderDTO::getOrderId).collect(Collectors.toList())
        );
    }

    /**
     * Oracle sequence를 사용하지 않고 service에서 동시성 이슈 해결
     * MAX(사전순) ORDER_ID 조회 -> 연속 ID 할당 -> insert
     * PK 충돌(동시에 같은 MAX ORDER_ID를 조회한 케이스) 시 재시도
     */
    private void insertWithId(List<OrderDTO> rows) {

        // 재시도 기능 구현을 위해 @Transaction 대신 TransactionTemplate 사용
        TransactionTemplate tx = new TransactionTemplate(txManager);

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                tx.execute(status -> {
                    String maxId = orderMapper.selectMaxOrderId(applicantKey);
                    List<String> ids = nextIds(maxId, rows.size());

                    // 원래 테스트에 있던 기능을 가져다 써서 테스트 ID가 들어있으므로 실제 사용할 ID로 교체
                    for (int i = 0; i < rows.size(); i++) {
                        rows.get(i).setOrderId(ids.get(i));
                    }

                    // CHUNK_SIZE 단위로 끊어서 insert 실행 (DB 안정화)
                    for (int i = 0; i < rows.size(); i += CHUNK_SIZE) {
                        List<OrderDTO> chunk = rows.subList(i, Math.min(i + CHUNK_SIZE, rows.size()));
                        orderMapper.insertOrders(chunk);
                    }
                    return null;
                });
                return;

            } catch (DuplicateKeyException dup) {
                // 동시성 이슈 해결
                log.warn("ORDER_ID collision detected. retry={}/{}", attempt, MAX_RETRY);
                jitter(attempt);
                if (attempt == MAX_RETRY) throw dup;
            }
        }
    }

    /**  
     * 읽어온 최대(사전순) ORDER_ID값 이후로 count개만큼 ID를 만들어 냄
     * ex) 최대 ORDER_ID가 B997, 현재 insert되는 row가 4개라면 B998,B999,C000,C001를 List로 생성
     */
    private List<String> nextIds(String maxId, int count) {
        int startIndex = -1;
        if (maxId != null && !maxId.isBlank()) {
            startIndex = toIndex(maxId.trim());
        }

        List<String> ids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            int idx = startIndex + i;
            if (idx >= 26 * 1000) {
                throw new IllegalStateException("ORDER_ID range exceeded (A000~Z999).");
            }
            ids.add(indexToId(idx));
        }
        return ids;
    }

    // 총 26,000가지의 ID 중 해당 orderId가 몇 번째(index기준) ID인지 판별 (숫자로 계산해야 쉬움)
    private int toIndex(String orderId) {
        if (!orderId.matches("^[A-Z]\\d{3}$")) {
            throw new IllegalStateException("Invalid ORDER_ID format: " + orderId);
        }
        char letter = orderId.charAt(0);
        int num = Integer.parseInt(orderId.substring(1));
        return (letter - 'A') * 1000 + num;
    }

    // 0~25999의 index값을 id로 바꿔줌
    private String indexToId(int idx) {
        char letter = (char) ('A' + (idx / 1000));
        int num = idx % 1000;
        return String.format("%c%03d", letter, num);
    }

    private void jitter(int attempt) {
        try {
            Thread.sleep(Math.min(50L, 10L * attempt));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildReceiptFileName() {
        // 파일명: INSPIEN_[참여자명]_[yyyyMMddHHmmss].txt
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "INSPIEN_" + participantName + "_" + ts + ".txt";
    }


    // 요구사항 포맷으로 영수증 내용 생성
    private String buildReceiptContent(List<OrderDTO> rows) {
        // ORDER_ID^USER_ID^ITEM_ID^APPLICANT_KEY^NAME^ADDRESS^ITEM_NAME^PRICE\n
        StringBuilder sb = new StringBuilder(rows.size() * 96);
        for (OrderDTO r : rows) {
            sb.append(r.getOrderId()).append('^')
              .append(r.getUserId()).append('^')
              .append(r.getItemId()).append('^')
              .append(r.getApplicantKey()).append('^')
              .append(r.getName()).append('^')
              .append(r.getAddress()).append('^')
              .append(r.getItemName()).append('^')
              .append(r.getPrice())
              .append('\n');
        }
        return sb.toString();
    }

}
