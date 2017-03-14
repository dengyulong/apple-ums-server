
package com.appleframework.ums.server.storage.hdfs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.FlumeException;
import org.apache.flume.PollableSource;
import org.apache.flume.conf.Configurable;
import org.apache.flume.conf.ConfigurationException;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.instrumentation.kafka.KafkaSourceCounter;
import org.apache.flume.source.AbstractSource;
import org.apache.flume.source.kafka.KafkaSourceConstants;
import org.apache.flume.source.kafka.KafkaSourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.appleframework.config.core.util.StringUtils;
import com.appleframework.flume.ng.configuration.utils.ContextUtil;
import com.appleframework.ums.server.core.model.ClientData;
import com.appleframework.ums.server.core.model.ClientDataJson;
import com.appleframework.ums.server.core.model.ErrorLog;
import com.appleframework.ums.server.core.model.ErrorLogJson;
import com.appleframework.ums.server.core.model.EventLog;
import com.appleframework.ums.server.core.model.EventLogJson;
import com.appleframework.ums.server.core.model.UsingLog;
import com.appleframework.ums.server.core.model.UsingLogJson;
import com.appleframework.util.ip.IP;

import kafka.consumer.ConsumerIterator;
import kafka.consumer.ConsumerTimeoutException;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.MessageAndMetadata;

/**
 * A Source for Kafka which reads messages from a kafka topic.
 *
 * <tt>zookeeperConnect: </tt> Kafka's zookeeper connection string.
 * <b>Required</b>
 * <p>
 * <tt>groupId: </tt> the group ID of consumer group. <b>Required</b>
 * <p>
 * <tt>topic: </tt> the topic to consume messages from. <b>Required</b>
 * <p>
 * <tt>maxBatchSize: </tt> Maximum number of messages written to Channel in one
 * batch. Default: 1000
 * <p>
 * <tt>maxBatchDurationMillis: </tt> Maximum number of milliseconds before a
 * batch (of any size) will be written to a channel. Default: 1000
 * <p>
 * <tt>kafka.auto.commit.enable: </tt> If true, commit automatically every time
 * period. if false, commit on each batch. Default: false
 * <p>
 * <tt>kafka.consumer.timeout.ms: </tt> Polling interval for new data for batch.
 * Low value means more CPU usage. High value means the time.upper.limit may be
 * missed. Default: 10
 *
 * Any property starting with "kafka" will be passed to the kafka consumer So
 * you can use any configuration supported by Kafka 0.8.1.1
 */
public class KafkaSource extends AbstractSource implements Configurable, PollableSource {

	private static final Logger log = LoggerFactory.getLogger(KafkaSource.class);

	private ConsumerConnector consumer;
	private ConsumerIterator<byte[], byte[]> it;
	private String topic;
	private int batchUpperLimit;
	private int timeUpperLimit;
	//private int consumerTimeout;
	private boolean kafkaAutoCommitEnabled;
	//private Context context;
	private Properties kafkaProps;
	private final List<Event> eventList = new ArrayList<Event>();
	private KafkaSourceCounter counter;

	public Status process() throws EventDeliveryException {

		byte[] kafkaMessage;
		byte[] kafkaKey;
		Event event;
		Map<String, String> headers;
		long batchStartTime = System.currentTimeMillis();
		long batchEndTime = System.currentTimeMillis() + timeUpperLimit;
		try {
			boolean iterStatus = false;
			long startTime = System.nanoTime();
			while (eventList.size() < batchUpperLimit && System.currentTimeMillis() < batchEndTime) {
				iterStatus = hasNext();
				if (iterStatus) {
					// get next message
					MessageAndMetadata<byte[], byte[]> messageAndMetadata = it.next();
					kafkaMessage = messageAndMetadata.message();
					kafkaKey = messageAndMetadata.key();

					String topic = messageAndMetadata.topic();

					headers = new HashMap<String, String>();
					headers.put(KafkaSourceConstants.TIMESTAMP, String.valueOf(System.currentTimeMillis()));
					headers.put(KafkaSourceConstants.TOPIC, topic);
					if (kafkaKey != null) {
						headers.put(KafkaSourceConstants.KEY, new String(kafkaKey));
					}
					if (log.isDebugEnabled()) {
						log.debug("Message: {}", new String(kafkaMessage));
					}

					String ip = new String(kafkaKey);
					String content = new String(kafkaMessage);

					if (topic.equals("topic_eventlog")) {
						EventLogJson json = JSON.parseObject(content, EventLogJson.class);
						List<EventLog> data = json.getData();
						for (EventLog log : data) {
							event = EventBuilder.withBody(log.toString().getBytes(), headers);
							eventList.add(event);
						}
					} else if (topic.equals("topic_errorlog")) {
						ErrorLogJson json = JSON.parseObject(content, ErrorLogJson.class);
						List<ErrorLog> data = json.getData();
						for (ErrorLog log : data) {
							event = EventBuilder.withBody(log.toString().getBytes(), headers);
							eventList.add(event);
						}
					} else if (topic.equals("topic_clientdata")) {
						ClientDataJson json = JSON.parseObject(content, ClientDataJson.class);
						List<ClientData> data = json.getData();
						for (ClientData log : data) {
							String country, region, city;
							try {
								String[] regions = IP.find(ip);
								country = StringUtils.isEmpty(regions[0]) ? "unknown" : regions[0];
								region = StringUtils.isEmpty(regions[1]) ? "unknown" : regions[1];
								city = StringUtils.isEmpty(regions[2]) ? "unknown" : regions[2];
							} catch (Exception e) {
								country = "unknown";
								region = "unknown";
								city = "unknown";
							}
							event = EventBuilder.withBody(log.toString(ip, country, region, city).getBytes(), headers);
							eventList.add(event);
						}
					} else if (topic.equals("topic_usinglog")) {
						UsingLogJson json = JSON.parseObject(content, UsingLogJson.class);
						List<UsingLog> data = json.getData();
						for (UsingLog log : data) {
							event = EventBuilder.withBody(log.toString().getBytes(), headers);
							eventList.add(event);
						}
					}

				}
				if (log.isDebugEnabled()) {
					log.debug("Waited: {} ", System.currentTimeMillis() - batchStartTime);
					log.debug("Event #: {}", eventList.size());
				}
			}
			long endTime = System.nanoTime();
			counter.addToKafkaEventGetTimer((endTime - startTime) / (1000 * 1000));
			counter.addToEventReceivedCount(Long.valueOf(eventList.size()));
			// If we have events, send events to channel
			// clear the event list
			// and commit if Kafka doesn't auto-commit
			if (eventList.size() > 0) {
				getChannelProcessor().processEventBatch(eventList);
				counter.addToEventAcceptedCount(eventList.size());
				eventList.clear();
				if (log.isDebugEnabled()) {
					log.debug("Wrote {} events to channel", eventList.size());
				}
				if (!kafkaAutoCommitEnabled) {
					// commit the read transactions to Kafka to avoid duplicates
					long commitStartTime = System.nanoTime();
					consumer.commitOffsets();
					long commitEndTime = System.nanoTime();
					counter.addToKafkaCommitTimer((commitEndTime - commitStartTime) / (1000 * 1000));
				}
			}
			if (!iterStatus) {
				if (log.isDebugEnabled()) {
					counter.incrementKafkaEmptyCount();
					log.debug("Returning with backoff. No more data to read");
				}
				return Status.BACKOFF;
			}
			return Status.READY;
		} catch (Exception e) {
			log.error("KafkaSource EXCEPTION, {}", e);
			return Status.BACKOFF;
		}
	}

	/**
	 * We configure the source and generate properties for the Kafka Consumer
	 *
	 * Kafka Consumer properties are generated as follows:
	 *
	 * 1. Generate a properties object with some static defaults that can be
	 * overridden by Source configuration 2. We add the configuration users
	 * added for Kafka (parameters starting with kafka. and must be valid Kafka
	 * Consumer properties 3. We add the source documented parameters which can
	 * override other properties
	 *
	 * @param context
	 */
	public void configure(Context context) {
		//this.context = context;
		ContextUtil.fullContextValue(context);
		batchUpperLimit = context.getInteger(KafkaSourceConstants.BATCH_SIZE, KafkaSourceConstants.DEFAULT_BATCH_SIZE);
		timeUpperLimit = context.getInteger(KafkaSourceConstants.BATCH_DURATION_MS, KafkaSourceConstants.DEFAULT_BATCH_DURATION);
		topic = context.getString(KafkaSourceConstants.TOPIC);

		if (topic == null) {
			throw new ConfigurationException("Kafka topic must be specified.");
		}

		kafkaProps = KafkaSourceUtil.getKafkaProperties(context);
		//consumerTimeout = Integer.parseInt(kafkaProps.getProperty(KafkaSourceConstants.CONSUMER_TIMEOUT));
		kafkaAutoCommitEnabled = Boolean.parseBoolean(kafkaProps.getProperty(KafkaSourceConstants.AUTO_COMMIT_ENABLED));

		if (counter == null) {
			counter = new KafkaSourceCounter(getName());
		}
	}

	@Override
	public synchronized void start() {
		log.info("Starting {}...", this);

		try {
			// initialize a consumer. This creates the connection to ZooKeeper
			consumer = KafkaSourceUtil.getConsumer(kafkaProps);
		} catch (Exception e) {
			throw new FlumeException("Unable to create consumer. " + "Check whether the ZooKeeper server is up and that the " + "Flume agent can connect to it.", e);
		}

		Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
		// We always have just one topic being read by one thread
		topicCountMap.put(topic, 1);

		// Get the message iterator for our topic
		// Note that this succeeds even if the topic doesn't exist
		// in that case we simply get no messages for the topic
		// Also note that currently we only support a single topic
		try {
			Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = consumer.createMessageStreams(topicCountMap);
			List<KafkaStream<byte[], byte[]>> topicList = consumerMap.get(topic);
			KafkaStream<byte[], byte[]> stream = topicList.get(0);
			it = stream.iterator();
		} catch (Exception e) {
			throw new FlumeException("Unable to get message iterator from Kafka", e);
		}
		log.info("Kafka source {} started.", getName());
		counter.start();
		super.start();
	}

	@Override
	public synchronized void stop() {
		if (consumer != null) {
			// exit cleanly. This syncs offsets of messages read to ZooKeeper
			// to avoid reading the same messages again
			consumer.shutdown();
		}
		counter.stop();
		log.info("Kafka Source {} stopped. Metrics: {}", getName(), counter);
		super.stop();
	}

	/**
	 * Check if there are messages waiting in Kafka, waiting until timeout (10ms
	 * by default) for messages to arrive. and catching the timeout exception to
	 * return a boolean
	 */
	boolean hasNext() {
		try {
			it.hasNext();
			return true;
		} catch (ConsumerTimeoutException e) {
			return false;
		}
	}

}