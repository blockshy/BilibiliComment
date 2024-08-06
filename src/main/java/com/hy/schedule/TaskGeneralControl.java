package com.hy.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hy.common.EnabledEnum;
import com.hy.component.CommentComponent;
import com.hy.entity.ReplyList;
import com.hy.mybatis.entity.*;
import com.hy.mybatis.mapper.*;
import com.hy.utils.GsonUtil;
import com.hy.utils.HttpUtils;
import com.jayway.jsonpath.JsonPath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ScheduledFuture;

@Component
public class TaskGeneralControl {

    private static final Logger logger = LogManager.getLogger("InfoLogFile");

    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();

    private final TaskScheduler taskScheduler = new ThreadPoolTaskScheduler();

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private TaskControllerMapper taskControllerMapper;

    @Resource
    private TaskUserListMapper taskUserListMapper;

    @Resource
    private TaskRequestInfoMapper taskRequestInfoMapper;

    @Resource
    private ScheduledTaskMapper scheduledTaskMapper;

    @Resource
    private CommentComponent commentComponent;

    @Resource
    private ScheduledTasksAllLogMapper allLogMapper;

    private final static String dynamicListUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?host_mid=";

    @PostConstruct
    void initTask(){
        ((ThreadPoolTaskScheduler) taskScheduler).initialize();
        loadTaskControllers();
    }

    public void loadTaskControllers() {
        // 取消已调度的任务
        cancelAllTasks();

        // 从数据库加载定时任务配置
        List<TaskController> taskControllerList = taskControllerMapper.findAll();
        taskControllerList.forEach(this::scheduleTask);
    }

    //每十秒自动读取数据库刷新任务列表
    @Scheduled(fixedRate = 30000) // 每30秒检查一次
    public void refreshTasks() {
        logger.info("-----refreshTaskController");
        loadTaskControllers();
    }

    List<String> dynamicTypeSet = new ArrayList<>(Arrays.asList("DYNAMIC_TYPE_FORWARD", "DYNAMIC_TYPE_WORD", "DYNAMIC_TYPE_DRAW", "DYNAMIC_TYPE_ARTICLE"));

    List<String> videoTypeSet = new ArrayList<>(Arrays.asList("DYNAMIC_TYPE_AV"));

    private void scheduleTask(TaskController task) {
        if(EnabledEnum.DISABLE.getValue() == task.getEnabled()){
            return;
        }
        List<TaskUserList> taskUserList = taskUserListMapper.getTaskUserList();
        List<ScheduledTask> normalTaskList = scheduledTaskMapper.findAll();
        if(1 == task.getTaskType()){
            //动态
            for (TaskUserList userList : taskUserList) {
                //用户配置启用获取动态
                if(userList.getEnabledDynamic() == EnabledEnum.ENABLED.getValue()){
                    Runnable taskRunnable = () -> {
                        // 执行任务的逻辑
                        logger.info("=================================");
                        logger.info("Executing task: " + task.getTaskName());
                        logger.info("用户"+userList.getUid()+"开始获取动态");
                        getUserDynamicList(userList, 1);
                    };
                    Trigger trigger = new CronTrigger(task.getCronExpression());

                    ScheduledFuture<?> future = taskScheduler.schedule(taskRunnable, trigger);
                    scheduledTasks.put(task.getTaskName(), future);
                }
            }
        }
        if(2 == task.getTaskType()){
            //视频
            for (TaskUserList userList : taskUserList) {
                //用户配置启用获取视频
                if(userList.getEnabledVideo() == EnabledEnum.ENABLED.getValue()){
                    Runnable taskRunnable = () -> {
                        // 执行任务的逻辑
                        logger.info("=================================");
                        logger.info("Executing task: " + task.getTaskName());
                        logger.info("用户"+userList.getUid()+"开始获取视频");
                        getUserDynamicList(userList, 2);
                    };
                    Trigger trigger = new CronTrigger(task.getCronExpression());

                    ScheduledFuture<?> future = taskScheduler.schedule(taskRunnable, trigger);
                    scheduledTasks.put(task.getTaskName(), future);
                }
            }
        }
        if(3 == task.getTaskType()){
            //基础task
            for (ScheduledTask scheduledTask : normalTaskList) {
                if(EnabledEnum.ENABLED.getValue() == scheduledTask.getEnabled()){
                    if(1 == scheduledTask.getType()){
                        Runnable taskRunnable = () -> {
                            // 执行任务的逻辑
                            logger.info("=================================");
                            logger.info("Executing task: " + scheduledTask.getTaskName());
                            commentComponent.getVideoNewComment(scheduledTask);
                            //修改执行时间
                            updateScheduledTaskExecutionTime(scheduledTask);
                        };
                        Trigger trigger = new CronTrigger(scheduledTask.getCronExpression());

                        ScheduledFuture<?> future = taskScheduler.schedule(taskRunnable, trigger);
                        scheduledTasks.put(scheduledTask.getTaskName(), future);
                    }
                    if(2 == scheduledTask.getType()){
                        Runnable taskRunnable = () -> {
                            // 执行任务的逻辑
                            logger.info("=================================");
                            logger.info("Executing task: " + scheduledTask.getTaskName());
                            commentComponent.getMarkNewComment(scheduledTask);
                            //修改执行时间
                            updateScheduledTaskExecutionTime(scheduledTask);
                        };
                        Trigger trigger = new CronTrigger(scheduledTask.getCronExpression());

                        ScheduledFuture<?> future = taskScheduler.schedule(taskRunnable, trigger);
                        scheduledTasks.put(scheduledTask.getTaskName(), future);
                    }
                }
            }
        }
        /*if(4 == task.getTaskType()){
            //基础task
            logger.info("--------------taskAllLog");

            List<ScheduledTasksAllLog> scheduledTasksAllLogs = allLogMapper.selectAll();

            TaskRequestInfo taskRequestInfo = taskRequestInfoMapper.getInfoById(1L);

            String tableName = "", url = "";
            int index = 0;
            for (ScheduledTasksAllLog allLog : scheduledTasksAllLogs) {
                if(2 == allLog.getType()){
                    tableName = "log_"+allLog.getMarkNo();
                }
                if(1 == allLog.getType()){
                    tableName = "log_"+allLog.getBvNo();
                }
                url = allLog.getUrl();
                // 创建 URI 对象
                URI uri = null;
                try {
                    uri = new URI(url);
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }

                // 解析查询参数
                String oid = getParameter(uri, "oid");
                String type = getParameter(uri, "type");
                String mode = getParameter(uri, "mode");
                String plat = getParameter(uri, "plat");
                String seek_rpid = getParameter(uri, "seek_rpid");
                String web_location = getParameter(uri, "web_location");
                String wts = getParameter(uri, "wts");

                //String data = String.format("{\"offset\":\"{\\\\\"type\\\\\":1,\\\\\"direction\\\\\":1,\\\\\"data\\\\\":{\\\\\"pn\\\\\":%s}}\"}", page);
                //String data = "{\"offset\":\"\"}";
                Integer cursor = 0;
                do{
                    logger.info("cursor------------"+cursor);
                    logger.info("tableName------------"+tableName);
                    String data = String.format("{\"offset\":\"{\\\"type\\\":3,\\\"direction\\\":1,\\\"Data\\\":{\\\"cursor\\\":%s}}\"}", cursor);
                    String date = getFormattedDate(); // 获取当前日期格式化后的字符串

                    String[] en1 = {
                            "mode=" + mode,
                            "oid=" + oid,
                            "pagination_str=" + URLEncoder.encode(data),
                            "plat=" + plat,
                            "seek_rpid=" + seek_rpid,
                            "type=" + type,
                            "web_location=" + web_location,
                            "wts=" + date, // 当前时间戳
                    };

                    String w_rid = hash(date, en1);

                    String[] en = {
                            "oid=" + oid,
                            "type=" + type,
                            "mode=" + mode,
                            "pagination_str=" + URLEncoder.encode(data),
                            "plat=" + plat,
                            "seek_rpid=" + seek_rpid,
                            "web_location=" + web_location,
                            "w_rid=" + w_rid,
                            "wts=" + date // 当前时间戳
                    };

                    String resultUrl = "https://api.bilibili.com/x/v2/reply/wbi/main?" + String.join("&", en);

                    logger.info(resultUrl);

                    String httpResponse = HttpUtils.get(resultUrl, taskRequestInfo.getCookie());

                    //自动建表
                    Integer checkTable = logMapper.checkTable(tableName);
                    if(0 == checkTable){
                        logMapper.createTable(tableName);
                    }

                    if(!Objects.isNull(httpResponse)){
                        ReplyList replyList = GsonUtil.fromJson(httpResponse, ReplyList.class);
                        for (ReplyList.Reply reply : replyList.getData().getReplies()) {
                            Log log = new Log();
                            Integer i = logMapper.selectRpId(tableName, reply.getRpid());
                            if(new Integer(0).equals(i)){
                                log.setMid(reply.getMember().getMid());
                                log.setUname(reply.getMember().getUname());
                                log.setAvatar(reply.getMember().getAvatar());
                                log.setCtime(conversionTime(reply.getCtime()*1000));
                                log.setCurrentLevel(reply.getMember().getLevel_info().getCurrent_level());
                                log.setContent(reply.getContent().getMessage());
                                log.setRpid(reply.getRpid());
                                logMapper.insertLog(log, tableName);
                            }

                            if (!Objects.isNull(reply.getReplies())){
                                for (ReplyList.Reply reply1 : reply.getReplies()) {
                                    i = logMapper.selectRpId(tableName, reply1.getRpid());
                                    if(new Integer(0).equals(i)){
                                        log = new Log();
                                        log.setMid(reply1.getMember().getMid());
                                        log.setUname(reply1.getMember().getUname());
                                        log.setAvatar(reply1.getMember().getAvatar());
                                        log.setCtime(conversionTime(reply1.getCtime()*1000));
                                        log.setCurrentLevel(reply1.getMember().getLevel_info().getCurrent_level());
                                        log.setContent(reply1.getContent().getMessage());
                                        log.setRpid(reply1.getRpid());
                                        log.setParent(reply1.getParent_str());
                                        logMapper.insertLog(log, tableName);
                                    }
                                }
                            }
                        }
                        // 使用Gson解析JSON字符串
                        Gson gson = new Gson();
                        JsonObject nextOffset = gson.fromJson(replyList.getData().getCursor().getPagination_reply().getNext_offset(), JsonObject.class);
                        if(Objects.isNull(nextOffset)){
                            break;
                        }
                        // 获取并打印解析后的数据
                        JsonObject jsonData = nextOffset.get("Data").getAsJsonObject();
                        String jsonCursor = jsonData.get("cursor").getAsString();
                        cursor = Integer.valueOf(jsonCursor);

                        allLogMapper.updateLastCursor(allLog.getId(), cursor);
                    }
                    Thread.sleep(2000L);
                }while (cursor > 0);

                allLogMapper.updateEnabled(allLog.getId());
            }

            for (ScheduledTask scheduledTask : normalTaskList) {
                if(EnabledEnum.ENABLED.getValue() == scheduledTask.getEnabled()){
                    if(1 == scheduledTask.getType()){
                        Runnable taskRunnable = () -> {
                            // 执行任务的逻辑
                            logger.info("=================================");
                            logger.info("Executing task: " + scheduledTask.getTaskName());
                            commentComponent.getVideoNewComment(scheduledTask);
                            //修改执行时间
                            updateScheduledTaskExecutionTime(scheduledTask);
                        };
                        Trigger trigger = new CronTrigger(scheduledTask.getCronExpression());

                        ScheduledFuture<?> future = taskScheduler.schedule(taskRunnable, trigger);
                        scheduledTasks.put(scheduledTask.getTaskName(), future);
                    }
                    if(2 == scheduledTask.getType()){
                        Runnable taskRunnable = () -> {
                            // 执行任务的逻辑
                            logger.info("=================================");
                            logger.info("Executing task: " + scheduledTask.getTaskName());
                            commentComponent.getMarkNewComment(scheduledTask);
                            //修改执行时间
                            updateScheduledTaskExecutionTime(scheduledTask);
                        };
                        Trigger trigger = new CronTrigger(scheduledTask.getCronExpression());

                        ScheduledFuture<?> future = taskScheduler.schedule(taskRunnable, trigger);
                        scheduledTasks.put(scheduledTask.getTaskName(), future);
                    }
                }
            }
        }*/
    }

    // 辅助方法：从 URI 中获取指定参数的值
    private static String getParameter(URI uri, String paramName) {
        String value = "";
        String query = uri.getQuery();
        if (query != null) {
            String[] params = query.split("&");
            for (String param : params) {
                String[] keyValue = param.split("=");
                if (keyValue.length == 2 && keyValue[0].equals(paramName)) {
                    try {
                        value = keyValue[1];
                        // URL 解码
                        value = java.net.URLDecoder.decode(value, "UTF-8");
                    } catch (UnsupportedEncodingException ex) {
                        ex.printStackTrace();
                    }
                    break;
                }
            }
        }
        return value;
    }

    // 获取当前日期格式化后的字符串，格式为 "fYYYYMMDDHHmmss"
    public static String getFormattedDate() {
        /*SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        return "f" + dateFormat.format(new Date());*/
        return String.valueOf(new Date().getTime() / 1000);
    }

    void updateScheduledTaskExecutionTime(ScheduledTask scheduledTask){
        // 创建 CronTrigger
        CronTrigger cronTrigger = new CronTrigger(scheduledTask.getCronExpression());

        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();
        ZonedDateTime zonedNow = now.atZone(ZoneId.systemDefault());
        Date nowDate = Date.from(zonedNow.toInstant());

        // 使用 Trigger.nextExecutionTime 来计算下一个执行时间
        Date nextExecutionDate = cronTrigger.nextExecutionTime(new TriggerContextImpl(nowDate));
        ZonedDateTime nextExecutionZoned = nextExecutionDate.toInstant().atZone(ZoneId.systemDefault());
        LocalDateTime nextExecutionLocal = nextExecutionZoned.toLocalDateTime();

        scheduledTask.setLastExecution(now);
        scheduledTask.setNextExecution(nextExecutionLocal);
        scheduledTaskMapper.update(scheduledTask);
    }


    void getUserDynamicList(TaskUserList taskUser, int type){
        //拼接查询用户动态的URL
        String requestUrl = dynamicListUrl + taskUser.getUid();

        //获取使用的cookie
        TaskRequestInfo taskRequestInfo = taskRequestInfoMapper.getInfoById(taskUser.getRequestInfoId());

        //获取相应
        String response = HttpUtils.get(requestUrl, taskRequestInfo.getCookie());

        JsonNode rootNode = null;
        try {
            rootNode = objectMapper.readTree(response);
            String string = JsonPath.read(rootNode.toString(), "$.data.items").toString();
            List<JsonNode> items = objectMapper.readValue(string, new TypeReference<List<JsonNode>>() {});

            for (JsonNode item : items) {
                //logger.info(item.toString());
                //创建task
                ScheduledTask scheduledTask = new ScheduledTask();

                //一个item代表一个动态的信息
                //JsonNode itemNode = objectMapper.readTree(item);
                String paramsType = JsonPath.read(item.toString(), "$.basic.comment_type").toString();
                String dynamicType =  JsonPath.read(item.toString(), "$.type").toString();
                //logger.info("-------------dynamicType:"+dynamicType);
                if(dynamicTypeSet.contains(dynamicType)){
                    //动态
                    //排除视频
                    if(1 != type){
                        continue;
                    }
                    //获取到动态ID
                    String commentIdStr = JsonPath.read(item.toString(), "$.basic.comment_id_str").toString();
                    //动态oid MarkNo
                    String markNo = JsonPath.read(item.toString(), "$.id_str").toString();
                    scheduledTask.setRemarks("动态-"+commentIdStr);
                    scheduledTask.setMarkNo(markNo);
                    scheduledTask.setType(2);
                    scheduledTask.setTaskName("Task-"+commentIdStr);
                    logger.info("获取到动态ID："+commentIdStr);

                    //检查该动态是否已在任务列表中
                    int existsTf = scheduledTaskMapper.checkExistsTaskByMarksNo(markNo);
                    if(0 != existsTf){
                        continue;
                    }
                }
                if(videoTypeSet.contains(dynamicType)){
                    //视频
                    if(2 != type){
                        continue;
                    }
                    //获取到视频BV号
                    String bvNo = JsonPath.read(item.toString(), "$.modules.module_dynamic.major.archive.bvid").toString();

                    scheduledTask.setRemarks("视频-"+bvNo);
                    scheduledTask.setBvNo(bvNo);
                    scheduledTask.setType(1);
                    scheduledTask.setTaskName("Task-"+bvNo);
                    logger.info("获取到视频BV号："+bvNo);

                    //检查该视频是否已在任务列表中
                    int existsTf = scheduledTaskMapper.checkExistsTaskByBvNo(bvNo);
                    if(0 != existsTf){
                        continue;
                    }
                }

                //task数据写入
                scheduledTask.setCronExpression("0/10 * * * * ?");
                //scheduledTask.setEnabled(Boolean.TRUE);
                //测试用设置为不启动
                scheduledTask.setEnabled(EnabledEnum.ENABLED.getValue());
                LocalDateTime now = LocalDateTime.now();
                scheduledTask.setCreatedAt(now);
                scheduledTask.setUpdatedAt(now);
                scheduledTask.setAllLog(0);
                scheduledTask.setRequestInfoId(taskUser.getRequestInfoId());
                scheduledTask.setParamsType(paramsType);
                logger.info("=======================");
                logger.info("插入新task-"+scheduledTask.getTaskName());
                scheduledTaskMapper.insert(scheduledTask);
            }

            //taskUserListMapper.updateListEnabledDynamic(taskUser.getId());

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void cancelAllTasks() {
        scheduledTasks.values().forEach(future -> {
            if (future != null) {
                future.cancel(false); // false 表示不中断正在执行的任务
            }
        });
        scheduledTasks.clear();
    }

    // 内部类实现 TriggerContext
    private static class TriggerContextImpl implements org.springframework.scheduling.TriggerContext {
        private final Date lastScheduledExecutionTime;

        public TriggerContextImpl(Date lastScheduledExecutionTime) {
            this.lastScheduledExecutionTime = lastScheduledExecutionTime;
        }

        @Override
        public Date lastScheduledExecutionTime() {
            return lastScheduledExecutionTime;
        }

        @Override
        public Date lastActualExecutionTime() {
            return null;
        }

        @Override
        public Date lastCompletionTime() {
            return null;
        }
    }

}
