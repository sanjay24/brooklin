/**
 *  Copyright 2019 LinkedIn Corporation. All rights reserved.
 *  Licensed under the BSD 2-Clause License. See the LICENSE file in the project root for license information.
 *  See the NOTICE file in the project root for additional information regarding copyright ownership.
 */
package com.linkedin.datastream.connectors.kafka;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.IntStream;

import org.apache.commons.lang.StringUtils;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.PartitionInfo;
import org.codehaus.jackson.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.common.DatastreamMetadataConstants;
import com.linkedin.datastream.common.DatastreamSource;
import com.linkedin.datastream.common.JsonUtils;
import com.linkedin.datastream.common.VerifiableProperties;
import com.linkedin.datastream.metrics.BrooklinMetricInfo;
import com.linkedin.datastream.server.DatastreamTask;
import com.linkedin.datastream.server.api.connector.DatastreamValidationException;


/**
 * Connector implementation that consumes from one Kafka source. Extends AbstractKafkaConnector which handles the
 * bulk of the logic for Kafka connectors.
 */
public class KafkaConnector extends AbstractKafkaConnector {

  public static final String CONFIG_WHITE_LISTED_CLUSTERS = "whiteListedClusters";
  private static final Logger LOG = LoggerFactory.getLogger(KafkaConnector.class);

  private final Set<KafkaBrokerAddress> _whiteListedBrokers;

  /**
   * Constructor for KafkaConnector.
   * This takes care of constructing the {@link KafkaGroupIdConstructor} for constructing the consumer group ID.
   * It also creates a whitelist of Kafka brokers from the provided config properties.
   */
  public KafkaConnector(String connectorName, Properties config, String clusterName) {
    super(connectorName, config, new KafkaGroupIdConstructor(
            Boolean.parseBoolean(config.getProperty(IS_GROUP_ID_HASHING_ENABLED, Boolean.FALSE.toString())), clusterName),
        clusterName, LOG);

    VerifiableProperties verifiableProperties = new VerifiableProperties(config);
    List<KafkaBrokerAddress> brokers =
        Optional.ofNullable(verifiableProperties.getString(CONFIG_WHITE_LISTED_CLUSTERS, null))
            .map(KafkaConnectionString::parseBrokers)
            .orElse(Collections.emptyList());
    _whiteListedBrokers = new HashSet<>(brokers);
  }

  @Override
  public void initializeDatastream(Datastream stream, List<Datastream> allDatastreams)
      throws DatastreamValidationException {
    LOG.info("Initialize datastream {}", stream);
    DatastreamSource source = stream.getSource();
    String connectionString = source.getConnectionString();

    try {
      KafkaConnectionString parsed = KafkaConnectionString.valueOf(connectionString);
      source.setConnectionString(parsed.toString()); // ordered now

      if (stream.hasDestination()) {
        // set default key and value serde
        if (!stream.getDestination().hasKeySerDe() && !StringUtils.isBlank(_config.getDefaultKeySerde())) {
          stream.getDestination().setKeySerDe(_config.getDefaultKeySerde());
        }
        if (!stream.getDestination().hasPayloadSerDe() && !StringUtils.isBlank(_config.getDefaultValueSerde())) {
          stream.getDestination().setPayloadSerDe(_config.getDefaultValueSerde());
        }
      }

      if (hasWildCards(parsed)) {
        String msg =
                String.format("Wildcard characters are Not Allowed %s.", connectionString);
        LOG.error(msg);
        throw new DatastreamValidationException(msg);
      }

      if (!isWhiteListedCluster(parsed)) {
        String msg =
            String.format("Kafka connector is not white-listed for the cluster %s. Current white-listed clusters %s.",
                connectionString, _whiteListedBrokers);
        LOG.error(msg);
        throw new DatastreamValidationException(msg);
      }
      try (Consumer<?, ?> consumer = KafkaConnectorTask.createConsumer(_config.getConsumerFactory(),
          _config.getConsumerProps(), "partitionFinder", parsed)) {
        List<PartitionInfo> partitionInfos = consumer.partitionsFor(parsed.getTopicName());
        if (partitionInfos == null) {
          throw new DatastreamValidationException(
              "Can't get partition info from kafka. Very likely that auto topic creation "
                  + "is disabled on the broker and the topic doesn't exist: " + parsed.getTopicName());
        }
        int numPartitions = partitionInfos.size();
        if (!source.hasPartitions()) {
          LOG.info("Kafka source {} has {} partitions.", parsed, numPartitions);
          source.setPartitions(numPartitions);
        } else {
          if (source.getPartitions() != numPartitions) {
            String msg =
                String.format("Source is configured with %d partitions, But the topic %s actually has %d partitions",
                    source.getPartitions(), parsed.getTopicName(), numPartitions);
            LOG.error(msg);
            throw new DatastreamValidationException(msg);
          }
        }

        // Try to see if the start positions requested are valid.
        // Value is a json string encoding partition to offset, like {"0":23,"1":15,"2":88}
        if (stream.getMetadata().containsKey(DatastreamMetadataConstants.START_POSITION)) {
          String json = stream.getMetadata().get(DatastreamMetadataConstants.START_POSITION);
          Map<Integer, Long> offsetMap = JsonUtils.fromJson(json, new TypeReference<Map<Integer, Long>>() {
          });
          if (offsetMap.size() != numPartitions || IntStream.range(0, numPartitions)
              .anyMatch(x -> !offsetMap.containsKey(x))) {
            String msg =
                String.format("Missing partitions starting offset for datastream %s, json value %s", stream, json);
            LOG.warn(msg);
            throw new DatastreamValidationException(msg);
          }
        }
      }
    } catch (DatastreamValidationException e) {
      throw e;
    } catch (Exception e) {
      LOG.error("Initialization threw an exception.", e);
      throw new DatastreamValidationException(e);
    }
  }

  @Override
  public List<BrooklinMetricInfo> getMetricInfos() {
    return Collections.unmodifiableList(KafkaConnectorTask.getMetricInfos(_connectorName));
  }

  private Boolean isWhiteListedCluster(KafkaConnectionString connectionStr) {
    return _whiteListedBrokers.isEmpty() || connectionStr.getBrokers().stream().anyMatch(_whiteListedBrokers::contains);
  }

  private Boolean hasWildCards(KafkaConnectionString connectionStr) {
    String topicName = connectionStr.getTopicName();

    if (topicName == null || topicName.equals("")) {
      return false;
    }
    boolean hasWildChar = false;

    String specialChars = "*?.";
    for (int specialIndex = 0; specialIndex < specialChars.length(); specialIndex++) {
      // only allow escaped special chars
      char prev = topicName.charAt(0);
      for (int stringIndex = 1; stringIndex < topicName.length(); stringIndex++) {
        if (topicName.charAt(stringIndex) == specialChars.charAt(specialIndex) && prev != '\\') {
          hasWildChar = true;
          break;
        }
        prev = topicName.charAt(stringIndex);
      }
    }
    return hasWildChar;
  }

  @Override
  protected AbstractKafkaBasedConnectorTask createKafkaBasedConnectorTask(DatastreamTask task) {
    return new KafkaConnectorTask(_config, task, _connectorName, _groupIdConstructor);
  }

  @Override
  public void postDatastreamInitialize(Datastream datastream, List<Datastream> allDatastreams)
      throws DatastreamValidationException {
    _groupIdConstructor.populateDatastreamGroupIdInMetadata(datastream, allDatastreams, Optional.of(LOG));
  }
}
