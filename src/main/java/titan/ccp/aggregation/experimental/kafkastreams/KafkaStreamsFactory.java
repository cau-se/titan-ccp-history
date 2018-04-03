package titan.ccp.aggregation.experimental.kafkastreams;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.kafka.common.serialization.ByteBufferDeserializer;
import org.apache.kafka.common.serialization.ByteBufferSerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.Consumed;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueStore;

import titan.ccp.model.PowerConsumptionRecord;
import titan.ccp.model.sensorregistry.MachineSensor;
import titan.ccp.model.sensorregistry.SensorRegistry;

public class KafkaStreamsFactory {

	public KafkaStreams create() {
		final StreamsBuilder builder = new StreamsBuilder(); // when using the DSL

		final KStream<String, PowerConsumptionRecord> wordCounts = builder.stream("partition-topic", /* input topic */
				Consumed.with(Serdes.String(), createPowerConsumptionSerde()));

		final KStream<String, PowerConsumptionRecord> flatMapped = wordCounts
				.flatMap((key, value) -> this.flatMap(value));

		final KGroupedStream<String, PowerConsumptionRecord> groupedStream = flatMapped.groupByKey(); // TODO set serdes

		final KTable<String, AggregatedSensorHistory> aggregated = groupedStream.aggregate(() -> {
			return new AggregatedSensorHistory();
		}, (aggKey, newValue, aggValue2) -> {
			// System.out.println("Agg: " + aggKey + ":" + newValue);
			// AggregatedSensorHistory aggValue2;
			aggValue2.update(aggKey, newValue.getPowerConsumptionInWh());
			return aggValue2;
			// return (long) newValue.getPowerConsumptionInWh();
		}, /* adder */
				Materialized
						.<String, AggregatedSensorHistory, KeyValueStore<Bytes, byte[]>>as(
								"aggregated-stream-store-for-partition") // state
						// store
						// name
						.withKeySerde(Serdes.String()) /* key serde */
						.withValueSerde(createAggregatedSensorHistorySerde())); /* serde for aggregate value */

		// aggregated.toStream().to("", Produced.with(null, null));

		final Topology topology = builder.build();

		// Use the configuration to tell your application where the Kafka cluster is,
		// which Serializers/Deserializers to use by default, to specify security
		// settings,
		// and so on.
		final Properties settings = new Properties();
		// Set a few key parameters
		settings.put(StreamsConfig.APPLICATION_ID_CONFIG, "my-first-streams-application-0.0.2");
		settings.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
		settings.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
		settings.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
		// Any further settings
		// settings.put(... , ...);
		final StreamsConfig config = new StreamsConfig(settings);

		return new KafkaStreams(topology, config);
	}

	private Iterable<KeyValue<String, PowerConsumptionRecord>> flatMap(final PowerConsumptionRecord record) {
		final SensorRegistry sensorRegistry = null; // TODO
		final Optional<MachineSensor> sensor = sensorRegistry.getSensorForIdentifier(record.getIdentifier().toString()); // TODO
																															// temp
		return sensor.stream().flatMap(s -> s.getParents().stream()).map(s -> s.getIdentifier())
				.map(i -> KeyValue.pair(i, record)).collect(Collectors.toList());
	}

	private static final Serde<PowerConsumptionRecord> createPowerConsumptionSerde() {
		return Serdes.serdeFrom(new PowerConsumptionRecordSerializer(), new PowerConsumptionRecordDeserializer());
	}

	// PowerConsumption Serdes

	private static class PowerConsumptionRecordDeserializer implements Deserializer<PowerConsumptionRecord> {

		private final ByteBufferDeserializer byteBufferDeserializer = new ByteBufferDeserializer();
		private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

		@Override
		public void configure(final Map<String, ?> configs, final boolean isKey) {
			this.byteBufferDeserializer.configure(configs, isKey);
		}

		@Override
		public PowerConsumptionRecord deserialize(final String topic, final byte[] data) {
			final ByteBuffer buffer = this.byteBufferDeserializer.deserialize(topic, data);

			final int stringLength = buffer.getInt();
			final byte[] stringBytes = new byte[stringLength];
			buffer.get(stringBytes);
			final String identifier = new String(stringBytes, DEFAULT_CHARSET);
			final long timestamp = buffer.getLong();
			final int powerConsumption = buffer.getInt();

			return new PowerConsumptionRecord(identifier.getBytes(), timestamp, powerConsumption); // TODO temp
																									// converserion to
																									// bytes
		}

		@Override
		public void close() {
			this.byteBufferDeserializer.close();
		}

	}

	private static class PowerConsumptionRecordSerializer implements Serializer<PowerConsumptionRecord> {

		private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");
		private static final int BYTE_BUFFER_CAPACITY = 65536; // Is only virtual memory

		private final ByteBufferSerializer byteBufferSerializer = new ByteBufferSerializer();

		@Override
		public void configure(final Map<String, ?> configs, final boolean isKey) {
			this.byteBufferSerializer.configure(configs, isKey);
		}

		@Override
		public byte[] serialize(final String topic, final PowerConsumptionRecord record) {
			final String identifier = record.getIdentifier().toString(); // TODO Identifier will be String

			final ByteBuffer buffer = ByteBuffer.allocateDirect(BYTE_BUFFER_CAPACITY);
			final byte[] stringBytes = identifier.getBytes(DEFAULT_CHARSET);
			buffer.putInt(stringBytes.length);
			buffer.put(stringBytes);
			buffer.putLong(record.getTimestamp());
			buffer.putInt(record.getPowerConsumptionInWh());

			return this.byteBufferSerializer.serialize(topic, buffer);
		}

		@Override
		public void close() {
			this.byteBufferSerializer.close();
		}

	}

	public static class AggregatedSensorHistory { // TODO

		private final Map<String, Long> lastValues;

		public AggregatedSensorHistory() {
			this.lastValues = new HashMap<>();
		}

		// TODO except read only copy of key value pairs
		public AggregatedSensorHistory(final Map<String, Long> lastValues) {
			this.lastValues = lastValues;
		}

		public void update(final String identifier, final long newValue) {
			this.lastValues.put(identifier, newValue);
		}

		// TODO return read only copy of key value pairs
		public Map<String, Long> getLastValues() {
			return this.lastValues;
		}

		public LongSummaryStatistics getSummaryStatistics() {
			return this.lastValues.values().stream().mapToLong(v -> v).summaryStatistics();
		}

	}

	// AggregatedSensorHistorySerdes

	private static final Serde<AggregatedSensorHistory> createAggregatedSensorHistorySerde() {
		return Serdes.serdeFrom(new AggregatedSensorHistorySerializer(), new AggregatedSensorHistoryDeserializer());
	}

	private static class AggregatedSensorHistorySerializer implements Serializer<AggregatedSensorHistory> {

		private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");
		private static final int BYTE_BUFFER_CAPACITY = 65536; // Is only virtual memory

		private final ByteBufferSerializer byteBufferSerializer = new ByteBufferSerializer();

		@Override
		public void configure(final Map<String, ?> configs, final boolean isKey) {
			this.byteBufferSerializer.configure(configs, isKey);
		}

		@Override
		public byte[] serialize(final String topic, final AggregatedSensorHistory data) {
			final ByteBuffer buffer = ByteBuffer.allocateDirect(BYTE_BUFFER_CAPACITY);

			buffer.putInt(data.getLastValues().size());
			for (final Entry<String, Long> entry : data.getLastValues().entrySet()) {
				final byte[] key = entry.getKey().getBytes(DEFAULT_CHARSET);
				buffer.putInt(key.length);
				buffer.put(key);
				buffer.putLong(entry.getValue());
			}

			return this.byteBufferSerializer.serialize(topic, buffer);
		}

		@Override
		public void close() {
			this.byteBufferSerializer.close();
		}

	}

	private static class AggregatedSensorHistoryDeserializer implements Deserializer<AggregatedSensorHistory> {

		private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

		private final ByteBufferDeserializer byteBufferDeserializer = new ByteBufferDeserializer();

		@Override
		public void configure(final Map<String, ?> configs, final boolean isKey) {
			this.byteBufferDeserializer.configure(configs, isKey);
		}

		@Override
		public AggregatedSensorHistory deserialize(final String topic, final byte[] data) {
			final ByteBuffer buffer = this.byteBufferDeserializer.deserialize(topic, data);

			final Map<String, Long> map = new HashMap<>();

			final int size = buffer.getInt();
			for (int i = 0; i < size; i++) {
				final int keyLength = buffer.getInt();
				final byte[] keyBytes = new byte[keyLength];
				buffer.get(keyBytes);
				final String key = new String(keyBytes, DEFAULT_CHARSET);
				final long value = buffer.getLong();

				map.put(key, value);
			}

			return new AggregatedSensorHistory(map);
		}

		@Override
		public void close() {
			this.byteBufferDeserializer.close();
		}

	}

}