package com.appdynamics.monitors.kubernetes.SnapshotTasks;

import com.appdynamics.extensions.TasksExecutionServiceProvider;
import com.appdynamics.extensions.metrics.Metric;
import com.appdynamics.extensions.util.AssertUtils;
import com.appdynamics.monitors.kubernetes.Metrics.UploadMetricsTask;
import com.appdynamics.monitors.kubernetes.Models.AppDMetricObj;
import com.appdynamics.monitors.kubernetes.Models.SummaryObj;
import com.appdynamics.monitors.kubernetes.Models.NodeRole;
import com.appdynamics.monitors.kubernetes.RestClient;
import com.appdynamics.monitors.kubernetes.Utilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;


import io.kubernetes.client.ApiClient;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.models.*;
import io.sundr.shaded.org.apache.velocity.runtime.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;

import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;


import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import afu.org.checkerframework.checker.units.qual.h;

import static com.appdynamics.monitors.kubernetes.Constants.*;
import static com.appdynamics.monitors.kubernetes.Utilities.*;

public class QuotaSnapshotRunner extends SnapshotRunnerBase {

    public QuotaSnapshotRunner(){

    }

    public QuotaSnapshotRunner(final TasksExecutionServiceProvider serviceProvider, final Map<String, String> config, final CountDownLatch countDownLatch){
        super(serviceProvider, config, countDownLatch);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        AssertUtils.assertNotNull(getConfiguration(), "The job configuration cannot be empty");
        generateQuotaSnapshot();
    }

//    "request_cpu", "float",
//            "request_memory", "float",
//            "limit_cpu", "float",
//            "limit_memory", "float",

    private void generateQuotaSnapshot(){
        logger.info("Proceeding to Quota update...");
        final Map<String, String> config = (Map<String, String>) getConfiguration().getConfigYml();
        if (config != null) {
            final String apiKey = Utilities.getEventsAPIKey(config);
            final String accountName = Utilities.getGlobalAccountName(config);
            final URL publishUrl = Utilities.ensureSchema(config, apiKey, accountName,CONFIG_SCHEMA_NAME_POD, CONFIG_SCHEMA_DEF_POD);

            try {
                V1ResourceQuotaList quotaList;

                try {
                    final ApiClient client = Utilities.initClient(config);
                    this.setAPIServerTimeout(client, K8S_API_TIMEOUT);
                    Configuration.setDefaultApiClient(client);
                    final CoreV1Api api = new CoreV1Api();
                    /* public V1ResourceQuotaList listResourceQuotaForAllNamespaces(String _continue, String fieldSelector, Boolean includeUninitialized, String labelSelector, Integer limit, String pretty, String resourceVersion, Integer timeoutSeconds, Boolean watch) throws ApiException {
                        ApiResponse<V1ResourceQuotaList> resp = listResourceQuotaForAllNamespacesWithHttpInfo(_continue, fieldSelector, includeUninitialized, labelSelector, limit, pretty, resourceVersion, timeoutSeconds, watch);
                        return resp.getData();
                    } */
                    this.setCoreAPIServerTimeout(api, K8S_API_TIMEOUT);
                    quotaList = api.listResourceQuotaForAllNamespaces(null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null);
                }
                catch (final Exception ex){
                    throw new Exception("Unable to connect to Kubernetes API server because it may be unavailable or the cluster credentials are invalid", ex);
                }

                createQuotaPayload(quotaList, config, publishUrl, accountName, apiKey);

                
                
                /* Config to get Total metrics collected */
                SummaryObj summaryScript = getSummaryMap().get("QuotaScript");
                if (summaryScript == null) {
                    summaryScript = initScriptSummaryObject(config, "Quota");
                    getSummaryMap().put("QuotaScript", summaryScript);
                }


                final Integer metrics_count = getMetricsFromSummary(getSummaryMap(), config).size();
                //incrementField(summaryMetrics, "NodeMetricsCollected", metrics_count);
                incrementField(summaryScript, "QuotaMetricsCollected", metrics_count);

                /* End config Summary Metrics */


                //build and update metrics
                final List<Metric> metricList = getMetricsFromSummary(getSummaryMap(), config);

                logger.info("About to send {} quota metrics", metricList.size());
                final UploadMetricsTask quotaMetricsTask = new UploadMetricsTask(getConfiguration(), getServiceProvider().getMetricWriteHelper(), metricList, countDownLatch);
                getConfiguration().getExecutorService().execute("UploadMetricsTask", quotaMetricsTask);

                //check searches
            } catch (final IOException e) {
                countDownLatch.countDown();
                logger.error("Failed to push QUOTA data", e);
            } catch (final Exception e) {
                countDownLatch.countDown();
                logger.error("Failed to push QUOTA data", e);
            }
        }
    }

     ArrayNode createQuotaPayload(final V1ResourceQuotaList quotaList, final Map<String, String> config, final URL publishUrl, final String accountName, final String apiKey){
        final ObjectMapper mapper = new ObjectMapper();
        ArrayNode arrayNode = mapper.createArrayNode();
        
        // Read historical Node rol//es
        
        Map<String,String> mapNodes = new HashMap<String,String>();
        try {
   
            // convert JSON file to map
            mapNodes = mapper.readValue(Paths.get(Utilities.getExtensionDirectory()+"/nodes.roles").toFile(), HashMap.class);
            logger.info("Successfull reading the historical node roles");
            //List<NodeRole> nodesRoles = Arrays.asList(mapper.readValue(Paths.get("nodes.roles").toFile(), NodeRole[].class));
        
        } catch (final Exception ex) {
            logger.error("Fail reading the historical node roles - maybe it is the first time");
            logger.error(ex.getMessage());
        }

        final long batchSize = Long.parseLong(config.get(CONFIG_RECS_BATCH_SIZE));
        
        // Variable to count namespaces
        final HashMap<String, Integer> namespaces = new HashMap<String, Integer>();

        for(final V1ResourceQuota quotaItem : quotaList.getItems()){

            ObjectNode quotaObject = mapper.createObjectNode();
            final String namespace = quotaItem.getMetadata().getNamespace();
            

            if (namespace == null || namespace.isEmpty()){
                logger.info(String.format("Quota %s missing namespace attribution", quotaItem.getMetadata().getName()));
            }


            final String clusterName = Utilities.ensureClusterName(config, quotaItem.getMetadata().getClusterName());

            SummaryObj summary = getSummaryMap().get(ALL);
            if (summary == null) {
                summary = initQuotaSummaryObject(config, ALL, ALL);
                getSummaryMap().put(ALL, summary);
            }

            SummaryObj summaryNamespace = getSummaryMap().get(namespace);
            if (Utilities.shouldCollectMetricsForNamespace(getConfiguration(), namespace)) {
                if (summaryNamespace == null) {
                    summaryNamespace = initQuotaSummaryObject(config, namespace, ALL);
                    getSummaryMap().put(namespace, summaryNamespace);
                }
            }


            quotaObject = checkAddObject(quotaObject, quotaItem.getMetadata().getUid(), "object_uid");

            quotaObject = checkAddObject(quotaObject, clusterName, "clusterName");
            quotaObject = checkAddObject(quotaObject, quotaItem.getMetadata().getCreationTimestamp(), "creationTimestamp");
            quotaObject = checkAddObject(quotaObject, quotaItem.getMetadata().getDeletionTimestamp(), "deletionTimestamp");

            if (quotaItem.getMetadata().getLabels() != null) {
                String labels = "";
                final Iterator it = quotaItem.getMetadata().getLabels().entrySet().iterator();
                while (it.hasNext()) {
                    final Map.Entry pair = (Map.Entry)it.next();
                    labels += String.format("%s:%s;", pair.getKey(), pair.getValue());
                    it.remove();
                }
                quotaObject = checkAddObject(quotaObject, labels, "labels");
            }

            if (quotaItem.getMetadata().getAnnotations() != null){
                String annotations = "";
                final Iterator it = quotaItem.getMetadata().getAnnotations().entrySet().iterator();
                while (it.hasNext()) {
                    final Map.Entry pair = (Map.Entry)it.next();
                    annotations += String.format("%s:%s;", pair.getKey(), pair.getValue());
                    it.remove();
                }
                quotaObject = checkAddObject(quotaObject, annotations, "annotations");
            }

            quotaObject = checkAddObject(quotaObject, quotaItem.getMetadata().getName(), "name");
            quotaObject = checkAddObject(quotaObject, namespace, "namespace");

            


            
            if (quotaItem.getStatus() != null) {
                String tolerations = "";
                final V1ResourceQuotaStatus quotaStatus = quotaItem.getStatus();
                final Map<String, String> hardLimit = quotaStatus.getHard();
                final Map<String, String> usedLimit = quotaStatus.getUsed();

                for(final Map.Entry<String, String> hard : hardLimit.entrySet()){
                    final String hardKey = hard.getKey().toString();
                    final BigDecimal hardValue = new BigDecimal(hard.getValue());
                    logger.info("Hard Key:"+hardKey);
                    logger.info("Hard Value:"+hardValue);
                    if (hardKey == "cpu" ) {
                        Utilities.incrementField(summary, ("ResourceQuotaHardCPU"), (hardValue.multiply(new BigDecimal(1000))));
                        Utilities.incrementField(summaryNamespace, ("ResourceQuotaHardCPU"), (hardValue.multiply(new BigDecimal(1000))));
                        quotaObject = checkAddObject(quotaObject, hardValue, "ResourceQuotaHardCPU");
                    }
                    else{
                        Utilities.incrementField(summary, ("ResourceQuotaHardMemory"), hardValue);
                        Utilities.incrementField(summaryNamespace, ("ResourceQuotaHardMemory"), hardValue);
                        quotaObject = checkAddObject(quotaObject, hardValue, "ResourceQuotaHardCPU");
                    }
                    
                }
                
                for(final Map.Entry<String, String> used : usedLimit.entrySet()){
                    final String usedKey = used.getKey().toString();
                    final BigDecimal usedValue = new BigDecimal(used.getValue());
                    logger.info("Used Key:"+usedKey);
                    logger.info("Used Value:"+usedValue);
                    if (usedKey == "cpu" ) {
                        Utilities.incrementField(summary, ("ResourceQuotaUsedCPU"), (usedValue.multiply(new BigDecimal(1000))));
                        Utilities.incrementField(summaryNamespace, ("ResourceQuotaUsedCPU"), (usedValue.multiply(new BigDecimal(1000))));
                        quotaObject = checkAddObject(quotaObject, usedValue, "ResourceQuotaUsedCPU");
                    }
                    else{
                        Utilities.incrementField(summary, ("ResourceQuotaUsedMemory"), usedValue);
                        Utilities.incrementField(summaryNamespace, ("ResourceQuotaUsedMemory"), usedValue);
                        quotaObject = checkAddObject(quotaObject, usedValue, "ResourceQuotaUsedCPU");
                    }
                     
                }
                
            }

            arrayNode.add(quotaObject);
            if (arrayNode.size() >= batchSize){
                logger.info("Sending batch of {} Quota records", arrayNode.size());
                final String payload = arrayNode.toString();
                arrayNode = arrayNode.removeAll();
                if(!payload.equals("[]")){
                    //final UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                    //getConfiguration().getExecutorService().execute("UploadQuotaData", uploadEventsTask);
                }
            }
        }
        
        
                
        if (arrayNode.size() > 0){
             logger.info("Sending last batch of {} Quota records", arrayNode.size());
             final String payload = arrayNode.toString();
             arrayNode = arrayNode.removeAll();
             if(!payload.equals("[]")){
                 //final UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                 //getConfiguration().getExecutorService().execute("UploadQuotaData", uploadEventsTask);
             }
         }
        return  arrayNode;
    }

    protected SummaryObj initDefaultSummaryObject(final Map<String, String> config){
        return initQuotaSummaryObject(config, ALL, ALL);
    }

    public  static SummaryObj initQuotaSummaryObject(final Map<String, String> config, final String namespace, final String node){
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode summary = mapper.createObjectNode();

        
        summary.put("namespace", namespace);
        summary.put("ResourceQuotaUsedCPU", 0);
        summary.put("ResourceQuotaUsedMemory", 0);
        summary.put("ResourceQuotaHardCPU", 0);
        summary.put("ResourceQuotaHardMemory", 0);
       
        final String path = Utilities.getMetricsPath(config, namespace, node);

        final ArrayList<AppDMetricObj> metricsList = initMetrics(config, namespace, node);

        logger.info("Init Quota Path:"+path);

        return new SummaryObj(summary, metricsList, path);
    }

    public  static  ArrayList<AppDMetricObj> initMetrics(final Map<String, String> config, final String namespace, final String node){
        if (Utilities.ClusterName == null || Utilities.ClusterName.isEmpty()){
            return new ArrayList<AppDMetricObj>();
        }
        final String rootPath = String.format("Application Infrastructure Performance|%s|Custom Metrics|Cluster Stats|", Utilities.getClusterTierName(config));
        final String clusterName = Utilities.ClusterName;
        final String parentSchema = config.get(CONFIG_SCHEMA_NAME_POD);
        final ArrayList<AppDMetricObj> metricsList = new ArrayList<AppDMetricObj>();
        String namespacesCondition = "";
        String nodeCondition = "";
        if(namespace != null && !namespace.equals(ALL)){
            namespacesCondition = String.format("and namespace = \"%s\"", namespace);
        }

        if(node != null && !node.equals(ALL)){
            nodeCondition = String.format("and nodeName = \"%s\"", node);
        }

        final String filter = namespacesCondition.isEmpty() ? nodeCondition : namespacesCondition;

        if (namespace != null && namespace.equals(ALL) && node != null && node.equals(ALL)) {

            metricsList.add(new AppDMetricObj("Pods", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("Containers", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where containerCount > 0 and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("InitContainers", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where initContainerCount > 0 and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("Evictions", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where reason = \"Evicted\" and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));

        }
        return metricsList;
    }
}

