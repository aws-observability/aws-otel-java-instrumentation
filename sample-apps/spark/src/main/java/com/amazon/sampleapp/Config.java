package com.amazon.sampleapp;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.util.Map;
import java.util.ArrayList;
import org.yaml.snakeyaml.Yaml;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Config {
    private static final Logger logger = LogManager.getLogger();
    private static String configHost;
    private static String configPort;
    private static int configInterval;
    private static int configTime;
    private static int configHeap;
    private static int configThreads;
    private static int configCpu;
    private static ArrayList<String> configSamplePorts;
    public Config() {
        Yaml yaml = new Yaml();
        InputStream inputStream = null;
        try {
            logger.info((System.getProperty("user.dir")));
            String filePath = System.getProperty("user.dir");
            filePath = filePath + "/src/main/java/com/amazon/sampleapp/config.yaml";
            inputStream = new FileInputStream(filePath);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        logger.info("Reading in config file");
        Map<String, Object> variables = yaml.load(inputStream);
        configHost = (String) variables.get("Host");
        configPort = (String) variables.get("port");
        configInterval = (int) variables.get("TimeInterval");
        configTime = (int) variables.get("RandomTimeAliveIncrementer");
        configHeap = (int) variables.get("RandomTotalHeapSizeUpperBound");
        configThreads = (int) variables.get("RandomThreadsActiveUpperBound");
        configCpu = (int) variables.get("RandomCpuUsageUpperBound");
//        String[] tempPorts = (String[]) variables.get("SampleAppPorts");
//        for (int i = 0; i < tempPorts.length; i++) {
//            configSamplePorts.add(tempPorts[i]);
//        }
        configSamplePorts = (ArrayList<String>) variables.get("SampleAppPorts");
        logger.info(configHost);
        logger.info(configPort);
        logger.info(configInterval);
        logger.info(configTime);
        logger.info(configHeap);
        logger.info(configThreads);
        logger.info(configCpu);
        logger.info("Read in config file");
    }

    public String getHost() {
        return configHost;
    }
    public String getPort() {
        return configPort;
    }

    public int getInterval() {
        return configInterval;
    }

    public int getTimeAdd() {
        return configTime;
    }

    public int getHeap() {
        return configHeap;
    }

    public int getThreads() {
        return configThreads;
    }

    public int getCpu() {
        return configCpu;
    }

    public ArrayList<String> getSamplePorts() {
        return configSamplePorts;
    }
}
