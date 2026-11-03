package com.zhouzihang.exam.mapper;

import com.zhouzihang.exam.domain.ProctorAlert;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 告警表访问。 */
@Mapper
public interface ProctorAlertMapper {

    @Insert("insert into proctor_alert(exam_record_id, alert_level, risk_score, reason, evidence_file_id) "
            + "values(#{examRecordId}, #{alertLevel}, #{riskScore}, #{reason}, #{evidenceFileId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ProctorAlert alert);

    @Select("<script>select * from proctor_alert where exam_record_id = #{recordId}"
            + "<if test='level != null'> and alert_level = #{level}</if>"
            + " order by id desc limit #{limit}</script>")
    List<ProctorAlert> findByRecord(@Param("recordId") long recordId,
                                    @Param("level") String level,
                                    @Param("limit") int limit);

    @Select("select alert_level as level, count(*) as count from proctor_alert "
            + "where exam_record_id = #{recordId} group by alert_level")
    List<AlertLevelCount> countByLevel(@Param("recordId") long recordId);

    /** 按等级聚合的告警数量（供教师端看板一次性拿汇总）。 */
    @Data
    class AlertLevelCount {
        private String level;
        private Long count;
    }
}
