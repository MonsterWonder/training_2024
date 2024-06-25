package com.example.springcloud.service;


import com.example.springcloud.base.BaseJsonUtils;
import com.example.springcloud.base.Response;
import com.example.springcloud.base.SnowflakeIdGenerator;
import com.example.springcloud.base.enums.MetricEnum;
import com.example.springcloud.controller.ServerMetricController;
import com.example.springcloud.controller.request.LogQueryRequest;
import com.example.springcloud.controller.request.LogUploadRequest;
import com.example.springcloud.controller.request.MetricUploadRequest;
import com.example.springcloud.controller.request.MetricQueryRequest;
import com.example.springcloud.controller.response.LogQueryResponse;
import com.example.springcloud.controller.response.MetricQueryResponse;
import com.example.springcloud.po.XmCollectorPo;
import com.example.springcloud.service.impl.ServerLogServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @ClassName serviceTest
 * @Description TODO
 * @Author 石心木PC
 * @create: 2024-06-05 15:35
 **/
@SpringBootTest
@RunWith(SpringRunner.class)
public class ServiceTest {
    @Resource
    RedisTemplate redisTemplate;
    @Autowired
    SnowflakeIdGenerator snowflakeIdGenerator;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ServerService serverService;
    @Autowired
    private ServerMetricController serverMetricController;
    @Autowired
    private ServerLogService serverLogService;
    @Autowired
    private ServerLogServiceImpl serverLogServiceImpl;


    @Test
    public void insertTest() {
        for (int i = 0; i < 1; i++) {
            MetricUploadRequest request = new MetricUploadRequest("cpu.used.percent", "my-computer1", 1717596227L, 60, 60.10f);
            serverService.metricUpload(request);
            Response<Void> response = serverMetricController.metricUpload(request);
            System.out.println(BaseJsonUtils.writeValue(response));
        }
    }

    @Test
    public void queryTest() {
        MetricQueryRequest request = new MetricQueryRequest();
        request.setEndpoint("my-computer");
//        request.setMetric("cpu.used.percent");
        request.setStart_ts(1717596227L);
        request.setEnd_ts(1717598947L);
        Response<List<MetricQueryResponse>> response = serverMetricController.metricQuery(request);
        System.out.println(BaseJsonUtils.writeValue(response));
        response.getData().stream().forEach(System.out::println);
        System.out.println(serverService.queryMetric(request));
    }

    @Test
    public void idTest() {
        SnowflakeIdGenerator snowflakeIdGenerator = new SnowflakeIdGenerator(1L, 2L);
        System.out.println(snowflakeIdGenerator.nextId());
    }

    @Test
    public void redisTestSetAndGetToPo() {
        XmCollectorPo po = new XmCollectorPo();
        po.setId(snowflakeIdGenerator.nextId());
        po.setEndpoint("my-computer");
        po.setStep(60);
        po.setMetric(MetricEnum.getByCode("cpu.used.percent"));
        po.setTimestamp(1717596227L);
        po.setIsDelete(0);
        po.setValue(60.10f);
        po.setCreateTime(null);
        po.setUpdateTime(null);
        stringRedisTemplate.opsForValue().set("one", "1");
        System.out.println(stringRedisTemplate.opsForValue().get("one"));
        String key = po.getMetric() + ":" + po.getEndpoint() + ":" + po.getTimestamp();
        stringRedisTemplate.opsForValue().set(key, BaseJsonUtils.writeValue(po));
        XmCollectorPo newPo = BaseJsonUtils.readValue(stringRedisTemplate.opsForValue().get(key), XmCollectorPo.class);
        System.out.println(newPo);
        System.out.println(newPo.getMetric() + " " + newPo.getEndpoint() + " " + newPo.getTimestamp());
    }

    public String getSameTime(String st, String et) {
        if (StringUtils.isBlank(st) && StringUtils.isBlank(et)) {
            return null;
        }
        if (st.compareToIgnoreCase(et) > 0){
            return null; // 开始时间大于结束时间
        }
        int index = 0;
        StringBuilder commonPrefix = new StringBuilder();
        while (index < st.length() && index < et.length()
                && st.charAt(index) == et.charAt(index)) {
            commonPrefix.append(st.charAt(index));
            index++;
        }
        return commonPrefix.toString();
    }


    @Test
    public void redisGetKeysToSearchPo() {
        MetricQueryRequest request = new MetricQueryRequest();
        request.setEndpoint("my-computer");
        request.setMetric("cpu.used.percent");
        request.setStart_ts(1717596226L);
        request.setEnd_ts(1717598947L);
        Long st = request.getStart_ts();
        Long et = request.getEnd_ts();
        //方法截取相同时间戳
        String st_string = String.valueOf(st);
        String et_string = String.valueOf(et);
        String sameTime = getSameTime(st_string, et_string);
        System.out.println(sameTime);
        String key = request.getMetric() + ":" + request.getEndpoint() + ":" + sameTime + "*";
        Set<String> keys = stringRedisTemplate.keys(key);
        System.out.println(keys);

        List<XmCollectorPo> poList = keys.stream().filter(key1 -> key1 != null)
                .map(key1 -> {
                    String json = stringRedisTemplate.opsForValue().get(key1);
                    return json != null ? BaseJsonUtils.readValue(json, XmCollectorPo.class) : null;
                })
                // 过滤掉null值以及不符合时间戳条件的项
                .filter(newPo -> newPo != null && newPo.getTimestamp() >= st && newPo.getTimestamp() <= et)
                .collect(Collectors.toList());
        System.out.println(poList.get(0).getValue());
        Map<String, MetricQueryResponse> resultMap = new HashMap<>();
        poList.stream().forEach(po -> {
            String currentMetric = po.getMetric();
            MetricQueryResponse metricResponse = resultMap.computeIfAbsent(currentMetric, k -> {
                MetricQueryResponse newResponse = new MetricQueryResponse();
                newResponse.setMetric(k); // 明确设置metric属性
                return newResponse;
            });
            metricResponse.getValues().add(new MetricQueryResponse.Value(po.getTimestamp(), po.getValue()));
        });
        System.out.println(resultMap.values());
    }

    @Test
    public void TestredisQuery() {
        MetricQueryRequest request = new MetricQueryRequest();
        request.setEndpoint("my-computer");
        request.setMetric("cpu.used.percent");
        request.setStart_ts(1717596226L);
        request.setEnd_ts(1717598947L);
        serverService.queryMetric(request);
    }

    @Test
    public void TestSaveToFile() {
        LogUploadRequest request = new LogUploadRequest();
        request.setHostName("my-computer");
        request.setFile("/home/work/a.log");
        List<String> logs = new ArrayList<>();
        logs.add("2024-05-16 10:11:51 +08:00 ERROR tcp_ping::metric src/metric.rs:95 - Report one chunk done, error: Connection refused");
        logs.add("2024-05-16 10:11:51 +08:00 ERROR tcp_ping::metric src/metric.rs:95 - Report one chunk done, error: Connection refused");
        logs.add("2024-05-16 10:11:51 +08:00 ERROR tcp_ping::metric src/metric.rs:95 - Report one chunk done, error: Connection refused");
        request.setLogs(logs);
        serverLogServiceImpl.saveToFile(request);
    }
    @Test
    public void TestSaveToDB() {
        LogUploadRequest request = new LogUploadRequest();
        request.setHostName("my-computer");
        request.setFile("/home/work/a.log");
        List<String> logs = new ArrayList<>();
        logs.add("2024-05-16 10:11:51 +08:00 ERROR tcp_ping::metric src/metric.rs:95 - Report one chunk done, error: Connection refused");
        logs.add("2024-05-16 10:11:51 +08:00 ERROR tcp_ping::metric src/metric.rs:95 - Report one chunk done, error: Connection refused");
        logs.add("2024-05-16 10:11:51 +08:00 ERROR tcp_ping::metric src/metric.rs:95 - Report one chunk done, error: Connection refused");
        request.setLogs(logs);
        serverLogServiceImpl.saveToDatabase(request);
    }
    @Test
    public void TestQueryLogFromFile() {
        LogQueryRequest request = new LogQueryRequest();
        request.setHostName("my-computer");
        request.setFile("/home/work/a.log");
        LogQueryResponse responses = serverLogServiceImpl.queryFromFile(request);
        System.out.println(responses.getHostName());
        System.out.println(responses.getFile());
        responses.getLogs().forEach(System.out::println);
    }
    @Test
    public void TestQueryLogFromFileByHTTP() throws Exception {
        LogQueryRequest request = new LogQueryRequest();
        request.setHostName("my-computer");
        request.setFile("/home/work/a.log");
        String strUrl = "http://localhost:9090/api/log/query?hostname=my-computer&file=/home/work/a.log";
        URL url = new URL(strUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        // 设置请求方法为GET
        connection.setRequestMethod("GET");
        StringBuilder response = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
        }
        System.out.println(response.toString());
    }
    @Test
    public void TestQueryLogFromDB() {
        LogQueryRequest request = new LogQueryRequest();
        request.setHostName("my-computer");
        request.setFile("/home/work/a.log");
        LogQueryResponse responses = serverLogServiceImpl.queryFromDatabase(request);
        System.out.println(responses.getHostName());
        System.out.println(responses.getFile());
        responses.getLogs().forEach(System.out::println);
    }
}
