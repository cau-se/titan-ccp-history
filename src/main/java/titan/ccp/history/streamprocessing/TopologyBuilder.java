package titan.ccp.history.streamprocessing;

import com.datastax.driver.core.Session;
import kieker.common.record.IMonitoringRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import titan.ccp.common.cassandra.CassandraWriter;
import titan.ccp.common.cassandra.ExplicitPrimaryKeySelectionStrategy;
import titan.ccp.common.cassandra.PredefinedTableNameMappers;
import titan.ccp.common.kieker.cassandra.KiekerDataAdapter;
import titan.ccp.common.kieker.kafka.IMonitoringRecordSerde;
import titan.ccp.models.records.ActivePowerRecord;
import titan.ccp.models.records.ActivePowerRecordFactory;
import titan.ccp.models.records.AggregatedActivePowerRecord;
import titan.ccp.models.records.AggregatedActivePowerRecordFactory;

/**
 * Builds Kafka Stream Topology for the History microservice.
 *
 * <p>
 * The History microservice is going to be divided into a History and an Aggregation component
 * (later microservice). After the restructuring, this class constructs the topology for the
 * Aggregation whereas the Cassandra storing parts are going to be moved to a dedicated builder.
 * </p>
 */
public class TopologyBuilder {

  private static final Logger LOGGER = LoggerFactory.getLogger(TopologyBuilder.class);

  private final String inputTopic;
  private final String outputTopic;
  private final Session cassandraSession; // TODO Remove History parts

  private final StreamsBuilder builder = new StreamsBuilder();


  /**
   * Create a new {@link TopologyBuilder} using the given topics.
   */
  public TopologyBuilder(final String inputTopic, final String outputTopic,
      final Session cassandraSession) {
    this.inputTopic = inputTopic;
    this.outputTopic = outputTopic;
    this.cassandraSession = cassandraSession;
  }

  /**
   * Build the {@link Topology} for the History microservice.
   */
  public Topology build() {
    this.buildCassandraWriters();

    return this.builder.build();
  }

  private void buildCassandraWriters() {

    // Cassandra Writer for ActivePowerRecord
    final CassandraWriter<IMonitoringRecord> cassandraWriterForNormal =
        this.buildCassandraWriter(ActivePowerRecord.class);
    this.builder
        .stream(this.inputTopic,
            Consumed.with(Serdes.String(),
                IMonitoringRecordSerde.serde(new ActivePowerRecordFactory())))
        // TODO Logging
        .peek((k, record) -> LOGGER.info("Write ActivePowerRecord to Cassandra {}",
            this.buildActivePowerRecordString(record)))
        .foreach((key, record) -> cassandraWriterForNormal.write(record));

    // Cassandra Writer for AggregatedActivePowerRecord
    final CassandraWriter<IMonitoringRecord> cassandraWriter =
        this.buildCassandraWriter(AggregatedActivePowerRecord.class);
    this.builder
        .stream(this.outputTopic, Consumed.with(
            Serdes.String(),
            IMonitoringRecordSerde.serde(new AggregatedActivePowerRecordFactory())))
        .peek((k, record) -> LOGGER.info("Write AggregatedActivePowerRecord to Cassandra {}",
            this.buildAggActivePowerRecordString(record)))
        .foreach((key, record) -> cassandraWriter.write(record));
  }

  private CassandraWriter<IMonitoringRecord> buildCassandraWriter(
      final Class<? extends IMonitoringRecord> recordClass) {
    final ExplicitPrimaryKeySelectionStrategy primaryKeySelectionStrategy =
        new ExplicitPrimaryKeySelectionStrategy();
    primaryKeySelectionStrategy.registerPartitionKeys(recordClass.getSimpleName(), "identifier");
    primaryKeySelectionStrategy.registerClusteringColumns(recordClass.getSimpleName(), "timestamp");

    final CassandraWriter<IMonitoringRecord> cassandraWriter =
        CassandraWriter.builder(this.cassandraSession, new KiekerDataAdapter())
            .tableNameMapper(PredefinedTableNameMappers.SIMPLE_CLASS_NAME)
            .primaryKeySelectionStrategy(primaryKeySelectionStrategy).build();

    return cassandraWriter;
  }

  // TODO Temp
  private String buildActivePowerRecordString(final ActivePowerRecord record) {
    return "{" + record.getIdentifier() + ';' + record.getTimestamp() + ';' + record.getValueInW()
        + '}';
  }

  // TODO Temp
  private String buildAggActivePowerRecordString(final AggregatedActivePowerRecord record) {
    return "{" + record.getIdentifier() + ';' + record.getTimestamp() + ';' + record.getSumInW()
        + '}';
  }

}
