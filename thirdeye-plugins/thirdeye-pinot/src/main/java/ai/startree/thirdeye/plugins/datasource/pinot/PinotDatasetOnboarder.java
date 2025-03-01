/*
 * Copyright 2023 StarTree Inc
 *
 * Licensed under the StarTree Community License (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.startree.ai/legal/startree-community-license
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT * WARRANTIES OF ANY KIND,
 * either express or implied.
 * See the License for the specific language governing permissions and limitations under
 * the License.
 */
package ai.startree.thirdeye.plugins.datasource.pinot;

import static ai.startree.thirdeye.spi.Constants.DEFAULT_CHRONOLOGY;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import ai.startree.thirdeye.plugins.datasource.pinot.restclient.PinotControllerRestClient;
import ai.startree.thirdeye.spi.datalayer.Templatable;
import ai.startree.thirdeye.spi.datalayer.dto.DatasetConfigDTO;
import ai.startree.thirdeye.spi.datalayer.dto.MetricConfigDTO;
import ai.startree.thirdeye.spi.metric.MetricAggFunction;
import ai.startree.thirdeye.spi.metric.MetricType;
import ai.startree.thirdeye.spi.util.SpiUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.apache.pinot.spi.data.DateTimeFieldSpec;
import org.apache.pinot.spi.data.DateTimeFormatSpec;
import org.apache.pinot.spi.data.MetricFieldSpec;
import org.apache.pinot.spi.data.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class PinotDatasetOnboarder {

  public static final MetricAggFunction DEFAULT_AGG_FUNCTION = MetricAggFunction.SUM;
  public static final MetricAggFunction DEFAULT_TDIGEST_AGG_FUNCTION = MetricAggFunction.PCT90;
  private static final Logger LOG = LoggerFactory.getLogger(PinotDatasetOnboarder.class);

  /* Use "ROW_COUNT" as the special token for the count(*) metric for a pinot table */
  private static final String BYTES_STRING = "BYTES";
  private static final String NON_ADDITIVE = "non_additive";
  private static final String PINOT_PRE_AGGREGATED_KEYWORD = "*";

  private final PinotControllerRestClient pinotControllerRestClient;

  @Inject
  public PinotDatasetOnboarder(final PinotControllerRestClient pinotControllerRestClient) {
    this.pinotControllerRestClient = pinotControllerRestClient;
  }

  public static void setDateTimeSpecs(final DatasetConfigDTO datasetConfigDTO,
      final DateTimeFieldSpec dateTimeFieldSpec) {
    Preconditions.checkNotNull(dateTimeFieldSpec);
    final String timeFormat = parseTimeFormat(dateTimeFieldSpec);
    datasetConfigDTO
        .setTimeColumn(dateTimeFieldSpec.getName())
        .setTimeFormat(timeFormat)
        .setTimezone(DEFAULT_CHRONOLOGY.getZone().toString());
  }

  private static String parseTimeFormat(final DateTimeFieldSpec dateTimeFieldSpec) {
    final DateTimeFormatSpec formatSpec = new DateTimeFormatSpec(dateTimeFieldSpec.getFormat());
    switch (formatSpec.getTimeFormat()) {
      case EPOCH:
      case SIMPLE_DATE_FORMAT:
        return formatSpec.getFormat();
      case TIMESTAMP:
        // fixme this is unlikely to be correct and not tested - previous behaviour was undefined anyway
        //  left for another PR  - see https://cortexdata.atlassian.net/browse/TE-1674
        return formatSpec.getFormat();
      default:
        throw new UnsupportedOperationException(String.format("Unsupported pinot time format: %s", formatSpec.getTimeFormat()));
    }
  }

  public static DatasetConfigDTO generateDatasetConfig(final String dataset, final Schema schema,
      final String timeColumnName,
      final Map<String, String> customConfigs, final String dataSourceName) {
    final List<String> dimensions = schema.getDimensionNames();
    final DateTimeFieldSpec dateTimeFieldSpec = schema.getSpecForTimeColumn(timeColumnName);
    // Create DatasetConfig
    final DatasetConfigDTO datasetConfigDTO = new DatasetConfigDTO()
        .setDataset(dataset)
        .setDimensions(Templatable.of(dimensions))
        .setDataSource(dataSourceName)
        .setProperties(customConfigs)
        .setActive(Boolean.TRUE);
    setDateTimeSpecs(datasetConfigDTO, dateTimeFieldSpec);
    checkNonAdditive(datasetConfigDTO);
    return datasetConfigDTO;
  }

  /**
   * Check if the dataset is non-additive. If it is, set the additive flag to false and set the
   * pre-aggregated keyword.
   *
   * @param dataset the dataset DTO to check
   */
  static void checkNonAdditive(final DatasetConfigDTO dataset) {
    if (dataset.isAdditive() && dataset.getDataset().endsWith(NON_ADDITIVE)) {
      dataset.setAdditive(false);
      dataset.setPreAggregatedKeyword(PINOT_PRE_AGGREGATED_KEYWORD);
    }
  }

  public static MetricConfigDTO generateMetricConfig(final MetricFieldSpec metricFieldSpec,
      final String dataset) {
    final MetricConfigDTO metricConfigDTO = new MetricConfigDTO();
    final String metric = metricFieldSpec.getName();
    metricConfigDTO.setName(metric);
    metricConfigDTO.setAlias(SpiUtils.constructMetricAlias(dataset, metric));
    metricConfigDTO.setDataset(dataset);
    metricConfigDTO.setActive(Boolean.TRUE);

    final String dataTypeStr = metricFieldSpec.getDataType().toString();
    if (BYTES_STRING.equals(dataTypeStr)) {
      // Assume if the column is BYTES type, use the default TDigest function and set the return data type to double
      metricConfigDTO.setDefaultAggFunction(DEFAULT_TDIGEST_AGG_FUNCTION.toString());
      metricConfigDTO.setDatatype(MetricType.DOUBLE);
    } else {
      metricConfigDTO.setDefaultAggFunction(DEFAULT_AGG_FUNCTION.toString());
      metricConfigDTO.setDatatype(MetricType.valueOf(dataTypeStr));
    }

    return metricConfigDTO;
  }

  public ImmutableList<String> getAllTables() throws IOException {
    return ImmutableList.copyOf(pinotControllerRestClient.getAllTablesFromPinot());
  }

  public List<DatasetConfigDTO> onboardAll(final String dataSourceName) throws IOException {
    final List<String> allTables = getAllTables();

    final List<DatasetConfigDTO> onboarded = new ArrayList<>();
    for (final String tableName : allTables) {
      try {
        final DatasetConfigDTO datasetConfigDTO = onboardTable(tableName, dataSourceName);
        onboarded.add(requireNonNull(datasetConfigDTO, "Dataset config is null"));
      } catch (final Exception e) {
        // Catch the exception and continue to onboard other tables
        LOG.error("Failed to onboard table: " + tableName, e);
      }
    }
    return onboarded;
  }

  public DatasetConfigDTO onboardTable(final String tableName, final String dataSourceName)
      throws IOException {
    final Schema schema = pinotControllerRestClient.getSchemaFromPinot(tableName);
    requireNonNull(schema, "Onboarding Error: schema is null for pinot table: " + tableName);
    checkArgument(!StringUtils.isBlank(schema.getSchemaName()),
        "Onboarding Error: schema name is blank for pinot table: " + tableName);

    final JsonNode tableConfigJson = pinotControllerRestClient
        .getTableConfigFromPinotEndpoint(tableName);
    checkArgument(tableConfigJson != null && !tableConfigJson.isNull(),
        "Onboarding Error: table config is null for pinot table: " + tableName);

    final String timeColumnName = pinotControllerRestClient
        .extractTimeColumnFromPinotTable(tableConfigJson);
    // rewrite above if to throw exception instead of returning null
    checkArgument(timeColumnName != null,
        "Onboarding Error: time column is null for pinot table: " + tableName);
    checkArgument(schema.getSpecForTimeColumn(timeColumnName) != null,
        "Onboarding Error: unable to get time column spec in schema for pinot table: " + tableName);

    final Map<String, String> pinotCustomProperties = pinotControllerRestClient
        .extractCustomConfigsFromPinotTable(tableConfigJson);

    return toDatasetConfigDTO(tableName,
        schema,
        timeColumnName,
        pinotCustomProperties,
        dataSourceName);
  }

  /**
   * Adds a new dataset to the thirdeye database
   */
  private DatasetConfigDTO toDatasetConfigDTO(final String dataset,
      final Schema schema,
      final String timeColumnName,
      final Map<String, String> customConfigs,
      final String dataSourceName) {
    final List<MetricFieldSpec> metricSpecs = schema.getMetricFieldSpecs();

    // Create DatasetConfig
    final DatasetConfigDTO datasetConfigDTO = generateDatasetConfig(dataset,
        schema,
        timeColumnName,
        customConfigs,
        dataSourceName);

    // Create MetricConfig
    final List<MetricConfigDTO> metrics = metricSpecs.stream()
        .map(metricFieldSpec -> generateMetricConfig(metricFieldSpec, dataset))
        .collect(Collectors.toList());

    datasetConfigDTO.setMetrics(metrics);
    return datasetConfigDTO;
  }

  public void close() {
    pinotControllerRestClient.close();
  }
}
