package streambench.heron.metrics;

import com.twitter.heron.spi.metricsmgr.metrics.MetricsRecord;
import com.twitter.heron.spi.metricsmgr.sink.IMetricsSink;
import com.twitter.heron.spi.metricsmgr.sink.SinkContext;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Adapted from https://github.com/tomncooper/heron-influxdb-sink/
 */
public class InfluxDBSink implements IMetricsSink {

    private static final Logger LOG = Logger.getLogger(
            InfluxDBSink.class.getName());

    /** InluxDB connection instance **/
    private InfluxDB influxDB;
    /** Heron Topology ID string **/
    private String topologyName;
    /** The hostname of the influxDB server */
    private String serverHost;
    /** The port of the influxDB server */
    private String serverPort;
    /** The name of the database on influxDB server that metrics will sent to */
    private String dbName;

    // Set the config key names for the metrics_sink.yaml config file
    private static final String SERVER_HOST_KEY = "influx-host";
    private static final String SERVER_PORT_KEY = "influx-port";
    private static final String METRIC_DB_NAME_KEY = "influx-db-name";
    private static final String DB_USERNAME_KEY = "influx-db-username";
    private static final String DB_PASSWORD_KEY = "influx-db-password";

    @Override
    public void init(Map<String, Object> conf, SinkContext context) {
        LOG.info("Configuration: " + conf.toString());
        topologyName = context.getTopologyName();

        serverHost = (String) conf.get(SERVER_HOST_KEY);
        serverPort = Integer.toString((int) conf.get(SERVER_PORT_KEY));
        dbName = (String) conf.get(METRIC_DB_NAME_KEY);

        // Check if username and passwords fields have been set
        if(conf.containsKey(DB_USERNAME_KEY) && conf.containsKey(DB_PASSWORD_KEY)) {
            String dbUser = (String) conf.get(DB_USERNAME_KEY);
            String dbPwd = (String) conf.get(DB_PASSWORD_KEY);
            influxDB = InfluxDBFactory.connect(serverHost + ":" + serverPort, dbUser, dbPwd);
        } else {
            influxDB = InfluxDBFactory.connect(serverHost + ":" + serverPort);
        }

        influxDB.enableBatch();

        // TODO: check if database exists, create if it doesn't

        LOG.info("Influx Connection created");
    }

    @Override
    public void processRecord(MetricsRecord record) {
        Long timestamp = record.getTimestamp();
        String source = record.getSource();

        BatchPoints points = BatchPoints.database(dbName)
                .tag("Topology", topologyName)
                .tag("Source", source)
                .build();

        // Cycle through the metrics and add them to points batch
        record.getMetrics().forEach(
            metric -> {
                Point point;
                try {
                    point = Point
                        .measurement(metric.getName())
                        .time(timestamp, TimeUnit.MILLISECONDS)
                        .addField("value", new Double(metric.getValue()))
                        .build();
                } catch (Exception e) {
                    point = Point
                        .measurement(metric.getName())
                        .time(timestamp, TimeUnit.MILLISECONDS)
                        .addField("value", metric.getValue())
                        .build();
                }

                // Add the current metric to the points batch
                points.point(point);
            }
        );

        try {
            //Write the batch of points for this tasks metrics to InfluxDB.
            influxDB.write(points);
            LOG.info(points.getPoints().size() + " metrics sent to " +
                    "InfluxDB with timestamp: " + timestamp);
        } catch (NullPointerException n) {
            LOG.warning("Sending to InfluxDB raised a NullPointer Exception");
            n.printStackTrace();
        } catch (Exception e) {
            LOG.warning("Sending to InfluxDB raised a generic exception");
            for(Point point : points.getPoints())
                System.err.println(point);
            e.printStackTrace();
        }
    }

    @Override
    public void flush() {
        LOG.info("Flushing any remaining metrics to InfluxDB");
        if(influxDB != null)
            influxDB.flush();
    }

    @Override
    public void close() {
        LOG.info("Closing InfluxDB connection");
        if(influxDB != null)
            influxDB.close();
    }
}
