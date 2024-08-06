package com.hy.utils;

import com.hy.component.CommentComponent;
import org.apache.http.HttpEntity;
import org.apache.http.ParseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.io.IOException;

public class HttpUtils {

    private static final Logger logger = LogManager.getLogger("InfoLogFile");

    public static String get(String url, String cookie){
        logger.info("请求URL:"+url);
        String result = null;
        // 获得Http客户端(可以理解为:你得先有一个浏览器;注意:实际上HttpClient与浏览器是不一样的)
        CloseableHttpClient httpClient = HttpClientBuilder.create().build();
        // 创建Get请求
        HttpGet httpGet = new HttpGet(url);

        httpGet.addHeader("cookie", cookie);
        httpGet.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36");

        // 打印请求头以确认
        /*logger.info("请求头: ");
        for (org.apache.http.Header header : httpGet.getAllHeaders()) {
            logger.info(header.getName() + ": " + header.getValue());
        }*/
        // 响应模型
        CloseableHttpResponse response = null;
        try {
            // 由客户端执行(发送)Get请求
            response = httpClient.execute(httpGet);
            // 从响应模型中获取响应实体
            HttpEntity responseEntity = response.getEntity();
            logger.info("响应状态为:" + response.getStatusLine());
            if (responseEntity != null) {
                //logger.info("响应内容长度为:" + responseEntity.getContentLength());
                String string = EntityUtils.toString(responseEntity);
                logger.info("JSON长度为："+string.length());
                result = string;
            }
        } catch (ParseException | IOException e) {
            e.printStackTrace();
        } finally {
            try {
                // 释放资源
                if (httpClient != null) {
                    httpClient.close();
                }
                if (response != null) {
                    response.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //logger.info("请求响应："+result);
        return result;
    }

    public static String post(String url, String cookie, String jsonPayload) {
        logger.info("请求URL: " + url);
        String result = null;
        CloseableHttpClient httpClient = HttpClientBuilder.create().build();
        HttpPost httpPost = new HttpPost(url);

        httpPost.addHeader("cookie", cookie);
        httpPost.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36");
        httpPost.addHeader("Content-Type", "application/json");

        try {
            // 设置请求体
            StringEntity entity = new StringEntity(jsonPayload);
            httpPost.setEntity(entity);

            // 执行POST请求
            CloseableHttpResponse response = httpClient.execute(httpPost);
            HttpEntity responseEntity = response.getEntity();
            logger.info("响应状态为: " + response.getStatusLine());
            if (responseEntity != null) {
                String string = EntityUtils.toString(responseEntity);
                logger.info("JSON长度为: " + string.length());
                result = string;
            }
            response.close(); // 确保在finally块中关闭响应
        } catch (ParseException | IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (httpClient != null) {
                    httpClient.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return result;
    }
}
