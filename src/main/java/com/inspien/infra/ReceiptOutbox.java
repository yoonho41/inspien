package com.inspien.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.time.Instant;

@Slf4j
@Component
public class ReceiptOutbox {

    /** 
     * DB insert가 성공한 이후에 파일 생성 및 전송(SFTP)가 실패할 수 있으므로, 영수증 전송 작업을
     * local outbox 폴더(pending/sent/failed)로 영속화하여 전송 재시도 및 admin 조치를 가능하게 함
     */

    private final ObjectMapper om = new ObjectMapper();

    @Value("${inspien.receipt.outbox-dir:./out/receipts}")
    private String outboxDir;

    // 각 outbox 폴더의 경로 선언
    public Path pendingDir() { return Paths.get(outboxDir, "pending"); }
    public Path sentDir()    { return Paths.get(outboxDir, "sent"); }
    public Path failedDir()  { return Paths.get(outboxDir, "failed"); }

    // 폴더 없으면 만들기
    public void ensureDirs() {
        try {
            Files.createDirectories(pendingDir());
            Files.createDirectories(sentDir());
            Files.createDirectories(failedDir());
        } catch (Exception e) {
            throw new RuntimeException("Outbox directory create failed: " + e.getMessage(), e);
        }
    }

    public Path metaPathInPending(String fileName) {
        return pendingDir().resolve(fileName + ".meta.json");
    }

    public Path receiptPathInPending(String fileName) {
        return pendingDir().resolve(fileName);
    }


    // meta 파일을 반드시 영수증 파일보다 먼저 만들도록 할 것(그래야 영수증 생성에서 에러 나도 추적 후 재생성 가능)
    public void writeMetaToPending(ReceiptMetaDTO meta) {
        ensureDirs();
        Path metaPath = metaPathInPending(meta.getFileName());
        atomicWriteString(metaPath, toJson(meta));
    }

    // meta 파일 읽기
    public ReceiptMetaDTO readMeta(Path metaPath) {
        try {
            String json = Files.readString(metaPath);
            return om.readValue(json, ReceiptMetaDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("Read meta failed: " + e.getMessage(), e);
        }
    }

    // 수정할 사항을 전달받았을 경우 meta 파일 수정
    public void updateMeta(Path metaPath, ReceiptMetaDTO meta) {
        try {
            atomicWriteString(metaPath, toJson(meta));
        } catch (Exception e) {
            log.error("Meta update failed. metaPath={}, msg={}", metaPath, e.getMessage(), e);
        }
    }

    public void writeReceiptToPending(String fileName, String content) {
        ensureDirs();
        Path receiptPath = receiptPathInPending(fileName);
        atomicWriteString(receiptPath, content);
    }

    // 성공 처리: pending 에서 sent로 이동
    public void markSent(String fileName) {
        ensureDirs();
        moveIfExists(receiptPathInPending(fileName), sentDir().resolve(fileName));
        moveIfExists(metaPathInPending(fileName), sentDir().resolve(fileName + ".meta.json"));
    }

    // 최종 실패 처리: pending 에서 failed로 이동
    public void markFailed(String fileName) {
        ensureDirs();
        moveIfExists(receiptPathInPending(fileName), failedDir().resolve(fileName));
        moveIfExists(metaPathInPending(fileName), failedDir().resolve(fileName + ".meta.json"));
    }

    // 백오프(재시도 간격): 2^attempts 초, 최대 10분
    public long calcNextAttemptAt(int attempts) {
        long delayMs = Math.min(10 * 60 * 1000L, (1L << Math.min(attempts, 10)) * 1000L);
        return Instant.now().toEpochMilli() + delayMs;
    }

    private String toJson(ReceiptMetaDTO meta) {
        try {
            return om.writeValueAsString(meta);
        } catch (Exception e) {
            throw new RuntimeException("Meta json serialization failed: " + e.getMessage(), e);
        }
    }


    // 깨진(작성하다가 중간에 중단된) 파일이 만들어지는 걸 방지하기 위해 atomic write 패턴 사용 
    private void atomicWriteString(Path target, String content) {
        try {
            Path tmp = Paths.get(target.toString() + ".tmp");
            Files.writeString(tmp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // atomic_move 가 지원되지 않는 경우엔 일반적인 방법으로 생성
            try {
                Files.writeString(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception ex) {
                throw new RuntimeException("Write failed: " + ex.getMessage(), ex);
            }
        } catch (Exception e) {
            throw new RuntimeException("Write failed: " + e.getMessage(), e);
        }
    }

    private void moveIfExists(Path from, Path to) {
        try {
            if (Files.exists(from)) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            // 업로드는 성공했는데 마킹 이동이 실패하면 중복 업로드 위험이 생길 수 있음
            log.error("Move failed. from={}, to={}, msg={}", from, to, e.getMessage(), e);
        }
    }
}
