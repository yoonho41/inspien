package com.inspien.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    //OncePerRequestFilter : 같은 요청에 대해서 필터가 딱 한 번만 실행되도록 보장
    //요청 하나당 traceId를 반드시 하나만 생성

    public static final String TRACE_ID_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, jakarta.servlet.ServletException {

        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        MDC.put(TRACE_ID_KEY, traceId);
        request.setAttribute(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        long startNs = System.nanoTime();

        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            int status = response.getStatus();
            String result = (status < 400) ? "SUCCESS" : "FAIL";

            log.info("REQ traceId={} {} {} -> result={} status={} elapsedMs={}",
                    traceId,
                    request.getMethod(),
                    request.getRequestURI(),
                    result,
                    status,
                    elapsedMs
            );

            //다음 요청 처리 시 이전 요청의 traceId가 남아있지 않도록 마지막에 MDC remove
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
