/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone.om;

import org.apache.hadoop.hdds.client.DefaultReplicationConfig;
import org.apache.hadoop.hdds.client.ECReplicationConfig;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the server side replication config preference logic.
 */
public class TestOzoneConfigUtil {
  private ReplicationConfig ratis3ReplicationConfig =
      RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.THREE);
  private HddsProtos.ReplicationType noneType = HddsProtos.ReplicationType.NONE;
  private HddsProtos.ReplicationFactor zeroFactor =
      HddsProtos.ReplicationFactor.ZERO;
  private HddsProtos.ECReplicationConfig clientECReplicationConfig =
      new ECReplicationConfig("rs-3-2-1024K").toProto();
  private DefaultReplicationConfig bucketECConfig =
      new DefaultReplicationConfig(
          new ECReplicationConfig(clientECReplicationConfig));

  private OzoneManager ozoneManager;

  @BeforeEach
  public void setup() {
    ozoneManager = mock(OzoneManager.class);
    when(ozoneManager.getDefaultReplicationConfig())
        .thenReturn(ratis3ReplicationConfig);
  }

  /**
   * Tests EC bucket defaults.
   */
  @Test
  public void testResolveClientSideRepConfigWhenBucketHasEC() throws Exception {
    ReplicationConfig replicationConfig = OzoneConfigUtil
        .resolveReplicationConfigPreference(noneType, zeroFactor,
            clientECReplicationConfig, bucketECConfig, ozoneManager);
    // Client has no preference, so we should bucket defaults as we passed.
    Assertions.assertEquals(bucketECConfig.getReplicationConfig(),
        replicationConfig);
  }

  /**
   * Tests server defaults.
   */
  @Test
  public void testResolveClientSideRepConfigWithNoClientAndBucketDefaults()
      throws Exception {
    ReplicationConfig replicationConfig = OzoneConfigUtil
        .resolveReplicationConfigPreference(noneType, zeroFactor,
            clientECReplicationConfig, null, ozoneManager);
    // Client has no preference, no bucket defaults, so it should return server
    // defaults.
    Assertions.assertEquals(ratis3ReplicationConfig, replicationConfig);
  }

  /**
   * Tests client preference of EC.
   */
  @Test
  public void testResolveClientSideRepConfigWhenClientPassEC()
      throws Exception {
    ReplicationConfig replicationConfig = OzoneConfigUtil
        .resolveReplicationConfigPreference(HddsProtos.ReplicationType.EC,
            zeroFactor, clientECReplicationConfig, null,
            ozoneManager);
    // Client has preference of type EC, no bucket defaults, so it should return
    // client preference.
    Assertions.assertEquals(new ECReplicationConfig("rs-3-2-1024K"),
        replicationConfig);
  }

  /**
   * Tests bucket ratis defaults.
   */
  @Test
  public void testResolveClientSideRepConfigWhenBucketHasEC3()
      throws Exception {
    ReplicationConfig ratisReplicationConfig =
        RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.THREE);
    DefaultReplicationConfig ratisBucketDefaults =
        new DefaultReplicationConfig(ratisReplicationConfig);
    ReplicationConfig replicationConfig = OzoneConfigUtil
        .resolveReplicationConfigPreference(noneType, zeroFactor,
            clientECReplicationConfig, ratisBucketDefaults,
            ozoneManager);
    // Client has no preference of type and bucket has ratis defaults, so it
    // should return ratis.
    Assertions.assertEquals(ratisReplicationConfig, replicationConfig);
  }

  @Test
  public void testS3AdminExtraction() throws IOException {
    OzoneConfiguration configuration = new OzoneConfiguration();
    configuration.set(OzoneConfigKeys.OZONE_S3_ADMINISTRATORS, "alice,bob");

    Assertions.assertTrue(OzoneConfigUtil.getS3AdminsFromConfig(configuration)
        .containsAll(Arrays.asList("alice", "bob")));
  }

  @Test
  public void testS3AdminExtractionWithFallback() throws IOException {
    OzoneConfiguration configuration = new OzoneConfiguration();
    configuration.set(OzoneConfigKeys.OZONE_ADMINISTRATORS, "alice,bob");

    Assertions.assertTrue(OzoneConfigUtil.getS3AdminsFromConfig(configuration)
        .containsAll(Arrays.asList("alice", "bob")));
  }

  @Test
  public void testS3AdminGroupExtraction() {
    OzoneConfiguration configuration = new OzoneConfiguration();
    configuration.set(OzoneConfigKeys.OZONE_S3_ADMINISTRATORS_GROUPS,
        "test1, test2");

    Assertions.assertTrue(OzoneConfigUtil.getS3AdminsGroupsFromConfig(
        configuration).containsAll(Arrays.asList("test1", "test2")));
  }

  @Test
  public void testS3AdminGroupExtractionWithFallback() {
    OzoneConfiguration configuration = new OzoneConfiguration();
    configuration.set(OzoneConfigKeys.OZONE_ADMINISTRATORS_GROUPS,
        "test1, test2");

    Assertions.assertTrue(OzoneConfigUtil.getS3AdminsGroupsFromConfig(
        configuration).containsAll(Arrays.asList("test1", "test2")));
  }
}
