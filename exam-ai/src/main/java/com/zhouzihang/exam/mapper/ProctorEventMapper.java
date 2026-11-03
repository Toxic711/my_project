package com.zhouzihang.exam.mapper;

import com.zhouzihang.exam.domain.ProctorEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 防作弊原始事件表访问。 */
@Mapper
public interface ProctorEventMapper {

    @Insert("insert into proctor_event(exam_record_id, session_id, face_detected, face_count, yaw, pitch, roll, "
            + "gaze_off_screen, ear, risk_score, risk_level, event_ts, evidence_file_id) "
            + "values(#{examRecordId}, #{sessionId}, #{faceDetected}, #{faceCount}, #{yaw}, #{pitch}, #{roll}, "
            + "#{gazeOffScreen}, #{ear}, #{riskScore}, #{riskLevel}, #{eventTs}, #{evidenceFileId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ProctorEvent event);

    @Select("select * from proctor_event where exam_record_id = #{recordId} order by id desc limit #{limit}")
    List<ProctorEvent> findByRecordId(@Param("recordId") long recordId, @Param("limit") int limit);
}
