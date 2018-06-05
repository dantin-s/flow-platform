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

package com.flow.platform.api.envs;

import com.google.common.collect.ImmutableSet;
import java.util.Set;

/**
 * @author yang
 */
public interface EnvKey {

    Set<String> VALUES_BOOLEAN = ImmutableSet.of("true", "false");

    /**
     * The env variable should write to root node result output
     */
    Set<String> FOR_OUTPUTS = ImmutableSet.of(
        GitEnvs.FLOW_GIT_BRANCH.name(),
        GitEnvs.FLOW_GIT_CHANGELOG.name(),
        GitEnvs.FLOW_GIT_COMMIT_ID.name(),
        GitEnvs.FLOW_GIT_COMMIT_URL.name(),
        GitEnvs.FLOW_GIT_COMPARE_ID.name(),
        GitEnvs.FLOW_GIT_COMPARE_URL.name(),
        GitEnvs.FLOW_GIT_AUTHOR.name(),
        GitEnvs.FLOW_GIT_PR_URL.name(),
        GitEnvs.FLOW_GIT_PR_STATE.name(),
        GitEnvs.FLOW_GIT_PR_MERGEBY.name(),
        GitEnvs.FLOW_GIT_EVENT_TYPE.name(),
        GitEnvs.FLOW_GIT_EVENT_SOURCE.name(),
        JobEnvs.FLOW_JOB_AGENT_DEPLOYMENT.name(),
        JobEnvs.FLOW_JOB_LOG_PATH.name()
    );

    String name();

    /**
     * Is readonly for user
     */
    boolean isReadonly();

    /**
     * Is editable from property UI
     */
    boolean isEditable();

    /**
     * Available values, or null for not defined
     */
    Set<String> availableValues();
}
