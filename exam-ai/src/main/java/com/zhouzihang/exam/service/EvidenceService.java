package com.zhouzihang.exam.service;

import com.zhouzihang.exam.common.ApiException;
import com.zhouzihang.exam.domain.EvidenceFile;
import com.zhouzihang.exam.domain.ExamRecord;
import com.zhouzihang.exam.mapper.EvidenceFileMapper;
import com.zhouzihang.exam.mapper.ExamRecordMapper;
import com.zhouzihang.exam.security.AuthPrincipal;
import com.zhouzihang.exam.storage.EvidenceStorage;
import org.springframework.stereotype.Service;

/**
 * 截图存证读取 + 访问控制。
 *
 * <p>面试讲解点：证据文件属于敏感数据，必须“鉴权后流式返回”，
 * 绝不能让前端拼磁盘路径。访问规则：
 * 教师/管理员可看任意；学生只能看自己答卷的存证。
 * 响应头用 Content-Disposition: inline + Cache-Control: private,no-store，
 * 避免被 CDN/浏览器缓存后越权读取。</p>
 */
@Service
public class EvidenceService {

    private final EvidenceFileMapper evidenceFileMapper;
    private final ExamRecordMapper recordMapper;
    private final EvidenceStorage evidenceStorage;

    public EvidenceService(EvidenceFileMapper evidenceFileMapper, ExamRecordMapper recordMapper,
                           EvidenceStorage evidenceStorage) {
        this.evidenceFileMapper = evidenceFileMapper;
        this.recordMapper = recordMapper;
        this.evidenceStorage = evidenceStorage;
    }

    public EvidenceContent load(long evidenceId, AuthPrincipal principal) {
        EvidenceFile file = evidenceFileMapper.findById(evidenceId);
        if (file == null) {
            throw ApiException.notFound("存证不存在");
        }
        ExamRecord record = recordMapper.findById(file.getExamRecordId());
        if (record == null) {
            throw ApiException.notFound("考试记录不存在");
        }
        if (principal.isStudent() && !record.getStudentId().equals(principal.userId())) {
            throw ApiException.forbidden("无权查看该存证");
        }
        byte[] bytes = evidenceStorage.read(file.getRelPath());
        return new EvidenceContent(bytes, file.getMimeType(), evidenceId);
    }

    public record EvidenceContent(byte[] bytes, String contentType, long evidenceId) {
    }
}
