package com.zhouzihang.exam.mapper;

import com.zhouzihang.exam.domain.EvidenceFile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 截图存证表访问。 */
@Mapper
public interface EvidenceFileMapper {

    @Insert("insert into evidence_file(exam_record_id, rel_path, sha256, size_bytes, mime_type) "
            + "values(#{examRecordId}, #{relPath}, #{sha256}, #{sizeBytes}, #{mimeType})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(EvidenceFile file);

    @Select("select * from evidence_file where id = #{id}")
    EvidenceFile findById(@Param("id") long id);
}
