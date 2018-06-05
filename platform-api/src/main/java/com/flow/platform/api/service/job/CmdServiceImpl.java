/*
 * Copyright 2017 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flow.platform.api.service.job;

import com.flow.platform.api.domain.EnvObject;
import com.flow.platform.api.domain.job.Job;
import com.flow.platform.api.domain.node.Node;
import com.flow.platform.api.envs.AgentEnvs;
import com.flow.platform.api.envs.EnvUtil;
import com.flow.platform.api.envs.FlowEnvs;
import com.flow.platform.api.envs.JobEnvs;
import com.flow.platform.api.service.node.NodeService;
import com.flow.platform.api.util.PlatformURL;
import com.flow.platform.core.exception.HttpException;
import com.flow.platform.core.exception.IllegalParameterException;
import com.flow.platform.core.exception.IllegalStatusException;
import com.flow.platform.domain.AgentPath;
import com.flow.platform.domain.Cmd;
import com.flow.platform.domain.CmdInfo;
import com.flow.platform.domain.CmdType;
import com.flow.platform.domain.Jsonable;
import com.flow.platform.util.ExceptionUtil;
import com.flow.platform.util.Logger;
import com.flow.platform.util.http.HttpClient;
import com.flow.platform.util.http.HttpResponse;
import com.flow.platform.util.http.HttpURL;
import com.google.common.base.Strings;
import java.io.UnsupportedEncodingException;
import org.apache.http.entity.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Response for send cmd to control center
 *
 * @author yang
 */
@Service
public class CmdServiceImpl implements CmdService {

    private final static Logger LOGGER = new Logger(CmdService.class);

    private final static String DEFAULT_CMD_TIMEOUT = "3600";

    private final int httpRetryTimes = 5;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private PlatformURL platformURL;

    @Value(value = "${api.zone.default}")
    private String zone;

    @Value(value = "${domain.api}")
    private String apiDomain;

    @Override
    public String createSession(Job job, Integer retry) {
        // 如果指定了 agent，则使用指定 agent 执行
        String agent = null;
        String agentFromDeployment = job.getEnv(JobEnvs.FLOW_JOB_AGENT_DEPLOYMENT);
        if (agentFromDeployment != null && !agentFromDeployment.equals("")){
            agent = agentFromDeployment;
        }

        CmdInfo cmdInfo = new CmdInfo(zone, agent, CmdType.CREATE_SESSION, null);
        cmdInfo.setWebhook(buildCmdWebhook(job));
        LOGGER.traceMarker("CreateSession", "job id - %s", job.getId());

        // create session
        try {
            Cmd cmd = sendToQueue(cmdInfo, 1, retry);

            if (Strings.isNullOrEmpty(cmd.getSessionId())) {
                throw new IllegalStatusException("Invalid session id");
            }

            return cmd.getSessionId();
        } catch (Throwable e) {
            throw new IllegalStatusException(ExceptionUtil.findRootCause(e).getMessage());
        }
    }

    /**
     * Delete job session
     */
    @Override
    public void deleteSession(Job job) {
        CmdInfo cmdInfo = new CmdInfo(zone, null, CmdType.DELETE_SESSION, null);
        cmdInfo.setSessionId(job.getSessionId());
        cmdInfo.setWebhook(buildCmdWebhook(job));

        try {
            LOGGER.traceMarker("DeleteSession", "Send delete session for session id %s", job.getSessionId());
            sendDirectly(cmdInfo);
        } catch (UnsupportedEncodingException e) {
            LOGGER.warnMarker("DeleteSession", "Encoding error: %s", e.getMessage());
        }
    }

    @Override
    public CmdInfo runShell(Job job, Node node, String cmdId, EnvObject envVars) {
        CmdInfo cmdInfo = new CmdInfo(zone, null, CmdType.RUN_SHELL, nodeService.getRunningScript(node));
        cmdInfo.setInputs(envVars.getEnvs());
        cmdInfo.setWebhook(buildCmdWebhook(job));

        String outputFilter = envVars.getEnv(FlowEnvs.FLOW_ENV_OUTPUT_PREFIX, "FLOW_OUTPUT");
        cmdInfo.setOutputEnvFilter(EnvUtil.parseCommaEnvToList(outputFilter));

        try {
            cmdInfo.setTimeout(Integer.parseInt(envVars.getEnv(JobEnvs.FLOW_JOB_CMD_TIMEOUT, DEFAULT_CMD_TIMEOUT)));
        } catch (NumberFormatException e) {
            cmdInfo.setTimeout(Integer.parseInt(DEFAULT_CMD_TIMEOUT));
            LOGGER.warn("JobEnvs.FLOW_JOB_CMD_TIMEOUT env value is invalid");
        }

        cmdInfo.setSessionId(job.getSessionId());
        cmdInfo.setExtra(node.getPath()); // use cmd.extra to keep node path info
        cmdInfo.setCustomizedId(cmdId);
        cmdInfo.setWorkingDir(envVars.getEnv(AgentEnvs.FLOW_AGENT_WORKSPACE, null));

        try {
            LOGGER.traceMarker("RunShell", "step name - %s, node path - %s", node.getName(), node.getPath());
            sendDirectly(cmdInfo);
        } catch (Throwable e) {
            final String rootCause = ExceptionUtil.findRootCause(e).getMessage();
            final IllegalStatusException exception = new IllegalStatusException(rootCause);
            exception.setData(cmdInfo);
            throw exception;
        }

        return cmdInfo;
    }

    @Override
    public void shutdown(AgentPath path, String password) {
        CmdInfo cmdInfo = new CmdInfo(path, CmdType.SHUTDOWN, password);
        try {
            LOGGER.traceMarker("Shutdown", "Send shutdown for %s with pass '%s'", path, password);
            sendDirectly(cmdInfo);
        } catch (Throwable e) {
            String rootCause = ExceptionUtil.findRootCause(e).getMessage();
            throw new IllegalStatusException("Unable to shutdown since: " + rootCause);
        }
    }

    @Override
    public void close(AgentPath path) {
        if (path.isEmpty()) {
            throw new IllegalParameterException("Agent zone and name are required");
        }

        try {
            LOGGER.traceMarker("Close", "Send close cmd to agent: %s", path);
            sendDirectly(new CmdInfo(path, CmdType.STOP, null));
        } catch (Throwable e) {
            String rootCause = ExceptionUtil.findRootCause(e).getMessage();
            throw new IllegalStatusException("Unable to close agent since: " + rootCause);
        }
    }

    @Override
    public Cmd sendCmd(CmdInfo cmdInfo, boolean inQueue, int priority) {
        try {
            if (inQueue) {
                return sendToQueue(cmdInfo, priority, 5);
            }

            return sendDirectly(cmdInfo);
        } catch (Throwable e) {
            String rootCause = ExceptionUtil.findRootCause(e).getMessage();
            throw new IllegalStatusException("Unable to send cmd since: " + rootCause);
        }
    }

    /**
     * Send cmd to control center directly
     */
    private Cmd sendDirectly(CmdInfo cmdInfo) throws UnsupportedEncodingException {
        HttpResponse<String> response = HttpClient.build(platformURL.getCmdUrl())
            .post(cmdInfo.toJson())
            .withContentType(ContentType.APPLICATION_JSON)
            .retry(httpRetryTimes)
            .bodyAsString();

        if (!response.hasSuccess()) {
            throw new HttpException(String.format("Send cmd failure: %s", cmdInfo.getExtra()));
        }

        return Jsonable.parse(response.getBody(), Cmd.class);
    }

    /**
     * Send cmd to control center cmd queue
     *
     * @throws HttpException
     * @throws IllegalStatusException
     */
    private Cmd sendToQueue(CmdInfo cmdInfo, int priority, int retry) {
        final String url = HttpURL.build(platformURL.getQueueUrl())
            .withParam("priority", Integer.toString(priority))
            .withParam("retry", Integer.toString(retry))
            .toString();

        try {

            HttpResponse<String> response = HttpClient.build(url)
                .post(cmdInfo.toJson())
                .withContentType(ContentType.APPLICATION_JSON)
                .retry(httpRetryTimes)
                .bodyAsString();

            if (!response.hasSuccess()) {
                final String message = "Create session cmd to queue failure for url: " + url;
                throw new HttpException(message);
            }

            return Jsonable.parse(response.getBody(), Cmd.class);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Unable to send cmd since: ", e);
        }
    }

    /**
     * Build cmd callback webhook url with job id as identifier
     */
    private String buildCmdWebhook(Job job) {
        return HttpURL.build(apiDomain)
            .append("/hooks/cmd")
            .withParam("identifier", HttpURL.encode(job.getId().toString()))
            .toString();
    }
}
