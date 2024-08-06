package com.hy.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hy.mybatis.entity.TaskRequestInfo;
import com.hy.mybatis.mapper.TaskRequestInfoMapper;
import com.jayway.jsonpath.JsonPath;

import javax.annotation.Resource;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class CookieRefresh {

    @Resource
    private TaskRequestInfoMapper taskRequestInfoMapper;

    @Resource
    private ObjectMapper objectMapper;

    private static final String PUBLIC_KEY = "-----BEGIN PUBLIC KEY-----\n" +
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg\n" +
            "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71\n" +
            "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40\n" +
            "JNrRuoEUXpabUzGB8QIDAQAB\n" +
            "-----END PUBLIC KEY-----";

    private static String isRefreshCookieUrl = "https://passport.bilibili.com/x/passport-login/web/cookie/info?csrf=";

    private static String refreshCookieUrl = "https://www.bilibili.com/correspond/1/";

    private static String refreshCookieResult = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh";

    private static String sureRefreshCookie = "https://passport.bilibili.com/x/passport-login/web/confirm/refresh";

    public void test() {
        try {
            TaskRequestInfo taskRequestInfo = taskRequestInfoMapper.getInfoById(1L);
            String cookie = taskRequestInfo.getCookie();
            // 解析 Cookie 字符串
            Map<String, String> cookieMap = parseCookie(cookie);
            // 获取特定的 判断是否需要刷新cookie的 参数
            String biliJct = cookieMap.get("bili_jct");
            String isRefreshCookieUrlResult = isRefreshCookieUrl + biliJct;

            String isRefreshResponse = HttpUtils.get(isRefreshCookieUrlResult, cookie);
            JsonNode jsonNode = objectMapper.readTree(isRefreshResponse);
            Boolean isRefresh = JsonPath.read(jsonNode.toString(), "$.data.refresh");

            if(isRefresh){
                String correspondPath = getCorrespondPath(String.format("refresh_%d", System.currentTimeMillis()), PUBLIC_KEY);
                String refreshCookieUrlResult = refreshCookieUrl + correspondPath;

                String refreshResponse = HttpUtils.get(refreshCookieUrlResult, cookie);
                // 解析 HTML
                Document doc = Jsoup.parse(refreshResponse);

                // 提取 id 为 "1-name" 的 div 的文本
                Element element = doc.getElementById("1-name");
                String extractedText = element.text();

                // 创建请求参数的 Map
                Map<String, String> parameters = new HashMap<>();
                parameters.put("csrf", biliJct); // 从 Cookie 中获取的 CSRF Token
                parameters.put("refresh_csrf", correspondPath); // 从获取refresh_csrf获得的实时刷新口令
                parameters.put("source", "main_web"); // 访问来源
                parameters.put("refresh_token", extractedText); // 从 localStorage 中获得的持久化刷新口令

                // 将 Map 转换为 JSON 字符串
                String jsonString = objectMapper.writeValueAsString(parameters);

                String postResponse = HttpUtils.post(refreshCookieResult, cookie, jsonString);
                String string = objectMapper.readTree(postResponse).toString();
                Integer refreshResult = JsonPath.read(string, "$.code");

                if(new Integer(0).equals(refreshResult)){
                    //成功
                    String refreshToken = JsonPath.read(string, "$.data.refresh_token");
                    taskRequestInfoMapper.updateAcTimeValue(refreshToken, taskRequestInfo.getId());

                    /*HttpUtils.post(sureRefreshCookie, )*/

                }


            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Map<String, String> parseCookie(String cookie) {
        Map<String, String> cookieMap = new HashMap<>();

        // 分割 cookie 字符串为单个键值对
        String[] cookies = cookie.split(";\\s*");

        for (String cookiePart : cookies) {
            String[] keyValue = cookiePart.split("=", 2); // 只分割一次
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                cookieMap.put(key, value);
            }
        }

        return cookieMap;
    }

    public static String getCorrespondPath(String plaintext, String publicKeyStr) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        publicKeyStr = publicKeyStr
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\n", "")
                .trim();
        byte[] publicBytes = Base64.getDecoder().decode(publicKeyStr);
        X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(publicBytes);
        PublicKey publicKey = keyFactory.generatePublic(x509EncodedKeySpec);

        String algorithm = "RSA/ECB/OAEPPadding";
        Cipher cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);

        // Encode the plaintext to bytes
        byte[] plaintextBytes = plaintext.getBytes("UTF-8");

        // Add OAEP padding to the plaintext bytes
        OAEPParameterSpec oaepParams = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams);
        // Encrypt the padded plaintext bytes
        byte[] encryptedBytes = cipher.doFinal(plaintextBytes);
        // Convert the encrypted bytes to a Base64-encoded string
        return new BigInteger(1, encryptedBytes).toString(16);
    }
}