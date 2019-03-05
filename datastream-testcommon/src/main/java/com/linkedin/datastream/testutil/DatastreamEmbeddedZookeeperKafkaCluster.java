/**
 *  Copyright 2019 LinkedIn Corporation. All rights reserved.
 *  Licensed under the BSD 2-Clause License. See the LICENSE file in the project root for license information.
 *  See the NOTICE file in the project root for additional information regarding copyright ownership.
 */
package com.linkedin.datastream.testutil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.linkedin.datastream.kafka.EmbeddedKafkaCluster;
import com.linkedin.datastream.kafka.KafkaCluster;


public class DatastreamEmbeddedZookeeperKafkaCluster implements KafkaCluster {
  private EmbeddedZookeeper _embeddedZookeeper = null;
  private EmbeddedKafkaCluster _embeddedKafkaCluster = null;
  private boolean _isStarted;

  /**
   * Create a DatastreamEmbeddedZookeeperKafkaCluster with specific base kafka config
   * @param kafkaBaseConfig base config of kafka brokers
   */
  public DatastreamEmbeddedZookeeperKafkaCluster(Properties kafkaBaseConfig) throws IOException {
    _embeddedZookeeper = new EmbeddedZookeeper();
    List<Integer> kafkaPorts = new ArrayList<>();
    // -1 for any available port
    kafkaPorts.add(-1);
    kafkaPorts.add(-1);
    _embeddedKafkaCluster = new EmbeddedKafkaCluster(_embeddedZookeeper.getConnection(), kafkaBaseConfig, kafkaPorts);
    _isStarted = false;
  }

  /**
   * Create a DatastreamEmbeddedZookeeperKafkaCluster with default base kafka config
   */
  public DatastreamEmbeddedZookeeperKafkaCluster() throws IOException {
    this(new Properties());
  }

  @Override
  public String getBrokers() {
    return _embeddedKafkaCluster.getBrokers();
  }

  @Override
  public String getZkConnection() {
    return _embeddedKafkaCluster.getZkConnection();
  }

  @Override
  public boolean isStarted() {
    return _isStarted;
  }

  @Override
  public void startup() {
    try {
      _embeddedZookeeper.startup();
    } catch (IOException e) {
      throw new RuntimeException("Starting zookeeper failed with exception", e);
    }
    _embeddedKafkaCluster.startup();
    _isStarted = true;
  }

  @Override
  public void shutdown() {
    if (_embeddedKafkaCluster != null) {
      _embeddedKafkaCluster.shutdown();
    }
    if (_embeddedZookeeper != null) {
      _embeddedZookeeper.shutdown();
    }
    _isStarted = false;
  }
}