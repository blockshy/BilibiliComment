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
import org.springframework.scheduling.annotation.Async;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ScheduledFuture;

@Component
public class TaskAllLog {

    private static final Logger logger = LogManager.getLogger("InfoLogFile");
    
    @Resource
    private TaskRequestInfoMapper taskRequestInfoMapper;

    @Resource
    private ScheduledTasksAllLogMapper allLogMapper;

    @Resource
    private LogMapper logMapper;
    
    //全量评论
    @Async("taskExecutor")
    @PostConstruct
    public void getAllComment() throws URISyntaxException, InterruptedException {
        logger.info("--------------taskNew");

        List<ScheduledTasksAllLog> scheduledTasksAllLogs = allLogMapper.selectAll();

        TaskRequestInfo taskRequestInfo = taskRequestInfoMapper.getInfoById(1L);

        String tableName = "", url = "";
        int index = 0;
        for (ScheduledTasksAllLog allLog : scheduledTasksAllLogs) {
            tableName = "log_"+allLog.getMarkNo();
            url = allLog.getUrl();
            // 创建 URI 对象
            URI uri = new URI(url);

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
            if(!Objects.isNull(allLog.getLastCursor())){
                cursor = allLog.getLastCursor();
            }
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

    //传入时间戳即可
    public Date conversionTime(long timeStamp) {
        return new Date(timeStamp);
    }

    // 计算 w_rid 的哈希函数
    public static String hash(String date, String[] en1) {
       /* String[] en1 = {

                "wts=" + date // 当前时间戳
        };*/
        //mode=3&oid=956626677264285747&pagination_str=%7B%22offset%22%3A%22%22%7D&plat=1&seek_rpid=0&type=17&web_location=1315875&wts=1721574736
        String wt = "ea1db124af3c7062474693fa704f4ff8";
        String Jt = String.join("&", en1);
        String string = Jt + wt;

        // 计算 MD5 哈希值
        String w_rid = md5(string);
        return w_rid;
    }

    public static String urlEncode(String url) {
        char[] tp = url.toCharArray();
        String now = "";
        for (char ch : tp) {
            if (ch >= 0x4E00 && ch <= 0x9FA5) {
                try {
                    now += URLEncoder.encode(ch + "", "gbk");
                } catch (UnsupportedEncodingException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            } else {
                now += ch;
            }

        }
        return now;
    }

    // 计算 MD5 哈希值
    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

}
