package com.zhouzihang.exam.web;

import com.zhouzihang.exam.security.CurrentUser;
import com.zhouzihang.exam.service.EvidenceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 截图存证受控读取。
 *
 * <p>面试讲解点：接口只接受 evidenceId，不接收路径参数 —— 路径由服务端从库里取，
 * 从根上消除路径穿越；响应禁止缓存，避免共享代理缓存导致越权。</p>
 */
@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable long id) {
        EvidenceService.EvidenceContent content = evidenceService.load(id, CurrentUser.require());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"evidence-" + content.evidenceId() + "\"")
                .body(content.bytes());
    }
}
