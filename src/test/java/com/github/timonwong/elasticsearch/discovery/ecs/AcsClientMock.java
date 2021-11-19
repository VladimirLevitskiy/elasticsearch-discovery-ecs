/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.github.timonwong.elasticsearch.discovery.ecs;

import com.aliyuncs.*;
import com.aliyuncs.auth.Credential;
import com.aliyuncs.auth.SignatureVersion;
import com.aliyuncs.auth.signers.SignatureAlgorithm;
import com.aliyuncs.ecs.model.v20140526.DescribeInstancesRequest;
import com.aliyuncs.ecs.model.v20140526.DescribeInstancesResponse;
import com.aliyuncs.http.HttpResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class AcsClientMock implements IAcsClient {

    private static final Logger logger = LogManager.getLogger(AcsClientMock.class);

    public static final String PREFIX_PRIVATE_IP = "10.0.0.";
    public static final String PREFIX_PUBLIC_IP = "8.8.8.";

    final List<DescribeInstancesResponse.Instance> instances = new ArrayList<>();
    final String endpoint;
    final DefaultProfile profile;
    final EcsClientSettings configuration;

    public AcsClientMock(int nodes, List<NodeOption> nodeOptions, String endpoint,
                         DefaultProfile profile, EcsClientSettings configuration) {
        assert nodeOptions == null || nodeOptions.size() == nodes;

        for (int node = 1; node < nodes + 1; node++) {
            String instanceId = "node" + node;

            final DescribeInstancesResponse.Instance instance = new DescribeInstancesResponse.Instance();
            final DescribeInstancesResponse.Instance.VpcAttributes vpcAttributes = new DescribeInstancesResponse.Instance.VpcAttributes();
            final DescribeInstancesResponse.Instance.EipAddress eipAddress = new DescribeInstancesResponse.Instance.EipAddress();

            vpcAttributes.setPrivateIpAddress(Collections.singletonList(PREFIX_PRIVATE_IP + node));
            eipAddress.setIpAddress(PREFIX_PUBLIC_IP + node);
            instance.setInstanceId(instanceId);
            instance.setStatus("Running");
            instance.setVpcAttributes(vpcAttributes);
            instance.setEipAddress(eipAddress);

            if (nodeOptions != null) {
                final NodeOption opt = nodeOptions.get(node - 1);
                instance.setTags(opt.tags);
                instance.setZoneId(opt.zoneId);
                instance.setSecurityGroupIds(opt.securityGroups);
            } else {
                instance.setTags(Collections.emptyList());
                instance.setSecurityGroupIds(Collections.emptyList());
            }

            instances.add(instance);
        }

        this.endpoint = endpoint;
        this.profile = profile;
        this.configuration = configuration;
    }

    @Override
    public <T extends AcsResponse> HttpResponse doAction(AcsRequest<T> request) {
        throw new UnsupportedOperationException("Not supported in mock");
    }

    @Override
    public <T extends AcsResponse> HttpResponse doAction(AcsRequest<T> request, boolean autoRetry, int maxRetryCounts) {
        throw new UnsupportedOperationException("Not supported in mock");
    }

    @Override
    public <T extends AcsResponse> HttpResponse doAction(AcsRequest<T> request, IClientProfile profile) {
        throw new UnsupportedOperationException("Not supported in mock");
    }

    @Override
    @SuppressWarnings("deprecation")
    public <T extends AcsResponse> HttpResponse doAction(AcsRequest<T> request, String regionId, Credential credential) {
        throw new UnsupportedOperationException("Not supported in mock");
    }

    @Override
    public <T extends AcsResponse> HttpResponse doAction(AcsRequest<T> request, boolean autoRetry, int maxRetryCounts, IClientProfile profile) {
        throw new UnsupportedOperationException("Not supported in mock");
    }

    @Override
    public <T extends AcsResponse> T getAcsResponse(AcsRequest<T> request) {
        if (!(request instanceof DescribeInstancesRequest)) {
            throw new UnsupportedOperationException("Not supported in mock");
        }

        logger.debug("--> mocking getAcsResponse for DescribeInstancesRequest");

        final DescribeInstancesRequest describeInstancesRequest = ((DescribeInstancesRequest) request);
        final T response;
        try {
            response = request.getResponseClass().getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
            | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            return null;
        }

        final DescribeInstancesResponse describeInstancesResponse = (DescribeInstancesResponse) response;
        if (describeInstancesRequest.getPageNumber() != null && describeInstancesRequest.getPageNumber() > 1) {
            describeInstancesResponse.setInstances(Collections.emptyList());
            return response;
        }

        boolean tagFiltered = false;
        final Map<String, Set<String>> expectedTags = new HashMap<>();
        for (DescribeInstancesRequest.Tag tag : describeInstancesRequest.getTags()) {
            tagFiltered = true;

            Set<String> tagValues = expectedTags.computeIfAbsent(tag.getKey(), k -> new HashSet<>());
            if (tag.getValue() != null) {
                tagValues.add(tag.getValue());
            }
        }

        final List<DescribeInstancesResponse.Instance> filteredInstances = new ArrayList<>();
        for (DescribeInstancesResponse.Instance instance : instances) {
            boolean instanceFound = false;

            final Map<String, String> instanceTags = new HashMap<>();
            for (DescribeInstancesResponse.Instance.Tag tag : instance.getTags()) {
                final String oldVal = instanceTags.put(tag.getTagKey(), tag.getTagValue());
                assert oldVal == null;  // instance tag should have only one value for the same key
            }

            if (tagFiltered) {
                logger.debug("--> expected tags: [{}]", expectedTags);
                logger.debug("--> instance tags: [{}]", instanceTags);

                instanceFound = true;
                for (Map.Entry<String, Set<String>> expectedTagsEntry : expectedTags.entrySet()) {
                    final String instanceTagValue = instanceTags.get(expectedTagsEntry.getKey());
                    if (instanceTagValue == null) {
                        instanceFound = false;
                        break;
                    }

                    if (!expectedTagsEntry.getValue().isEmpty()) {
                        instanceFound = expectedTagsEntry.getValue().contains(instanceTagValue);
                    }
                }
            }

            if (!tagFiltered || instanceFound) {
                logger.debug("--> instance added");
                filteredInstances.add(instance);
            } else {
                logger.debug("--> instance filtered");
            }
        }

        ((DescribeInstancesResponse) response).setInstances(filteredInstances);
        return response;
    }

    @Override
    public <T extends AcsResponse> T getAcsResponse(AcsRequest<T> request, boolean autoRetry, int maxRetryCounts) {
        return getAcsResponse(request);
    }

    @Override
    public <T extends AcsResponse> T getAcsResponse(AcsRequest<T> request, IClientProfile profile) {
        return getAcsResponse(request);
    }

    @Override
    @SuppressWarnings("deprecation")
    public <T extends AcsResponse> T getAcsResponse(AcsRequest<T> request, String regionId, Credential credential) {
        return getAcsResponse(request);
    }

    @Override
    public <T extends AcsResponse> T getAcsResponse(AcsRequest<T> request, String regionId) {
        return getAcsResponse(request);
    }

    @Override
    public CommonResponse getCommonResponse(CommonRequest request) {
        throw new UnsupportedOperationException("Not supported in mock");
    }

    @Override
    public void restoreSSLCertificate() {
    }

    @Override
    public void ignoreSSLCertificate() {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public SignatureVersion getSignatureVersion() {
        return null;
    }

    @Override
    public void setSignatureVersion(SignatureVersion signatureVersion) {

    }

    @Override
    public SignatureAlgorithm getSignatureAlgorithm() {
        return null;
    }

    @Override
    public void setSignatureAlgorithm(SignatureAlgorithm signatureAlgorithm) {

    }

    static class InstanceTag extends DescribeInstancesResponse.Instance.Tag {
        InstanceTag(String key, String value) {
            super();
            this.setTagKey(key);
            this.setTagValue(value);
        }
    }

    static class NodeOption {

        private final String zoneId;
        private final List<DescribeInstancesResponse.Instance.Tag> tags;
        private final List<String> securityGroups;

        static class Builder {
            private String zoneId;
            private List<DescribeInstancesResponse.Instance.Tag> tags;
            private List<String> securityGroups;

            Builder setZondId(String zoneId) {
                this.zoneId = zoneId;
                return this;
            }


            Builder setTags(List<DescribeInstancesResponse.Instance.Tag> tags) {
                this.tags = tags;
                return this;
            }

            Builder setSecurityGroups(List<String> securityGroups) {
                this.securityGroups = securityGroups;
                return this;
            }

            NodeOption build() {
                return new NodeOption(this);
            }
        }

        private NodeOption(Builder builder) {
            this.zoneId = builder.zoneId;
            this.tags = (builder.tags != null) ? builder.tags : Collections.emptyList();
            this.securityGroups = (builder.securityGroups != null) ? builder.securityGroups : Collections.emptyList();
        }

        static Builder builder() {
            return new Builder();
        }

    }

}
