package com.hy.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hy.common.EnabledEnum;
import com.hy.component.CommentComponent;
import com.hy.mybatis.entity.ScheduledTask;
import com.hy.mybatis.entity.TaskRequestInfo;
import com.hy.mybatis.entity.TaskUserList;
import com.hy.mybatis.mapper.ScheduledTaskMapper;
import com.hy.mybatis.mapper.TaskRequestInfoMapper;
import com.hy.mybatis.mapper.TaskUserListMapper;
import com.hy.utils.HttpUtils;
import com.jayway.jsonpath.JsonPath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Component
public class UserTaskListScheduler {

    @Resource
    private TaskUserListMapper taskUserListMapper;

    @Resource
    private TaskRequestInfoMapper taskRequestInfoMapper;

    @Resource
    private ScheduledTaskMapper scheduledTaskMapper;

    @Resource
    private ObjectMapper objectMapper;

    //详见https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/dynamic/space.md
    private final static String dynamicListUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?host_mid=";

    private static final Logger logger = LogManager.getLogger();

    //@Scheduled(fixedRate = 10000)
    public void loadUserTask(){
        //获取配置的用户表
        List<TaskUserList> taskUserList = taskUserListMapper.getTaskUserList();

        for (TaskUserList userList : taskUserList) {

            //拼接查询用户动态的URL
            String requestUrl = dynamicListUrl + userList.getUid();

            //获取使用的cookie
            TaskRequestInfo taskRequestInfo = taskRequestInfoMapper.getInfoById(userList.getRequestInfoId());

            //获取相应
            String response = HttpUtils.get(requestUrl, taskRequestInfo.getCookie());

            JsonNode rootNode = null;
            try {
                rootNode = objectMapper.readTree(response);
                String string = JsonPath.read(rootNode.toString(), "$.data.items").toString();
                List<JsonNode> items = objectMapper.readValue(string, new TypeReference<List<JsonNode>>() {});

                for (JsonNode item : items) {
                    //一个item代表一个动态的信息
                    //JsonNode itemNode = objectMapper.readTree(item);
                    //获取到动态ID
                    String commentIdStr = JsonPath.read(item.toString(), "$.id_str").toString();
                    String paramsType = JsonPath.read(item.toString(), "$.basic.comment_type").toString();
                    logger.info("获取到动态ID："+commentIdStr);
                    //检查该动态是否已在任务列表中
                    int existsTf = scheduledTaskMapper.checkExistsTaskByMarksNo(commentIdStr);
                    if(0 != existsTf){
                        continue;
                    }
                    //创建task
                    ScheduledTask scheduledTask = new ScheduledTask();
                    scheduledTask.setTaskName("Task_"+commentIdStr);
                    scheduledTask.setCronExpression("0/10 * * * * ?");
                    scheduledTask.setEnabled(EnabledEnum.ENABLED.getValue());
                    LocalDateTime now = LocalDateTime.now();
                    scheduledTask.setCreatedAt(now);
                    scheduledTask.setUpdatedAt(now);
                    scheduledTask.setRemarks("动态-"+commentIdStr);
                    scheduledTask.setMarkNo(commentIdStr);
                    scheduledTask.setAllLog(0);
                    scheduledTask.setType(2);
                    scheduledTask.setRequestInfoId(userList.getRequestInfoId());
                    scheduledTask.setParamsType(paramsType);
                    logger.info("=======================");
                    logger.info("插入新task-"+scheduledTask.getTaskName());
                    scheduledTaskMapper.insert(scheduledTask);

                    taskUserListMapper.updateListEnabledDynamic(userList.getId());

                }
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
