package com.hy.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hy.entity.ReplyList;
import com.hy.mybatis.entity.Log;
import com.hy.mybatis.entity.ScheduledTask;
import com.hy.mybatis.entity.TaskRequestInfo;
import com.hy.mybatis.mapper.LogMapper;
import com.hy.mybatis.mapper.ScheduledTaskMapper;
import com.hy.mybatis.mapper.TaskRequestInfoMapper;
import com.hy.utils.GsonUtil;
import com.hy.utils.HttpUtils;
import com.hy.utils.StringUtils;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import com.jayway.jsonpath.JsonPath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Component
public class CommentComponent {

    private static final Logger logger = LogManager.getLogger("InfoLogFile");

    //详见https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/dynamic/basicInfo.md
    public static final String trendsBaseUrl = "https://api.vc.bilibili.com/dynamic_repost/v1/dynamic_repost/repost_detail?dynamic_id=";
    //https://www.bilibili.com/video/BV1om421g72j
    public static final String videoBaseUrl = "https://www.bilibili.com/video/";

    @Resource
    private TaskRequestInfoMapper taskRequestInfoMapper;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private LogMapper logMapper;

    @Resource
    private ScheduledTaskMapper scheduledTaskMapper;

    //视频最新评论
    public void getVideoNewComment(ScheduledTask scheduledTask){

        String resultUrl = scheduledTask.getUrl();

        if(StringUtils.isEmpty(scheduledTask.getUrl())){
            //视频URL
            String videoUrl = videoBaseUrl + scheduledTask.getBvNo();

            //请求URL获取相应
            String httpResult = HttpUtils.get(videoUrl, null);

            if(StringUtils.isEmpty(httpResult)){
                return;
            }

            // 解析 HTML
            Document doc = Jsoup.parse(httpResult);

            // 提取所有 <script> 标签
            Elements scripts = doc.getElementsByTag("script");

            //获取其中的aid aid替换评论URL中的oid
            Long aid = null;
            for (Element script : scripts) {
                // 获取脚本内容
                String scriptContent = script.html();

                // 查找包含 window.__INITIAL_STATE 的行
                if (scriptContent.contains("window.__INITIAL_STATE")) {
                    // 提取 JSON 部分
                    int startIndexStart = scriptContent.indexOf("window.__INITIAL_STATE__=");
                    if (startIndexStart == -1){
                        continue;
                    }
                    int startIndex = startIndexStart + "window.__INITIAL_STATE__=".length();
                    int endIndex = scriptContent.indexOf(";", startIndex);
                    String jsonString = scriptContent.substring(startIndex, endIndex).trim();
                    logger.info("获取到的aid JSON");
                    logger.info(jsonString);
                    try {
                        // 解析 JSON
                        JsonNode rootNode = objectMapper.readTree(jsonString);
                        aid = rootNode.path("aid").asLong();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            if(Objects.isNull(aid)){
                return;
            }

            //https://api.bilibili.com/x/v2/reply/wbi/main?oid=960669633073905670&type=17&mode=2&pagination_str=%7B%22offset%22:%22%22%7D&plat=1&seek_rpid=0&web_location=1315875&w_rid=ca6e57ca8e0c58c498e774a2ecc582bc&wts=1722518137
            String commentUrl = "https://api.bilibili.com/x/v2/reply/wbi/main?oid=1456429204&type=1&mode=2&pagination_str=%7B%22offset%22:%22%22%7D&plat=1&seek_rpid=&web_location=1315875&w_rid=ddaf54f72a36ad9fe42bcb67dc29af34&wts=1722521185";

            resultUrl = getUrlParams(commentUrl, String.valueOf(aid), scheduledTask);
            if (StringUtils.isEmpty(resultUrl)) return;
        }

        String httpResponse = HttpUtils.get(resultUrl, null);

        if(StringUtils.isEmpty(scheduledTask.getUrl())){
            scheduledTaskMapper.updateNewCommentUrl(scheduledTask.getId(), resultUrl);
        }

        String tableName = "log_"+scheduledTask.getBvNo();

        saveReply(httpResponse, tableName, scheduledTask);
    }

    //动态最新评论
    public void getMarkNewComment(ScheduledTask scheduledTask){

        String resultUrl = scheduledTask.getUrl();

        if(StringUtils.isEmpty(scheduledTask.getUrl())){
            //动态URL
            String trendsUrl = trendsBaseUrl + scheduledTask.getMarkNo();

            TaskRequestInfo taskRequestInfo = taskRequestInfoMapper.getInfoById(scheduledTask.getRequestInfoId());

            JsonNode rootNode = null;
            String oid = null;
            try {
                //由于不同类型的动态获取oid的规则并不一致，在此做判断
                if("17".equals(scheduledTask.getParamsType())){
                    oid = scheduledTask.getMarkNo();
                }else{
                    //请求URL获取相应
                    String httpResult = HttpUtils.get(trendsUrl, taskRequestInfo.getCookie());
                    if(StringUtils.isEmpty(httpResult)){
                        return;
                    }
                    rootNode = objectMapper.readTree(httpResult);
                    oid = JsonPath.read(rootNode.toString(), "$.data.items[0].desc.origin.rid").toString();
                }
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            String commentUrl = "https://api.bilibili.com/x/v2/reply/wbi/main?oid=323458115&type=11&mode=2&pagination_str=%7B%22offset%22:%22%22%7D&plat=1&seek_rpid=0&web_location=1315875&w_rid=b01525982161a19ed8ea1f07c3c9e696&wts=1722522279";

            URI uri;
            resultUrl = getUrlParams(commentUrl, oid, scheduledTask);
            if (resultUrl == null) return;
        }

        if(StringUtils.isEmpty(scheduledTask.getUrl())){
            scheduledTaskMapper.updateNewCommentUrl(scheduledTask.getId(), resultUrl);
        }

        //添加动态设置时间，根据创建任务时间变更任务执行频率，避免任务堆积
        updateCron(scheduledTask);

        String httpResponse = HttpUtils.get(resultUrl, null);

        String tableName = "log_"+scheduledTask.getMarkNo();

        saveReply(httpResponse, tableName, scheduledTask);
    }

    //保存评论
    private void saveReply(String httpResponse, String tableName, ScheduledTask scheduledTask) {
        try {
            if(!Objects.isNull(httpResponse)){
                ReplyList replyList = GsonUtil.fromJson(httpResponse, ReplyList.class);
                Integer checkTable = logMapper.checkTable(tableName);
                if(0 == checkTable){
                    logMapper.createTable(tableName);
                }
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
            }
        }catch (Exception e){
            logger.error(e.getMessage(), e);
            scheduledTaskMapper.updateNewCommentUrl(scheduledTask.getId(), "");
        }
    }

    //拼接URL后缀
    private String getUrlParams(String commentUrl, String oid, ScheduledTask scheduledTask) {
        URI uri;
        String resultUrl;
        Map<String, String> queryParams = null;
        try {
            uri = new URI(commentUrl);
            String query = uri.getQuery();
            // 解析现有的查询参数
            queryParams = new LinkedHashMap<>();
            if (query != null && !query.isEmpty()) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2) {
                        queryParams.put(pair[0], pair[1]);
                    }
                }
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        if(queryParams.isEmpty()){
            return null;
        }

        logger.info("queryParams");
        logger.info(queryParams);

        String[] enInit = {
                "mode=" + getMapValue(queryParams, "mode"),
                "oid=" + oid,
                "pagination_str=" + URLEncoder.encode(getMapValue(queryParams, "pagination_str")),
                "plat=" + getMapValue(queryParams, "plat"),
                "seek_rpid=" + getMapValue(queryParams, "seek_rpid"),
                "type=" + (StringUtils.isEmpty(scheduledTask.getParamsType()) ? getMapValue(queryParams, "type") : scheduledTask.getParamsType()),
                "web_location=" + getMapValue(queryParams, "web_location"),
                "wts=" + getMapValue(queryParams, "wts"), // 当前时间戳
        };

        String w_rid = hash(enInit);

        String[] enResult = {
                "oid=" + oid,
                "type=" + (StringUtils.isEmpty(scheduledTask.getParamsType()) ? getMapValue(queryParams, "type") : scheduledTask.getParamsType()),
                "mode=" + getMapValue(queryParams, "mode"),
                "pagination_str=" + URLEncoder.encode(getMapValue(queryParams, "pagination_str")),
                "plat=" + getMapValue(queryParams, "plat"),
                "seek_rpid=" + getMapValue(queryParams, "seek_rpid"),
                "web_location=" + getMapValue(queryParams, "web_location"),
                "w_rid=" + w_rid,
                "wts=" + getMapValue(queryParams, "wts"), // 当前时间戳
        };

        resultUrl = "https://api.bilibili.com/x/v2/reply/wbi/main?" + String.join("&", enResult);

        return resultUrl;
    }

    private void updateCron(ScheduledTask scheduledTask) {
        LocalDateTime createdAt = scheduledTask.getCreatedAt();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createdTime = createdAt.toInstant(ZoneOffset.UTC).atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        Duration duration = Duration.between(createdTime, now);

        // 根据不同的时间范围来设置 cron 表达式
        String cron = null;
        long seconds = duration.getSeconds();
        if (seconds <= 3600) { // 小于或等于1小时
            cron = "0/5 * * * * ?";  // 每5秒执行一次
        } else if (seconds <= (3600 * 6)) { // 1到6小时
            cron = "0/10 * * * * ?"; // 每10秒执行一次
        } else if (seconds <= (3600 * 12)) { // 6到12小时
            cron = "0/15 * * * * ?"; // 每15秒执行一次
        } else if (seconds <= (3600 * 18)) { // 12到18小时
            cron = "0/20 * * * * ?"; // 每20秒执行一次
        } else if (seconds <= (3600 * 24)) { // 18到24小时
            cron = "0/25 * * * * ?"; // 每25秒执行一次
        } else if (seconds <= (3600 * 30)) { // 24到30小时
            cron = "0/30 * * * * ?"; // 每30秒执行一次
        } else if (seconds <= (3600 * 36)) { // 30到36小时
            cron = "0/40 * * * * ?"; // 每40秒执行一次
        } else if (seconds <= (3600 * 48)) { // 36到48小时
            cron = "0 * * * * ?"; // 每1分钟执行一次
        } else if (seconds <= (3600 * 72)) { // 48到72小时
            cron = "0 0/5 * * * ?"; // 每5分钟执行一次
        }
        if(StringUtils.isEmpty(cron)){
            //超出三天范围，停止任务
            scheduledTaskMapper.disabledTask(scheduledTask);
        }else{
            //修改任务频率
            scheduledTask.setCronExpression(cron);
            scheduledTaskMapper.updateCron(scheduledTask);
        }
    }

    String getMapValue(Map<String, String> map, String key){
        return Objects.isNull(map.get(key)) ? "" : map.get(key);
    }

    //传入时间戳即可
    public Date conversionTime(long timeStamp) {
        return new Date(timeStamp);
    }

    // 计算 w_rid 的哈希函数
    public static String hash(String[] enInit) {
       /* String[] en1 = {

                "wts=" + date // 当前时间戳
        };*/
        //mode=3&oid=956626677264285747&pagination_str=%7B%22offset%22%3A%22%22%7D&plat=1&seek_rpid=0&type=17&web_location=1315875&wts=1721574736
        String wt = "ea1db124af3c7062474693fa704f4ff8";
        String Jt = String.join("&", enInit);
        String string = Jt + wt;

        // 计算 MD5 哈希值
        String w_rid = md5(string);
        return w_rid;
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
