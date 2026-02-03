package com.inspien.service;

import com.inspien.dto.OrderDTO;
import com.inspien.infra.ReceiptMetaDTO;
import com.inspien.infra.ReceiptOutbox;
import com.inspien.infra.SftpUploader;
import com.inspien.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminReceiptService {

    private final ReceiptOutbox outbox;
    private final SftpUploader sftpUploader;
    private final OrderMapper orderMapper;

    // fileName: INSPIEN_<name>_<yyyyMMddHHmmss>.txt
    private static final Pattern RECEIPT_NAME_PATTERN =
            Pattern.compile("^INSPIEN_(.+)_(\\d{14})\\.txt$");

    public Map<String, Object> retryByTraceId(String requestXml) {
        AdminReq req = parseAdminXml(requestXml);

        String adminTraceId = MDC.get("traceId"); // 관리자 호출 자체의 traceId
        if (req.traceId == null || req.traceId.isBlank()) {
            return Map.of(
                    "traceId", adminTraceId,
                    "success", false,
                    "message", "TRACE_ID is required"
            );
        }

        // 현재 처리하려는 traceId(요청자에게 전달받은 값)를 이 작업의 traceId에 덮어씀 (추적/관리 용이)
        MDC.put("traceId", req.traceId);

        try {
            outbox.ensureDirs();

            Found found = findMetaByTraceId(req.traceId);

            // meta 파일이 없으면 사실상 처리가 불가능함. app 오류보다는 인프라 이슈 가능성 점검 필요
            if (found == null) {
                log.warn("No receipt meta found for traceId={}", req.traceId);
                return Map.of(
                        "traceId", req.traceId,
                        "success", false,
                        "message", "No receipt(meta) found in pending/failed for this traceId."
                );
            }

            ReceiptMetaDTO meta = found.meta;
            String oldFileName = meta.getFileName();
            String newFileName = oldFileName;

            // 1) 이름 변경 요청이 있으면 fileName 변경 + 파일 및 meta rename + meta 내용 업데이트
            if (req.newParticipantName != null && !req.newParticipantName.isBlank()) {
                String renamed = buildRenamedFileName(oldFileName, req.newParticipantName);

                if (renamed != null && !renamed.equals(oldFileName)) {
                    newFileName = renamed;
                    renameReceiptAndMeta(found, oldFileName, newFileName);
                    meta.setFileName(newFileName);

                    // meta 파일 위치가 바뀌었으니 metaPath도 갱신
                    found.metaPath = found.dir.resolve(newFileName + ".meta.json");
                    outbox.updateMeta(found.metaPath, meta);

                    log.info("Receipt rename applied. oldFileName={} newFileName={}", oldFileName, newFileName);
                } else {
                    log.warn("Rename requested but filename pattern not matched. oldFileName={}", oldFileName);
                }
            }

            // 2) 영수증 파일이 없으면 DB에서 재생성 (영수증 생성 실패 케이스까지 커버)
            Path receiptPath = found.dir.resolve(newFileName);
            if (!Files.exists(receiptPath)) {
                log.warn("Receipt file missing. Will recreate from DB. fileName={}", newFileName);

                List<OrderDTO> rows = orderMapper.selectOrdersByIds(meta.getApplicantKey(), meta.getOrderIds());
                if (rows == null || rows.isEmpty()) {
                    log.error("Cannot recreate receipt: DB rows not found. orderIds={}", meta.getOrderIds());
                    return Map.of(
                            "traceId", req.traceId,
                            "success", false,
                            "message", "Receipt file missing and DB rows not found. Cannot resend."
                    );
                }

                String content = buildReceiptContent(rows);

                Files.writeString(receiptPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log.info("Receipt recreated. fileName={} path={}", newFileName, receiptPath);
            }

            // 3) SFTP 전송 시도
            log.info("ADMIN SFTP retry start. fileName={} path={}", newFileName, receiptPath);
            sftpUploader.upload(receiptPath, newFileName);
            log.info("ADMIN SFTP retry success. fileName={}", newFileName);

            // 4) 성공 시 sent로 이동
            moveToSent(found.dir, newFileName);

            return Map.of(
                    "traceId", req.traceId,
                    "success", true,
                    "message", "SFTP resend success.",
                    "oldFileName", oldFileName,
                    "newFileName", newFileName,
                    "foundIn", found.location
            );

        } catch (Exception e) {
            log.error("ADMIN retry failed. traceId={}, msg={}", req.traceId, e.getMessage(), e);
            return Map.of(
                    "traceId", req.traceId,
                    "success", false,
                    "message", "Retry failed: " + e.getMessage()
            );
        } finally {
            MDC.remove("traceId");
        }
    }


    private Found findMetaByTraceId(String traceId) {
        // pending 부터 찾아보고 없으면 failed 에서 찾아보기
        Found f = scanDirForTraceId(outbox.pendingDir(), "pending", traceId);
        if (f != null) return f;
        return scanDirForTraceId(outbox.failedDir(), "failed", traceId);
    }

    private Found scanDirForTraceId(Path dir, String location, String traceId) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.meta.json")) {
            for (Path metaPath : stream) {
                ReceiptMetaDTO meta = outbox.readMeta(metaPath);
                if (traceId.equals(meta.getTraceId())) {
                    String fileName = meta.getFileName();
                    return new Found(dir, location, metaPath, meta, fileName);
                }
            }
        } catch (NoSuchFileException e) {
            return null;
        } catch (Exception e) {
            log.error("scanDirForTraceId failed. dir={}, msg={}", dir, e.getMessage(), e);
        }
        return null;
    }

    // 파일 이름 변경
    private void renameReceiptAndMeta(Found found, String oldFileName, String newFileName) throws Exception {
        Path oldReceipt = found.dir.resolve(oldFileName);
        Path newReceipt = found.dir.resolve(newFileName);

        Path oldMeta = found.dir.resolve(oldFileName + ".meta.json");
        Path newMeta = found.dir.resolve(newFileName + ".meta.json");

        // 이미 존재하면 덮어쓰지 않도록 처리
        if (Files.exists(newReceipt) || Files.exists(newMeta)) {
            throw new IllegalStateException("Target filename already exists: " + newFileName);
        }

        if (Files.exists(oldReceipt)) {
            Files.move(oldReceipt, newReceipt);
        }
        if (Files.exists(oldMeta)) {
            Files.move(oldMeta, newMeta);
        }
    }

    // outbox.sentDir로 이동
    private void moveToSent(Path sourceDir, String fileName) throws Exception {
        Path sentDir = outbox.sentDir();
        Files.createDirectories(sentDir);

        Path receipt = sourceDir.resolve(fileName);
        Path meta = sourceDir.resolve(fileName + ".meta.json");

        if (Files.exists(receipt)) {
            Files.move(receipt, sentDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(meta)) {
            Files.move(meta, sentDir.resolve(fileName + ".meta.json"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // 변경할 파일 이름 생성
    private String buildRenamedFileName(String oldFileName, String newName) {
        // INSPIEN_<anything>_<14digits>.txt 에서 timestamp만 유지하고 이름만 교체
        Matcher m = RECEIPT_NAME_PATTERN.matcher(oldFileName);
        if (!m.matches()) return null;
        String ts = m.group(2);
        return "INSPIEN_" + newName + "_" + ts + ".txt";
    }

    /**
     * 요구사항 포맷
     * ORDER_ID^USER_ID^ITEM_ID^APPLICANT_KEY^NAME^ADDRESS^ITEM_NAME^PRICE\n
     */
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


    // 관리자 요청 XML 파싱 (OrderXmlParser와 같은 역할)
    private AdminReq parseAdminXml(String rawXml) {
        if (rawXml == null || rawXml.isBlank()) {
            throw new IllegalArgumentException("XML body is empty.");
        }

        String xml = rawXml.trim();

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // XXE 방지
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setExpandEntityReferences(false);

            Document doc = dbf.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            String traceId = firstText(doc, "TRACE_ID", "traceId", "TraceId");
            String name = firstText(doc, "PARTICIPANT_NAME", "participantName", "NAME", "name");

            AdminReq req = new AdminReq();
            req.traceId = traceId;
            req.newParticipantName = name;
            return req;

        } catch (Exception e) {
            throw new IllegalArgumentException("Admin XML parsing failed: " + e.getMessage(), e);
        }
    }


    private String firstText(Document doc, String... tags) {
        for (String tag : tags) {
            NodeList list = doc.getElementsByTagName(tag);
            if (list.getLength() > 0) {
                String v = list.item(0).getTextContent();
                if (v != null && !v.trim().isEmpty()) return v.trim();
            }
        }
        return null;
    }

    private static class AdminReq {
        String traceId;
        String newParticipantName;
    }

    private static class Found {
        final Path dir;        // pending 또는 failed (경로)
        final String location; // "pending" 또는 "failed" (String값)
        Path metaPath;
        final ReceiptMetaDTO meta;
        final String fileName;

        Found(Path dir, String location, Path metaPath, ReceiptMetaDTO meta, String fileName) {
            this.dir = dir;
            this.location = location;
            this.metaPath = metaPath;
            this.meta = meta;
            this.fileName = fileName;
        }
    }
}
