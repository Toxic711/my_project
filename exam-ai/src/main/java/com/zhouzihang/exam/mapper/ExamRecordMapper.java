package com.zhouzihang.exam.mapper;

import com.zhouzihang.exam.domain.ExamRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 考试记录表访问。 */
@Mapper
public interface ExamRecordMapper {

    @Insert("insert into exam_record(exam_id, student_id, status, score, started_at, deadline_at, submit_request_id) "
            + "values(#{examId}, #{studentId}, #{status}, #{score}, #{startedAt}, #{deadlineAt}, #{submitRequestId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ExamRecord record);

    @Select("select * from exam_record where id = #{id}")
    ExamRecord findById(@Param("id") long id);

    @Select("select * from exam_record where exam_id = #{examId} and student_id = #{studentId}")
    ExamRecord findByExamAndStudent(@Param("examId") long examId, @Param("studentId") long studentId);

    @Select("select * from exam_record where student_id = #{studentId} order by id desc")
    List<ExamRecord> findByStudent(@Param("studentId") long studentId);

    @Update("update exam_record set status = #{status}, score = #{score}, submitted_at = #{submittedAt}, "
            + "submit_request_id = #{submitRequestId} where id = #{id}")
    int updateSubmit(@Param("id") long id, @Param("status") String status, @Param("score") int score,
                     @Param("submittedAt") LocalDateTime submittedAt,
                     @Param("submitRequestId") String submitRequestId);

    @Update("update exam_record set last_event_at = #{eventAt} where id = #{id}")
    int touchLastEvent(@Param("id") long id, @Param("eventAt") LocalDateTime eventAt);
}
