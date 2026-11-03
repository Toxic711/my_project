package com.zhouzihang.exam.mapper;

import com.zhouzihang.exam.domain.Exam;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 考试表访问。 */
@Mapper
public interface ExamMapper {

    @Insert("insert into exam(title, description, question_count, duration_minutes, total_score, status, created_by) "
            + "values(#{title}, #{description}, #{questionCount}, #{durationMinutes}, #{totalScore}, #{status}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Exam exam);

    @Select("select * from exam where id = #{id}")
    Exam findById(@Param("id") long id);

    @Select("select * from exam order by id")
    List<Exam> findAll();

    @Select("select * from exam where status = 'PUBLISHED' order by id")
    List<Exam> findPublished();

    @Update("update exam set question_count = #{questionCount}, total_score = #{totalScore}, status = #{status} where id = #{id}")
    int updateStats(@Param("id") long id, @Param("questionCount") int questionCount,
                    @Param("totalScore") int totalScore, @Param("status") String status);
}
