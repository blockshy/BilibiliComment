package com.hy.mybatis.mapper;

import com.hy.mybatis.entity.TaskUserList;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TaskUserListMapper {

    @Select("SELECT * FROM task_user_list WHERE enabled_dynamic = 1 OR enabled_video = 1")
    List<TaskUserList> getTaskUserList();

    @Update("UPDATE task_user_list SET enabled_dynamic = 0 WHERE id = #{id}")
    void updateListEnabledDynamic(@Param("id") Long id);

    @Update("UPDATE task_user_list SET enabled_video = 0 WHERE id = #{id}")
    void updateListEnabledVideo(@Param("id") Long id);
}
