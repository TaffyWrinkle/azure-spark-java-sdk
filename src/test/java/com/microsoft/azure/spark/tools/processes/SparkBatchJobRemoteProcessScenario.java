// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.azure.spark.tools.processes;

import com.microsoft.azure.spark.tools.clusters.HdiCluster;
import com.microsoft.azure.spark.tools.http.AmbariHttpObservable;
import com.microsoft.azure.spark.tools.job.Deployable;
import com.microsoft.azure.spark.tools.job.HdiSparkBatchFactory;
import com.microsoft.azure.spark.tools.job.PostBatchesHelper;
import com.microsoft.azure.spark.tools.restapi.livy.batches.api.PostBatches;
import com.microsoft.azure.spark.tools.utils.LogMonitor;
import com.microsoft.azure.spark.tools.utils.MockHttpRecordingArgs;
import com.microsoft.azure.spark.tools.utils.MockHttpService;

import cucumber.api.java.After;
import cucumber.api.java.Before;
import cucumber.api.java.en.And;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.mockito.Mockito;
import picocli.CommandLine;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@CommandLine.Command(
        description = "Record class SparkBatchJobRemoteProcess all requests and response for testing.",
        name = "SparkBatchJobRemoteProcessScenario",
        mixinStandardHelpOptions = true,
        version = "1.0")
public class SparkBatchJobRemoteProcessScenario implements Callable<Void> {
    private PostBatches postBatches;
    private MockHttpService hdiServiceMock;
    private SparkBatchJobRemoteProcess sparkJobRemoteProcess;

    @Before("@SparkBatchJobRemoteProcessScenario")
    public void setUp() {
        hdiServiceMock = MockHttpService.createFromSaving(this.getClass().getName());
    }

    @After("@SparkBatchJobRemoteProcessScenario")
    public void cleanUp() {
        hdiServiceMock.stop();
    }

    @Given("^create PostBatches with the following job config for SparkBatchJobRemoteProcess$")
    public void createPostBatches(Map<String, String> jobConf) {
        postBatches = PostBatchesHelper.createSubmitParams(jobConf);
    }

    @And("^submit HDInsight Spark job")
    public void submitJob() throws Throwable {
        sparkJobRemoteProcess = createSparkJobRemoteProcess(hdiServiceMock, postBatches);
        sparkJobRemoteProcess.start();
        assertEquals(0, sparkJobRemoteProcess.waitFor());
    }

    @Then("^check the HDInsight Spark job stdout should be")
    public void checkStdout(List<String> expect) throws Exception {
        String actual = IOUtils.toString(sparkJobRemoteProcess.getInputStream(), StandardCharsets.UTF_8);

        assertEquals("Stdout is unmatched, the captured logs:\n"
                        + StringUtils.join(LogMonitor.getSparkToolsLogs(), "\n") + "\n",
                StringUtils.join(expect, "\n") + "\n", actual);
    }

    private SparkBatchJobRemoteProcess createSparkJobRemoteProcess(MockHttpService recordingProxyService, PostBatches batchParam) {
        HdiCluster cluster = new HdiCluster() {
            @Override
            public String getYarnNMConnectionUrl() {
                return "http://localhost:" + recordingProxyService.getPort() + "/yarnui/ws/v1/cluster/apps/";
            }

            @Override
            public String getYarnUIBaseUrl() {
                return "http://localhost:" + recordingProxyService.getPort() + "/yarnui/";
            }

            @Override
            public String getLivyConnectionUrl() {
                return "http://localhost:" + recordingProxyService.getPort() + "/livy/";
            }
        };

        AmbariHttpObservable http = recordingArgs.getUsername() != null
                ? new AmbariHttpObservable(recordingArgs.getUsername(), recordingArgs.getPassword())
                : new AmbariHttpObservable();

        Deployable deployable = Mockito.mock(Deployable.class);

        return new SparkBatchJobRemoteProcess.Builder(
                new HdiSparkBatchFactory(cluster, batchParam, deployable).http(http)).build();
    }

    // Main function for recording mode
    @Mixin
    private MockHttpRecordingArgs recordingArgs = new MockHttpRecordingArgs();

    @Option(names = "--mainClassName", description = "Spark job main class name")
    private String mainClassName;

    @Option(names = "--artifactUri", description = "Spark job artifact URI")
    private URI artifactUri;

    @Option(names = "--log", description = "Output the logs captured")
    private boolean doesPrintLog;

    @Option(names = "--dry-run", description = "Dry run without saving response")
    private boolean isDryRun;

    private PostBatches createSubmitParamFromArgs() {
        PostBatches.Options batchParamOptions = new PostBatches.Options()
                .className(mainClassName)
                .artifactUri(artifactUri.toString());

        return batchParamOptions.build();
    }

    public static void main(String[] args) throws IOException {
        SparkBatchJobRemoteProcessScenario scenario = new SparkBatchJobRemoteProcessScenario();
        CommandLine.call(scenario, args);

        assertNotNull(scenario.recordingArgs.getTargetUrl());
        assertNotNull(scenario.mainClassName);
        assertNotNull(scenario.artifactUri);

        MockHttpService recordingProxyService = MockHttpService.createForRecord(
                SparkBatchJobRemoteProcessScenario.class.getName(),
                scenario.recordingArgs.getTargetUrl().toString(),
                !scenario.isDryRun);
        SparkBatchJobRemoteProcess sparkJobRemoteProcess = scenario.createSparkJobRemoteProcess(
                recordingProxyService, scenario.createSubmitParamFromArgs());
        sparkJobRemoteProcess.start();

        System.out.println("========= stderr =========");
        String stderr = IOUtils.toString(sparkJobRemoteProcess.getErrorStream(), StandardCharsets.UTF_8);
        System.out.print(stderr);

        System.out.println("========= stdout =========");
        String stdout = IOUtils.toString(sparkJobRemoteProcess.getInputStream(), StandardCharsets.UTF_8);
        System.out.print(stdout);

        recordingProxyService.getServer().stopRecording();
        recordingProxyService.getServer().stop();

        if (scenario.doesPrintLog) {
            System.out.println("========= log4j =========");
            System.out.println(StringUtils.join(LogMonitor.getSparkToolsLogs(), "\n"));
        }
    }

    @Override
    public Void call() throws Exception {
        return null;
    }

    @Then("check the HDInsight Spark job stderr should match the following line and log")
    public void checkTheHDInsightSparkJobStderrShouldStartWith(Map<Integer, String> expectLineLogs) throws IOException {
        List<String> actualLogs = Arrays.asList(
                IOUtils.toString(sparkJobRemoteProcess.getErrorStream(), StandardCharsets.UTF_8).split("\n"));

        for (Map.Entry<Integer, String> expectLineAndLog : expectLineLogs.entrySet()) {
            int line = expectLineAndLog.getKey() < 0
                    ? actualLogs.size() + expectLineAndLog.getKey()
                    : expectLineAndLog.getKey();

            assertEquals(expectLineAndLog.getValue(), actualLogs.get(line));
        }

    }
}
