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

package com.flow.platform.api.controller;

import com.flow.platform.api.config.AppConfig;
import com.flow.platform.api.domain.job.Job;
import com.flow.platform.api.domain.job.JobCategory;
import com.flow.platform.api.domain.node.Node;
import com.flow.platform.api.domain.user.User;
import com.flow.platform.api.envs.GitEnvs;
import com.flow.platform.api.envs.GitToggleEnvs;
import com.flow.platform.api.git.GitEventEnvConverter;
import com.flow.platform.api.git.GitWebhookTriggerFinishEvent;
import com.flow.platform.api.service.job.JobService;
import com.flow.platform.core.exception.FlowException;
import com.flow.platform.core.exception.IllegalStatusException;
import com.flow.platform.domain.Jsonable;
import com.flow.platform.util.Logger;
import com.flow.platform.util.StringUtil;
import com.flow.platform.util.git.GitException;
import com.flow.platform.util.git.hooks.GitHookEventFactory;
import com.flow.platform.util.git.model.GitEvent;
import com.flow.platform.util.git.model.GitEventType;
import com.google.common.base.Strings;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author yang
 */
@RestController
@RequestMapping("/hooks/git")
public class GitWebHookController extends NodeController {

    private final static Logger LOGGER = new Logger(GitWebHookController.class);

    private final static String SKIP_SIGNAL = "[skip]";

    @Autowired
    private JobService jobService;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @PostMapping(path = "/{root}")
    public void onEventReceived(@RequestHeader HttpHeaders headers, HttpServletRequest request) {
        final String path = currentNodePath.get();
        Map<String, String> headerAsMap = headers.toSingleValueMap();

        String body;
        try {
            request.setCharacterEncoding(AppConfig.DEFAULT_CHARSET.name());
            body = CharStreams.toString(request.getReader());
        } catch (IOException e) {
            throw new IllegalStatusException("Cannot read raw body");
        }

        try {
            final GitEvent hookEvent = GitHookEventFactory.build(headerAsMap, body);
            Node flow = nodeService.find(path).root();
            // extract git related env variables from event, and temporary set to node for git loading
            final Map<String, String> gitEnvs = GitEventEnvConverter.convert(hookEvent);

            LOGGER.trace("Git Webhook received: %s", hookEvent.toString());

            final String changeLog = gitEnvs.get(GitEnvs.FLOW_GIT_CHANGELOG.toString());
            if (!Strings.isNullOrEmpty(changeLog) && changeLog.contains(SKIP_SIGNAL)) {
                LOGGER.trace("Skipped");
                return;
            }

            if (!canExecuteGitEvent(flow, gitEnvs)) {
                LOGGER.warn("The git event not match flow settings");
                return;
            }

            // get user email from git event
            User user = new User(hookEvent.getUserEmail(), StringUtil.EMPTY, StringUtil.EMPTY);
            JobCategory jobCategory = GitEventEnvConverter.convert(hookEvent.getType());
            Job newJob = jobService.createFromFlowYml(path, jobCategory, gitEnvs, user);
            applicationEventPublisher.publishEvent(new GitWebhookTriggerFinishEvent(newJob));

        } catch (GitException | FlowException e) {
            LOGGER.warn("Cannot process web hook event: %s", e.getMessage());
        }
    }

    // todo: 增加判断, pr 关闭时可不触发构建
    private boolean canExecuteGitEvent(Node flow, Map<String, String> gitEnvs) {
        String gitEventType = gitEnvs.get(GitEnvs.FLOW_GIT_EVENT_TYPE.name());
        String gitBranch = gitEnvs.get(GitEnvs.FLOW_GIT_BRANCH.name());

        Boolean pushEnabled = Boolean.parseBoolean(flow.getEnv(GitToggleEnvs.FLOW_GIT_PUSH_ENABLED, "true"));
        Boolean tagEnabled = Boolean.parseBoolean(flow.getEnv(GitToggleEnvs.FLOW_GIT_TAG_ENABLED, "true"));
        Boolean prEnabled = Boolean.parseBoolean(flow.getEnv(GitToggleEnvs.FLOW_GIT_PR_ENABLED, "true"));

        final String[] pushFilter = Jsonable.GSON_CONFIG.fromJson(
            flow.getEnv(GitToggleEnvs.FLOW_GIT_PUSH_FILTER, GitToggleEnvs.DEFAULT_FILTER), String[].class);

        final String[] tagFilter = Jsonable.GSON_CONFIG.fromJson(
            flow.getEnv(GitToggleEnvs.FLOW_GIT_TAG_FILTER, GitToggleEnvs.DEFAULT_FILTER), String[].class);

        if (Objects.equals(gitEventType, GitEventType.PUSH.name())) {
            if (!pushEnabled) {
                return false;
            }

            if (pushFilter.length > 0) {
                return regexFilter(gitBranch, pushFilter);
            }

            return true;
        }

        if (Objects.equals(gitEventType, GitEventType.PR.name())) {
            return prEnabled;
        }

        if (Objects.equals(gitEventType, GitEventType.TAG.name())) {
            if (!tagEnabled) {
                return false;
            }

            if (tagFilter.length > 0) {
                return regexFilter(gitBranch, tagFilter);
            }

            return true;
        }

        return true;
    }

    private boolean regexFilter(String gitBranch, String[] filter) {
        for (String f : filter) {

            // convert * to RE
            if (f.equals("*")) {
                f = ".*";
            }

            Pattern rex = Pattern.compile(f);
            Matcher m = rex.matcher(gitBranch);
            if (m.find()) {
                return true;
            }
        }
        return false;
    }
}