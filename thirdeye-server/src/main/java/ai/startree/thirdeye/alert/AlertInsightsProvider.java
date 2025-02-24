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
package ai.startree.thirdeye.alert;

import static ai.startree.thirdeye.mapper.ApiBeanMapper.toAlertTemplateApi;
import static ai.startree.thirdeye.spi.ThirdEyeStatus.ERR_DATASET_NOT_FOUND;
import static ai.startree.thirdeye.spi.ThirdEyeStatus.ERR_MISSING_CONFIGURATION_FIELD;
import static ai.startree.thirdeye.spi.ThirdEyeStatus.ERR_UNKNOWN;
import static ai.startree.thirdeye.spi.util.AlertMetadataUtils.getGranularity;
import static ai.startree.thirdeye.spi.util.SpiUtils.optional;
import static ai.startree.thirdeye.util.ResourceUtils.serverError;
import static com.google.common.base.Preconditions.checkState;

import ai.startree.thirdeye.spi.Constants;
import ai.startree.thirdeye.spi.ThirdEyeException;
import ai.startree.thirdeye.spi.api.AlertApi;
import ai.startree.thirdeye.spi.api.AlertInsightsApi;
import ai.startree.thirdeye.spi.api.AlertInsightsRequestApi;
import ai.startree.thirdeye.spi.datalayer.bao.DatasetConfigManager;
import ai.startree.thirdeye.spi.datalayer.dto.AlertDTO;
import ai.startree.thirdeye.spi.datalayer.dto.AlertMetadataDTO;
import ai.startree.thirdeye.spi.datalayer.dto.AlertTemplateDTO;
import ai.startree.thirdeye.spi.datalayer.dto.DatasetConfigDTO;
import ai.startree.thirdeye.spi.datasource.loader.MinMaxTimeLoader;
import ai.startree.thirdeye.spi.util.TimeUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.WebApplicationException;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Chronology;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.Period;
import org.joda.time.chrono.ISOChronology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class AlertInsightsProvider {

  private static final Logger LOG = LoggerFactory.getLogger(AlertInsightsProvider.class);

  private static final Interval NOT_USED_INTERVAL = new Interval(0L, 0L, DateTimeZone.UTC);
  // computer clock difference is usually order of seconds - but here taking 1 hour is safe and does not impact the logic
  private static final long COMPUTER_CLOCK_MARGIN_MILLIS = 3600_000;
  private static final long FETCH_TIMEOUT_MILLIS = 30_000;

  private final AlertTemplateRenderer alertTemplateRenderer;
  private final DatasetConfigManager datasetConfigManager;
  private final MinMaxTimeLoader minMaxTimeLoader;

  @Inject
  public AlertInsightsProvider(final AlertTemplateRenderer alertTemplateRenderer,
      final DatasetConfigManager datasetConfigManager,
      final MinMaxTimeLoader minMaxTimeLoader) {
    this.alertTemplateRenderer = alertTemplateRenderer;
    this.datasetConfigManager = datasetConfigManager;
    this.minMaxTimeLoader = minMaxTimeLoader;
  }

  public AlertInsightsApi getInsights(final AlertInsightsRequestApi request) {
    final AlertApi alertApi = request.getAlert();
    try {
      final AlertTemplateDTO templateWithProperties = alertTemplateRenderer.renderAlert(alertApi,
          NOT_USED_INTERVAL);
      return buildInsights(templateWithProperties);
    } catch (final WebApplicationException e) {
      throw e;
    } catch (final Exception e) {
      // can do better exception handling if necessary - see handleAlertEvaluationException
      throw serverError(ERR_UNKNOWN, e);
    }
  }

  public AlertInsightsApi getInsights(final AlertDTO alertDTO) {
    try {
      final AlertTemplateDTO templateWithProperties = alertTemplateRenderer.renderAlert(alertDTO,
          NOT_USED_INTERVAL);
      return buildInsights(templateWithProperties);
    } catch (final WebApplicationException e) {
      throw e;
    } catch (final Exception e) {
      // can do better exception handling if necessary - see handleAlertEvaluationException
      throw serverError(ERR_UNKNOWN, e);
    }
  }

  private AlertInsightsApi buildInsights(final AlertTemplateDTO templateWithProperties)
      throws Exception {
    final AlertMetadataDTO metadata = templateWithProperties.getMetadata();

    final AlertInsightsApi insights = new AlertInsightsApi().setTemplateWithProperties(
        toAlertTemplateApi(templateWithProperties));
    addDatasetTimes(insights, metadata);

    return insights;
  }

  private void addDatasetTimes(@NonNull final AlertInsightsApi insights,
      @NonNull final AlertMetadataDTO metadata) throws Exception {
    final String datasetName = metadata.getDataset().getDataset();
    if (datasetName == null) {
      throw new ThirdEyeException(ERR_MISSING_CONFIGURATION_FIELD,
          "Dataset name not found in alert metadata.");
    }
    final DatasetConfigDTO datasetConfigDTO = datasetConfigManager.findByDataset(datasetName);
    if (datasetConfigDTO == null) {
      throw new ThirdEyeException(ERR_DATASET_NOT_FOUND, datasetName);
    }

    // fetch dataset interval
    addDatasetStartEndTimes(insights, datasetConfigDTO);
    addDefaultTimes(insights, metadata);
  }

  private void addDatasetStartEndTimes(final AlertInsightsApi insights,
      final DatasetConfigDTO datasetConfigDTO) throws Exception {
    final String dataSource = datasetConfigDTO.getDataSource();
    checkState(dataSource != null, "Datasource is null in configuration of dataset: %s.",
        datasetConfigDTO.getDataset());

    // launch min, max, safeMax queries async
    final Future<@Nullable Long> minTimeFuture = minMaxTimeLoader.fetchMinTimeAsync(
        datasetConfigDTO,
        null);
    final Future<@Nullable Long> maxTimeFuture = minMaxTimeLoader.fetchMaxTimeAsync(
        datasetConfigDTO,
        null);
    final long maximumPossibleEndTime = currentMaximumPossibleEndTime();
    final Interval safeInterval = new Interval(0L, maximumPossibleEndTime);
    final Future<@Nullable Long> safeMaxTimeFuture = minMaxTimeLoader.fetchMaxTimeAsync(
        datasetConfigDTO,
        safeInterval);

    // process futures
    addDatasetStart(insights, minTimeFuture);
    addDatasetEnd(insights, maxTimeFuture, safeMaxTimeFuture, maximumPossibleEndTime);
  }

  private void addDatasetStart(final AlertInsightsApi insights,
      final Future<@Nullable Long> minTimeFuture) throws Exception {
    final Long datasetStartTime = minTimeFuture.get(FETCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    insights.setDatasetStartTime(datasetStartTime);
  }

  private void addDatasetEnd(final AlertInsightsApi insights,
      final Future<@Nullable Long> maxTimeFuture, final Future<@Nullable Long> safeMaxTimeFuture,
      final long maximumPossibleEndTime) throws Exception {
    final @Nullable Long datasetMaxTime = maxTimeFuture.get(FETCH_TIMEOUT_MILLIS,
        TimeUnit.MILLISECONDS);
    if (datasetMaxTime == null) {
      return;
    }
    if (datasetMaxTime <= maximumPossibleEndTime) {
      insights.setDatasetEndTime(datasetMaxTime);
    } else {
      // there is bad data in the dataset, datasetMaxTime has an incorrect value, bigger than the current time - see TE-860
      // use the safe endTime
      insights.setSuspiciousDatasetEndTime(datasetMaxTime);
      LOG.warn(
          "Dataset maxTime is too big: {}. Current system time: {}.Most likely a data issue in the dataset. Rerunning query with a filter < safeEndTime={} to get a safe maxTime.",
          datasetMaxTime,
          System.currentTimeMillis(),
          maximumPossibleEndTime);
      final @Nullable Long safeMaxTime = safeMaxTimeFuture.get(FETCH_TIMEOUT_MILLIS,
          TimeUnit.MILLISECONDS);
      insights.setDatasetEndTime(safeMaxTime);
    }
  }

  // default times for chart - to call after dataset times are set in insights
  private void addDefaultTimes(final AlertInsightsApi insights, final AlertMetadataDTO metadata) {
    final Long datasetStartTime = insights.getDatasetStartTime();
    final Long datasetEndTime = insights.getDatasetEndTime();
    if (datasetStartTime == null || datasetEndTime == null) {
      // cannot compute
      return;
    }
    final Chronology chronology = optional(metadata.getTimezone()).map(DateTimeZone::forID)
        .map(ISOChronology::getInstance)
        .map(e -> (Chronology) e)
        .orElse(Constants.DEFAULT_CHRONOLOGY);
    final Interval datasetInterval = new Interval(datasetStartTime, datasetEndTime, chronology);
    // FIXME CYRIL internalize this?
    final Period granularity = getGranularity(metadata);
    // compute default chart interval
    final Interval defaultInterval = getDefaultChartInterval(datasetInterval, granularity);
    insights.setDefaultStartTime(defaultInterval.getStartMillis());
    insights.setDefaultEndTime(defaultInterval.getEndMillis());
  }

  public static long currentMaximumPossibleEndTime() {
    return System.currentTimeMillis() + COMPUTER_CLOCK_MARGIN_MILLIS;
  }

  @VisibleForTesting
  protected static Interval getDefaultChartInterval(final @NonNull Interval datasetInterval,
      @NonNull final Period granularity) {
    final DateTime datasetEndTimeBucketStart = TimeUtils.floorByPeriod(datasetInterval.getEnd(),
        granularity);

    DateTime defaultStartTime = datasetEndTimeBucketStart.minus(defaultChartTimeframe(granularity));
    if (defaultStartTime.getMillis() < datasetInterval.getStartMillis()) {
      defaultStartTime = TimeUtils.floorByPeriod(datasetInterval.getStart(), granularity);
      // first bucket may be incomplete - start from second one
      defaultStartTime = defaultStartTime.plus(granularity);
    }

    final DateTime datasetEndTimeBucketEnd = datasetEndTimeBucketStart.plus(granularity);
    return new Interval(defaultStartTime, datasetEndTimeBucketEnd);
  }

  /**
   * Returns a default Period for the UI timeseries chart timeframe, based on the alert granularity.
   * Rule of thumb.
   */
  @VisibleForTesting
  protected static Period defaultChartTimeframe(final Period alertGranularity) {
    if (alertGranularity.getMonths() != 0 || alertGranularity.getYears() != 0) {
      return Period.years(4);
    }
    final long granularityMillis = alertGranularity.toStandardDuration().getMillis();
    if (granularityMillis <= Period.millis(10).toStandardDuration().getMillis()) {
      // for PT0.01S granularity: 2000 points
      return Period.seconds(20);
    } else if (granularityMillis <= Period.millis(100).toStandardDuration().getMillis()) {
      // for PT0.1S granularity: 2400 points
      return Period.minutes(4);
    } else if (granularityMillis <= Period.seconds(1).toStandardDuration().getMillis()) {
      // for PT1S granularity: 1800 points
      return Period.minutes(30);
    } else if (granularityMillis <= Period.seconds(15).toStandardDuration().getMillis()) {
      // for PT15S granularity: 1920 points
      return Period.hours(8);
    } else if (granularityMillis <= Period.minutes(1).toStandardDuration().getMillis()) {
      // for PT1M granularity: 2880 points
      return Period.days(2);
    } else if (granularityMillis <= Period.minutes(5).toStandardDuration().getMillis()) {
      // for PT5M granularity: 2016 points
      return Period.days(7);
    } else if (granularityMillis <= Period.minutes(15).toStandardDuration().getMillis()) {
      // for PT15M granularity: 1344 points
      return Period.days(14);
    } else if (granularityMillis <= Period.hours(1).toStandardDuration().getMillis()) {
      // for PT1H granularity: 1440 points
      return Period.months(2);
    } else if (granularityMillis <= Period.days(1).toStandardDuration().getMillis()) {
      // for P1D granularity: 180 points
      return Period.months(6);
    } else if (granularityMillis <= Period.weeks(1).toStandardDuration().getMillis()) {
      // for P7D granularity: 52 points
      return Period.years(2);
    }
    // for monthly granularity: 36 points
    return Period.years(3);
  }
}
