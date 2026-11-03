package com.zhouzihang.exam.mapper;

import com.zhouzihang.exam.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 用户表访问。全部使用注解 SQL，避免离线环境下 XML 资源路径问题。 */
@Mapper
public interface UserMapper {

    @Select("select * from sys_user where username = #{username}")
    User findByUsername(@Param("username") String username);

    @Select("select * from sys_user where id = #{id}")
    User findById(@Param("id") long id);

    @Select("select * from sys_user order by id")
    List<User> findAll();
}
