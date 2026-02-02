package com.inspien.infra;

import lombok.Data;

import java.util.List;

// 영수증 전송 오류 시 추적을 위한 메타데이터 파일 내용
@Data
public class ReceiptMetaDTO {
    private String traceId;
    private String applicantKey;

    private String fileName;          // INSPIEN_이름_yyyyMMddHHmmss.txt
    private List<String> orderIds;    // 영수증 생성에 실패했다면 이 orderIds를 통해 다시 생성

    private int attempts;             // 재시도 횟수(즉시 시도 포함)
    private long nextAttemptAtEpochMs; // 다음 재시도 시각
    private String lastError;         // 마지막 실패 원인
}
