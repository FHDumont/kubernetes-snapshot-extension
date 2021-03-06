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

import static com.appdynamics.monitors.kubernetes.Constants.*;
import static com.appdynamics.monitors.kubernetes.Utilities.*;

public class PodSnapshotRunner extends SnapshotRunnerBase {

    public PodSnapshotRunner(){

    }

    public PodSnapshotRunner(final TasksExecutionServiceProvider serviceProvider, final Map<String, String> config, final CountDownLatch countDownLatch){
        super(serviceProvider, config, countDownLatch);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        AssertUtils.assertNotNull(getConfiguration(), "The job configuration cannot be empty");
        generatePodSnapshot();
    }

//    "request_cpu", "float",
//            "request_memory", "float",
//            "limit_cpu", "float",
//            "limit_memory", "float",

    private void generatePodSnapshot(){
        logger.info("Proceeding to POD update...");
        final Map<String, String> config = (Map<String, String>) getConfiguration().getConfigYml();
        if (config != null) {
            final String apiKey = Utilities.getEventsAPIKey(config);
            final String accountName = Utilities.getGlobalAccountName(config);
            final URL publishUrl = Utilities.ensureSchema(config, apiKey, accountName,CONFIG_SCHEMA_NAME_POD, CONFIG_SCHEMA_DEF_POD);

            try {
                V1PodList podList;

                try {
                    final ApiClient client = Utilities.initClient(config);
                    this.setAPIServerTimeout(client, K8S_API_TIMEOUT);
                    Configuration.setDefaultApiClient(client);
                    final CoreV1Api api = new CoreV1Api();

                    this.setCoreAPIServerTimeout(api, K8S_API_TIMEOUT);
                    podList = api.listPodForAllNamespaces(null,
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

                createPodPayload(podList, config, publishUrl, accountName, apiKey);

                
                
                /* Config to get Total metrics collected */
                SummaryObj summaryScript = getSummaryMap().get("PodScript");
                if (summaryScript == null) {
                    summaryScript = initScriptSummaryObject(config, "Pod");
                    getSummaryMap().put("PodScript", summaryScript);
                }


                final Integer metrics_count = getMetricsFromSummary(getSummaryMap(), config).size();
                //incrementField(summaryMetrics, "NodeMetricsCollected", metrics_count);
                incrementField(summaryScript, "PodMetricsCollected", metrics_count);

                /* End config Summary Metrics */

                // Pod Restart fix 

                // File to read and save podRestart history
                final String podHistoryPath = Utilities.getExtensionDirectory();

                //final File podHistoryFile = new File(podHistoryPath+"/"+"history.tmp");

                //logger.info("History file: " + podHistoryFile);
                
                //JSON parser object to parse read file
                final JSONParser jsonParser = new JSONParser();
                Integer podRestartsSum = 0;
                Integer clusterPodRestarts = 0;
                final Integer nodepodRestartsSum = 0;
                final Integer namespacepodRestartsSum = 0;
                Integer podRestartsHist = 0;

                final boolean historyExist = true; //podHistoryFile.exists();
                final String podHistoryFile = podHistoryPath+"/"+"history.tmp";
                if(historyExist){
                    //try (InputStream inputStream = this.getClass().getResourceAsStream(podHistoryFile)) 
                    try (InputStream inputStream = new FileInputStream(new File(podHistoryFile)))
                    {
                        // To String
                        //creating an InputStreamReader object
                        final InputStreamReader isReader = new InputStreamReader(inputStream);
                        //Creating a BufferedReader object
                        final BufferedReader reader = new BufferedReader(isReader);
                        final StringBuffer sb = new StringBuffer();
                        String str;
                        while((str = reader.readLine())!= null){
                            sb.append(str);
                        }
                        
                        //Read JSON file
                        final Object obj = jsonParser.parse(sb.toString());
                        final JSONObject podRestartHistoryJson = (JSONObject) obj;
                        logger.debug("History PodRestarts:"+(String) podRestartHistoryJson.get("podRestarts").toString());
                        final Integer podRestartHistory = (Integer) Math.toIntExact((Long) podRestartHistoryJson.get("podRestarts"));
                        podRestartsHist = podRestartHistory;
                        reader.close();
                        inputStream.close();
                        isReader.close();
                        logger.info("File "+podHistoryFile+" loaded with success");
                        logger.debug("History PodRestarts Converted to integer:"+ podRestartsHist.toString());
                        
                    } catch (final FileNotFoundException e) {
                        podRestartsHist = 0;
                        logger.error("NotFound - Issues when reading podRestart History file: "+podHistoryFile, e.getMessage());
                        logger.info("Sending PodRestarts as 0 because PodRestarts history file doesn't exist and it will be created");
                    } catch (final IOException e) {
                        podRestartsHist = 0;
                        logger.error("IOException - Issues when reading podRestart History file: "+podHistoryFile, e.getMessage());
                        logger.info("Sending PodRestarts as 0 because of IOException");
                    } catch (final ParseException e) {
                        podRestartsHist = 0;
                        logger.error("ParseException - Issues when reading podRestart History file: "+podHistoryFile, e.getMessage());
                        logger.info("Sending PodRestarts as 0 because Could not parse the file, please delete the file "+podHistoryFile+" if its corrupt");
                    } catch (final Exception e){
                        podRestartsHist = 0;
                        logger.error("Exception - Issues when reading podRestart History file: "+podHistoryFile, e.getMessage());
                        logger.info(e.getLocalizedMessage());
                        e.printStackTrace();
                    }
                }
                //Save and Add PodRestarts history file



                SummaryObj summary = getSummaryMap().get(ALL);
                //SummaryObj summaryNamespace = getSummaryMap().get(ALL);
                //SummaryObj summaryNode = getSummaryMap().get(ALL);
                if (summary == null) {
                    summary = initPodSummaryObject(config, ALL, ALL);
                    getSummaryMap().put(ALL, summary);
                }else{
                    try {
                        podRestartsSum = summary.getData().get("PodRestarts").intValue();
                    } catch (final Exception e) {
                        podRestartsSum = 0;
                    }
                    
                }
                logger.debug("Pod Sum Restarts"+podRestartsSum+" - "+"Pod History Sum Restarts"+podRestartsHist.toString());
                if (podRestartsHist > 0 && podRestartsSum > 0 && podRestartsSum >= podRestartsHist) {
                    clusterPodRestarts = podRestartsSum - podRestartsHist;
                }
                else{
                    clusterPodRestarts = 0;
                }

                Utilities.decrementField(summary, "PodRestarts", podRestartsHist);

                /* CPU and Memory Request Summary */



                /* END CPU and Memory Request Summary */


                final JSONObject clusterHistory = new JSONObject();
                clusterHistory.put("podRestarts", podRestartsSum);
                
                try (FileWriter file = new FileWriter(podHistoryFile)) {

                    file.write(clusterHistory.toJSONString());
                    file.flush();
                    file.close();
                    logger.info("File "+podHistoryFile+" saved with success!");

                } catch (final IOException e) {
                    logger.error("Issues when saving podRestart History file", e.getMessage());
                }

                // End Save History

                //build and update metrics
                final List<Metric> metricList = getMetricsFromSummary(getSummaryMap(), config);

                logger.info("About to send {} pod metrics", metricList.size());
                final UploadMetricsTask podMetricsTask = new UploadMetricsTask(getConfiguration(), getServiceProvider().getMetricWriteHelper(), metricList, countDownLatch);
                getConfiguration().getExecutorService().execute("UploadMetricsTask", podMetricsTask);

                //check searches
            } catch (final IOException e) {
                countDownLatch.countDown();
                logger.error("Failed to push POD data", e);
            } catch (final Exception e) {
                countDownLatch.countDown();
                logger.error("Failed to push POD data", e);
            }
        }
    }

     ArrayNode createPodPayload(final V1PodList podList, final Map<String, String> config, final URL publishUrl, final String accountName, final String apiKey){
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

        

        
        

        for(final V1Pod podItem : podList.getItems()){

            ObjectNode podObject = mapper.createObjectNode();
            final String namespace = podItem.getMetadata().getNamespace();
            final String nodeName = podItem.getSpec().getNodeName();

            // Get Role Name if exist
            String Role = "";

            try {
                Role = mapNodes.get(nodeName);
                logger.info("Role mapped: "+Role);
            } catch (final Exception e) {
                logger.info("Fail map role for node: "+nodeName);
                logger.error(e.getMessage());
            }

            namespaces.putIfAbsent(namespace, 0);
            namespaces.put(namespace, namespaces.get(namespace) + 1);

            if (namespace == null || namespace.isEmpty()){
                logger.info(String.format("Pod %s missing namespace attribution", podItem.getMetadata().getName()));
            }

            if (nodeName == null || nodeName.isEmpty()){
                logger.info(String.format("Pod %s missing node attribution", podItem.getMetadata().getName()));
            }

            final String clusterName = Utilities.ensureClusterName(config, podItem.getMetadata().getClusterName());

            SummaryObj summary = getSummaryMap().get(ALL);
            if (summary == null) {
                summary = initPodSummaryObject(config, ALL, ALL);
                getSummaryMap().put(ALL, summary);
            }

            SummaryObj summaryNamespace = getSummaryMap().get(namespace);
            if (Utilities.shouldCollectMetricsForNamespace(getConfiguration(), namespace)) {
                if (summaryNamespace == null) {
                    summaryNamespace = initPodSummaryObject(config, namespace, ALL);
                    getSummaryMap().put(namespace, summaryNamespace);
                }
            }

            SummaryObj summaryNode = getSummaryMap().get(nodeName);
            if (Utilities.shouldCollectMetricsForNode(getConfiguration(), nodeName)) {
                if (summaryNode == null) {
                    summaryNode = initPodSummaryObject(config, ALL, nodeName);
                    getSummaryMap().put(nodeName, summaryNode);
                }
            }
            
            SummaryObj summaryRole= getSummaryMap().get(Role);
            if (Role != "") {
                if (summaryRole == null) {
                    summaryRole = initPodSummaryObject(config, ALL, Role);
                    getSummaryMap().put(Role, summaryRole);
                }
            }
            final Integer totalNamespaces =  namespaces.entrySet().size();
            logger.debug("Namespaces : "+totalNamespaces);
            Utilities.setField(summary, "NamespacesRunning", totalNamespaces);
            Utilities.incrementField(summary, "Pods");
            Utilities.incrementField(summaryNamespace, "Pods");
            Utilities.incrementField(summaryNode, "Pods");
            if (Role != "") {
                Utilities.incrementField(summaryRole, "Pods");
            }

            podObject = checkAddObject(podObject, podItem.getMetadata().getUid(), "object_uid");

            podObject = checkAddObject(podObject, clusterName, "clusterName");
            podObject = checkAddObject(podObject, podItem.getMetadata().getCreationTimestamp(), "creationTimestamp");
            podObject = checkAddObject(podObject, podItem.getMetadata().getDeletionTimestamp(), "deletionTimestamp");

            if (podItem.getMetadata().getLabels() != null) {
                String labels = "";
                final Iterator it = podItem.getMetadata().getLabels().entrySet().iterator();
                while (it.hasNext()) {
                    final Map.Entry pair = (Map.Entry)it.next();
                    labels += String.format("%s:%s;", pair.getKey(), pair.getValue());
                    it.remove();
                }
                podObject = checkAddObject(podObject, labels, "labels");
            }

            if (podItem.getMetadata().getAnnotations() != null){
                String annotations = "";
                final Iterator it = podItem.getMetadata().getAnnotations().entrySet().iterator();
                while (it.hasNext()) {
                    final Map.Entry pair = (Map.Entry)it.next();
                    annotations += String.format("%s:%s;", pair.getKey(), pair.getValue());
                    it.remove();
                }
                podObject = checkAddObject(podObject, annotations, "annotations");
            }

            podObject = checkAddObject(podObject, podItem.getMetadata().getName(), "name");
            podObject = checkAddObject(podObject, namespace, "namespace");

            final int containerCount = podItem.getSpec().getContainers() != null ? podItem.getSpec().getContainers().size() : 0;
            podObject = checkAddInt(podObject, containerCount, "containerCount");

            if (containerCount > 0) {
                Utilities.incrementField(summary, "Containers", containerCount);
                Utilities.incrementField(summaryNamespace, "Containers", containerCount);
                Utilities.incrementField(summaryNode, "Containers", containerCount);
                if (Role != "") {
                    Utilities.incrementField(summaryRole, "Containers", containerCount);
                }
            }

            final int initContainerCount = podItem.getSpec().getInitContainers() != null ? podItem.getSpec().getInitContainers().size() : 0;
            podObject = checkAddInt(podObject, initContainerCount, "initContainerCount");

            if (initContainerCount > 0) {
                Utilities.incrementField(summary, "InitContainers", initContainerCount);
                Utilities.incrementField(summaryNamespace, "InitContainers", initContainerCount);
                Utilities.incrementField(summaryNode, "InitContainers", initContainerCount);
                if (Role != "") {
                    Utilities.incrementField(summaryRole, "InitContainers", initContainerCount);
                }
            }

            podObject = checkAddObject(podObject, nodeName, "nodeName");
            podObject = checkAddInt(podObject, podItem.getSpec().getPriority(), "priority");
            podObject = checkAddObject(podObject, podItem.getSpec().getRestartPolicy(), "restartPolicy");
            podObject = checkAddObject(podObject, podItem.getSpec().getServiceAccountName(), "serviceAccountName");
            podObject = checkAddLong(podObject, podItem.getSpec().getTerminationGracePeriodSeconds(), "terminationGracePeriodSeconds");

            if (podItem.getSpec().getTolerations() != null) {
                String tolerations = "";
                final int tolerationsCount = podItem.getSpec().getTolerations().size();
                Utilities.incrementField(summary, "TolerationsCount", tolerationsCount);
                Utilities.incrementField(summaryNamespace, "TolerationsCount", tolerationsCount);
                Utilities.incrementField(summaryNode, "TolerationsCount", tolerationsCount);
                if (Role != "") {
                    Utilities.incrementField(summaryRole, "TolerationsCount", tolerationsCount);
                }
                for(final V1Toleration toleration : podItem.getSpec().getTolerations()){
                    tolerations += String.format("%s;", toleration.toString());
                }
                podObject = checkAddObject(podObject, tolerations, "tolerations");
            }

             if (podItem.getSpec().getAffinity() != null) {
                final V1NodeAffinity affinity = podItem.getSpec().getAffinity().getNodeAffinity();
                if (affinity != null) {
                    Utilities.incrementField(summary, "HasNodeAffinity");
                    Utilities.incrementField(summaryNamespace, "HasNodeAffinity");
                    Utilities.incrementField(summaryNode, "HasNodeAffinity");
                    if (Role != "") {
                        Utilities.incrementField(summaryRole, "HasNodeAffinity");
                    }
                    String nodeAffinityPreferred = "";

                    if (affinity.getPreferredDuringSchedulingIgnoredDuringExecution() != null) {
                        for (final V1PreferredSchedulingTerm t : affinity.getPreferredDuringSchedulingIgnoredDuringExecution()) {
                            nodeAffinityPreferred += String.format("%s;", t.toString());
                        }
                    }
                    podObject = checkAddObject(podObject, nodeAffinityPreferred, "nodeAffinityPreferred");


                    String nodeAffinityRequired = "";
                    final V1NodeSelector nodeSelector = affinity.getRequiredDuringSchedulingIgnoredDuringExecution();
                    if (nodeSelector != null) {
                        if (nodeSelector.getNodeSelectorTerms() != null) {
                            for (final V1NodeSelectorTerm term : nodeSelector.getNodeSelectorTerms()) {
                                if (term.getMatchExpressions() != null) {
                                    for (final V1NodeSelectorRequirement req : term.getMatchExpressions()) {
                                        nodeAffinityRequired += String.format("%s;", req.toString());
                                    }
                                }
                            }
                        }
                    }
                    podObject = checkAddObject(podObject, nodeAffinityRequired, "nodeAffinityRequired");
                }
            }

            final boolean hasPodAffinity = podItem.getSpec().getAffinity() != null && podItem.getSpec().getAffinity().getPodAffinity() != null;
            podObject = checkAddBoolean(podObject, hasPodAffinity, "hasPodAffinity");
            if(hasPodAffinity){
                Utilities.incrementField(summary, "HasPodAffinity");
                Utilities.incrementField(summaryNamespace, "HasPodAffinity");
                Utilities.incrementField(summaryNode, "HasPodAffinity");
                if (Role != "") {
                    Utilities.incrementField(summaryRole, "HasPodAffinity");
                }
            }

            final boolean hasPodAntiAffinity = podItem.getSpec().getAffinity() != null && podItem.getSpec().getAffinity().getPodAntiAffinity() != null;
            podObject = checkAddBoolean(podObject, hasPodAntiAffinity, "hasPodAntiAffinity");
            if (hasPodAntiAffinity){
                Utilities.incrementField(summary, "HasPodAntiAffinity");
                Utilities.incrementField(summaryNamespace, "HasPodAntiAffinity");
                Utilities.incrementField(summaryNode, "HasPodAntiAffinity");
            }

            podObject = checkAddObject(podObject, podItem.getStatus().getHostIP(), "hostIP");

            final String phase = podItem.getStatus().getPhase();
            podObject = checkAddObject(podObject, phase, "phase");
            if (phase.equals("Pending")) {
                Utilities.incrementField(summary, "PendingPods");
                Utilities.incrementField(summaryNamespace, "PendingPods");
                Utilities.incrementField(summaryNode, "PendingPods");
                if (Role != "") {
                    Utilities.incrementField(summaryRole, "PendingPods");
                }
            }

            if (phase.equals("Failed")) {
                Utilities.incrementField(summary, "FailedPods");
                Utilities.incrementField(summaryNamespace, "FailedPods");
                Utilities.incrementField(summaryNode, "FailedPods");
            }

            if (phase.equals("Running")) {
                Utilities.incrementField(summary, "RunningPods");
                Utilities.incrementField(summaryNamespace, "RunningPods");
                Utilities.incrementField(summaryNode, "RunningPods");
                if (Role != "") {
                    Utilities.incrementField(summaryRole, "RunningPods");
                }
            }

            podObject = checkAddObject(podObject, podItem.getStatus().getPodIP(), "podIP");
            podObject = checkAddObject(podObject, podItem.getStatus().getReason(), "reason");


            if (podItem.getStatus().getReason() != null && podItem.getStatus().getReason().equals("Evicted")){
                Utilities.incrementField(summary, "Evictions");
                Utilities.incrementField(summaryNamespace, "Evictions");
                Utilities.incrementField(summaryNode, "Evictions");
                if (Role != "") {
                    Utilities.incrementField(summaryRole, "Evictions");
                }
            }
            podObject = checkAddObject(podObject, podItem.getStatus().getStartTime(), "startTime");

            if (podItem.getStatus().getConditions() != null && podItem.getStatus().getConditions().size() > 0) {
                final V1PodCondition recentCondition = podItem.getStatus().getConditions().get(0);
                podObject = checkAddObject(podObject, recentCondition.getLastTransitionTime(), "lastTransitionTimeCondition");
                podObject = checkAddObject(podObject, recentCondition.getReason(), "reasonCondition");
                podObject = checkAddObject(podObject, recentCondition.getStatus(), "statusCondition");
                podObject = checkAddObject(podObject, recentCondition.getType(), "typeCondition");
            }

            int podRestarts = 0;
            final String contStates = "";
            String images = "";
            String waitReasons = "";
            String termReasons = "";      

            if (podItem.getStatus().getContainerStatuses() != null){
                for(final V1ContainerStatus status : podItem.getStatus().getContainerStatuses()){

                    final String image = status.getImage();
                    images += String.format("%s;", image);

                    final int restarts = status.getRestartCount();
                    podRestarts += restarts;

                    if (status.getState().getWaiting()!= null){
                        waitReasons += String.format("%s;", status.getState().getWaiting().getReason());
                    }

                    if (status.getState().getTerminated() != null) {
                        termReasons += String.format("%s;", status.getState().getTerminated().getReason());
                        podObject = checkAddObject(podObject, status.getState().getTerminated().getFinishedAt(), "terminationTime");
                    }

                    if (status.getState().getRunning() != null) {
                        podObject = checkAddObject(podObject, status.getState().getRunning().getStartedAt(), "runningStartTime");
                    }
                }
                

                

                

                //container data
                podObject = checkAddObject(podObject, contStates, "containerStates");
                podObject = checkAddObject(podObject, images, "images");
                podObject = checkAddObject(podObject, waitReasons, "waitReasons");
                podObject = checkAddObject(podObject, termReasons, "termReasons");

                podObject = checkAddInt(podObject, podRestarts, "podRestarts");
                //podRestartsSum += podRestarts;
                //podObject = checkAddInt(podObject, podRestartsSum, "podRestartsSum");
                Utilities.incrementField(summary, "PodRestarts", podRestarts);
                //Utilities.incrementField(summary, "podRestartsSum", podRestartsSum);
                Utilities.incrementField(summaryNamespace, "PodRestarts", podRestarts);
                Utilities.incrementField(summaryNode, "PodRestarts", podRestarts);
                
            }

            boolean limitsDefined = false;

            int numLive = 0;
            int numReady = 0;
            int numPrivileged = 0;
            float cpuRequest = 0;
            float memRequest = 0;

            float memLimit = 0;
            float cpuLimit = 0;

            for(final V1Container container : podItem.getSpec().getContainers()){
                if (container.getSecurityContext() != null ){

                    try {
                        if (Boolean.TRUE.equals(container.getSecurityContext().isPrivileged())) {
                            numPrivileged++;
                        }
                    }
                    catch (final Exception ex){
                        logger.error("Issues when getting the privileged flag for " + podItem.getMetadata().getName(), ex.getMessage());
                    }
                }

                numLive += container.getLivenessProbe() != null ? 1 : 0;
                numReady += container.getReadinessProbe() != null ? 1 : 0;
                if (container.getPorts() != null) {
                    String ports = "";
                    for (final V1ContainerPort port : container.getPorts()) {
                        ports += String.format("%d;",port.getContainerPort());
                    }
                    podObject = checkAddObject(podObject, ports, "ports");
                }

                if (container.getResources() != null) {
                    if (container.getResources().getRequests() != null) {
                        final Set<Map.Entry<String, Quantity>> setRequests = container.getResources().getRequests().entrySet();
                        for (final Map.Entry<String, Quantity> s : setRequests) {
                            if (s.getKey().equals("memory")) {
                                memRequest += s.getValue().getNumber().divide(new BigDecimal(1000000)).floatValue(); //MB

                            }
                            if (s.getKey().equals("cpu")) {
                                cpuRequest += s.getValue().getNumber().floatValue();
                            }
                        }
                        limitsDefined = true;
                    }

                 if (container.getResources().getLimits() != null) {
                     final Set<Map.Entry<String, Quantity>> setLimits = container.getResources().getLimits().entrySet();
                     for (final Map.Entry<String, Quantity> s : setLimits) {
                         if (s.getKey().equals("memory")) {
                             memLimit += s.getValue().getNumber().divide(new BigDecimal(1000000)).floatValue(); //MB

                         }
                         if (s.getKey().equals("cpu")) {
                             cpuLimit += s.getValue().getNumber().floatValue();
                         }
                     }
                     limitsDefined = true;
                 }
                }
                if (container.getVolumeMounts() != null){

                    String mounts = "";
                    for(final V1VolumeMount vm : container.getVolumeMounts()){
                        mounts += String.format("%s;",vm.getMountPath());
                    }
                    podObject = checkAddObject(podObject, mounts, "mounts");
                }
            }

            podObject = checkAddBoolean(podObject, limitsDefined, "limitsDefined");


            podObject =  checkAddFloat(podObject, cpuRequest, "cpuRequest");
            podObject =  checkAddFloat(podObject, memRequest, "memRequest");
            podObject =  checkAddFloat(podObject, cpuLimit, "cpuLimit");
            podObject =  checkAddFloat(podObject, memLimit, "memLimit");

            if (!(podItem.getStatus().getReason() != null && podItem.getStatus().getReason().equals("Evicted"))){
                Utilities.incrementField(summary, "RequestCpu", (cpuRequest*1000));
                Utilities.incrementField(summaryNamespace, "RequestCpu", (cpuRequest*1000));
                Utilities.incrementField(summaryNode, "RequestCpu", (cpuRequest*1000));
                if (Role != "") {
                    Utilities.incrementField(summaryRole, "RequestCpu", (cpuRequest*1000));
                    Utilities.incrementField(summaryRole, "RequestMemory", memRequest);
                    Utilities.incrementField(summaryRole, "LimitCpu", (cpuLimit*1000));
                    Utilities.incrementField(summaryRole, "LimitMemory", memLimit);

                }

                Utilities.incrementField(summary, "RequestMemory", memRequest);
                Utilities.incrementField(summaryNamespace, "RequestMemory", memRequest);
                Utilities.incrementField(summaryNode, "RequestMemory", memRequest);

                Utilities.incrementField(summary, "LimitCpu", (cpuLimit*1000));
                Utilities.incrementField(summaryNamespace, "LimitCpu", (cpuLimit*1000));
                Utilities.incrementField(summaryNode, "LimitCpu", (cpuLimit*1000));

                Utilities.incrementField(summary, "LimitMemory", memLimit);
                Utilities.incrementField(summaryNamespace, "LimitMemory", memLimit);
                Utilities.incrementField(summaryNode, "LimitMemory", memLimit);

                if (numLive == 0) {
                    Utilities.incrementField(summary, "NoLivenessProbe");
                    Utilities.incrementField(summaryNamespace, "NoLivenessProbe");
                    Utilities.incrementField(summaryNode, "NoLivenessProbe");
                    if (Role != "") {
                        Utilities.incrementField(summaryRole, "NoLivenessProbe");
                    }
                }

                if (numReady == 0) {
                    Utilities.incrementField(summary, "NoReadinessProbe");
                    Utilities.incrementField(summaryNamespace, "NoReadinessProbe");
                    Utilities.incrementField(summaryNode, "NoReadinessProbe");
                    if (Role != "") {
                        Utilities.incrementField(summaryRole, "NoReadinessProbe");
                    }
                }

                if (numPrivileged > 0) {
                    Utilities.incrementField(summary, "Privileged");
                    Utilities.incrementField(summaryNamespace, "Privileged");
                    Utilities.incrementField(summaryNode, "Privileged");
                    if (Role != "") {
                        Utilities.incrementField(summaryRole, "Privileged");
                    }
                }
                if (!limitsDefined){
                    Utilities.incrementField(summary, "NoLimits");
                    Utilities.incrementField(summaryNamespace, "NoLimits");
                    Utilities.incrementField(summaryNode, "NoLimits");
                    if (Role != "") {
                        Utilities.incrementField(summaryRole, "NoLimits");
                    }
                }
            }


            podObject = checkAddInt(podObject, numLive, "liveProbes");
            podObject = checkAddInt(podObject, numReady, "readyProbes");
            podObject = checkAddInt(podObject, numPrivileged, "numPrivileged");
            arrayNode.add(podObject);
            if (arrayNode.size() >= batchSize){
                logger.info("Sending batch of {} Pod records", arrayNode.size());
                final String payload = arrayNode.toString();
                arrayNode = arrayNode.removeAll();
                if(!payload.equals("[]")){
                    final UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                    getConfiguration().getExecutorService().execute("UploadPodData", uploadEventsTask);
                }
            }
        }
        
        
                
        if (arrayNode.size() > 0){
             logger.info("Sending last batch of {} Pod records", arrayNode.size());
             final String payload = arrayNode.toString();
             arrayNode = arrayNode.removeAll();
             if(!payload.equals("[]")){
                 final UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                 getConfiguration().getExecutorService().execute("UploadPodData", uploadEventsTask);
             }
         }
        return  arrayNode;
    }

    protected SummaryObj initDefaultSummaryObject(final Map<String, String> config){
        return initPodSummaryObject(config, ALL, ALL);
    }

    public  static SummaryObj initPodSummaryObject(final Map<String, String> config, final String namespace, final String node){
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode summary = mapper.createObjectNode();

        
        summary.put("namespace", namespace);
        summary.put("nodename", node);

        summary.put("Pods", 0);
        summary.put("Evictions", 0);
        summary.put("PodRestarts", 0);
        //summary.put("PodRestartsSum", 0);
        summary.put("RunningPods", 0);
        summary.put("FailedPods", 0);
        summary.put("PendingPods", 0);
        if (namespace != null && namespace.equals(ALL) && node != null && node.equals(ALL)) {
            summary.put("Containers", 0);
            summary.put("InitContainers", 0);
            summary.put("NoLimits", 0);
            summary.put("NoReadinessProbe", 0);
            summary.put("NoLivenessProbe", 0);
            summary.put("Privileged", 0);
            summary.put("TolerationsCount", 0);
            summary.put("HasNodeAffinity", 0);
            summary.put("HasPodAffinity", 0);
            summary.put("HasPodAntiAffinity", 0);
            summary.put("NamespacesRunning", 0);
            //summary.put("PodMetricsCollected", 0);
            summary.put("RequestCpu", 0);
            summary.put("RequestMemory", 0);
        }
        else if(node != null ){//&& node.equals("Workers") || node.equals("Masters")){
            summary.put("RequestCpu", 0);
            summary.put("RequestMemory", 0);
            summary.put("LimitCpu", 0);
            summary.put("LimitMemory", 0);
        }
        else {
            summary.put("RequestCpu", 0);
            summary.put("RequestMemory", 0);
            summary.put("LimitCpu", 0);
            summary.put("LimitMemory", 0);
        }
        


        final ArrayList<AppDMetricObj> metricsList = initMetrics(config, namespace, node);

        String path = "";
        if (node.equals("Workers") || node.equals("Masters") || node.equals("Storage") || node.equals("Infra")) {
            path = Utilities.getMetricsPathV2(config, "Summary", node);
        } else {
            path = Utilities.getMetricsPath(config, namespace, node);
        }
        
        path = path.replace("Nodes", "KNodes");
        logger.info("Init Pod Path:"+path);
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
            metricsList.add(new AppDMetricObj("PodRestarts", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where podRestarts > 0 and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("RunningPods", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where phase = \"Running\" and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("FailedPods", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where phase = \"Failed\" and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("PendingPods", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where phase = \"Pending\" and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("NoLimits", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where limitsDefined = false and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("NoReadinessProbe", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where readyProbes = 0 and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("NoLivenessProbe", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where liveProbes = 0 and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("Privileged", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where numPrivileged > 0 and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));

            metricsList.add(new AppDMetricObj("RequestCpu", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where cpuRequest > 0 and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("RequestMemory", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where memRequest > 0 and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("LimitCpu", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where cpuLimit > 0 and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("LimitMemory", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where memLimit > 0 and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("TolerationsCount", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where tolerations IS NOT NULL and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));

            metricsList.add(new AppDMetricObj("HasNodeAffinity", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where (nodeAffinityPreferred IS NOT NULL Or nodeAffinityRequired IS NOT NULL) and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("HasPodAffinity", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where hasPodAffinity = true and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));
            metricsList.add(new AppDMetricObj("HasPodAntiAffinity", parentSchema, CONFIG_SCHEMA_DEF_POD,
                    String.format("select * from %s where hasPodAntiAffinity = true and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node));

        }
        return metricsList;
    }
}

