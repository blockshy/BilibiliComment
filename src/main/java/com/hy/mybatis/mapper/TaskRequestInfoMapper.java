package com.hy.mybatis.mapper;

import com.hy.mybatis.entity.TaskRequestInfo;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TaskRequestInfoMapper {

    @Select("SELECT * FROM task_request_info WHERE id = #{id}")
    public TaskRequestInfo getInfoById(Long id);

    @Update("update task_request_info set ac_time_value = #{acTimeValue} where id = #{id}")
    public void updateAcTimeValue(@Param("acTimeValue") String acTimeValue, @Param("id") Long id);
}
