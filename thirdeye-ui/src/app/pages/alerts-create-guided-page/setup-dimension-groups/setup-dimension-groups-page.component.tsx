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
 *
 * See the License for the specific language governing permissions and limitations under
 * the License.
 */
import { Box, Button, Grid, Typography } from "@material-ui/core";
import {
    default as React,
    FunctionComponent,
    useEffect,
    useState,
} from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useOutletContext } from "react-router-dom";
import { generateTemplateProperties } from "../../../components/alert-wizard-v3/threshold-setup/threshold-setup.utils";
import { CohortsTable } from "../../../components/cohort-detector/cohorts-table/cohorts-table.component";
import { DatasetDetails } from "../../../components/cohort-detector/dataset-details/dataset-details.component";
import { WizardBottomBar } from "../../../components/welcome-onboard-datasource/wizard-bottom-bar/wizard-bottom-bar.component";
import {
    PageContentsGridV1,
    useNotificationProviderV1,
} from "../../../platform/components";
import { MetricAggFunction } from "../../../rest/dto/metric.interfaces";
import { CohortResult } from "../../../rest/dto/rca.interfaces";
import { useGetCohort } from "../../../rest/rca/rca.actions";
import { GetCohortParams } from "../../../rest/rca/rca.interfaces";
import { notifyIfErrors } from "../../../utils/notifications/notifications.util";
import { AppRouteRelative } from "../../../utils/routes/routes.util";
import { AlertCreatedGuidedPageOutletContext } from "../alerts-create-guided-page.interfaces";

export const SetupDimensionGroupsPage: FunctionComponent = () => {
    const navigate = useNavigate();
    const { t } = useTranslation();
    const { notify } = useNotificationProviderV1();

    const { cohortsResponse, getCohorts, status, errorMessages } =
        useGetCohort();

    const { alert, onAlertPropertyChange, setIsMultiDimensionAlert } =
        useOutletContext<AlertCreatedGuidedPageOutletContext>();

    const [selectedCohorts, setSelectedCohorts] = useState<CohortResult[]>([]);

    useEffect(() => {
        setIsMultiDimensionAlert(true);
    }, []);

    useEffect(() => {
        notifyIfErrors(
            status,
            errorMessages,
            notify,
            t("message.error-while-fetching", {
                entity: t("label.cohorts-data"),
            })
        );
    }, [status]);

    const handleSearchButtonClick = (
        getCohortsParams: GetCohortParams
    ): void => {
        const params = { ...getCohortsParams };

        if (params.aggregationFunction === MetricAggFunction.COUNT) {
            params.roundOffThreshold = true;
        }

        getCohorts(params);
    };

    const handleCohortsSelectionChange = (cohorts: CohortResult[]): void => {
        setSelectedCohorts(cohorts);
    };

    const handleCreateBtnClick = (): void => {
        const enumerationItemConfiguration = selectedCohorts.map((cohort) => {
            const criteria = Object.keys(cohort.dimensionFilters).map((k) => {
                return `${k}='${cohort.dimensionFilters[k]}'`;
            });
            const joined = criteria.join(" AND ");

            return {
                params: {
                    queryFilters: ` AND ${joined}`,
                },
            };
        });

        onAlertPropertyChange({
            templateProperties: {
                ...alert.templateProperties,
                queryFilters: "${queryFilters}",
                enumerationItems: enumerationItemConfiguration,
            },
        });

        navigate(`../${AppRouteRelative.WELCOME_CREATE_ALERT_SELECT_METRIC}`);
    };

    return (
        <>
            <PageContentsGridV1>
                <Grid item xs={12}>
                    <Typography variant="h5">
                        {t("message.multidimension-setup")}
                    </Typography>
                    <Typography variant="body1">
                        {t(
                            "message.automatically-detects-and-prioritizes-dimensions"
                        )}
                    </Typography>
                </Grid>
                <Grid item xs={12}>
                    <DatasetDetails
                        initialSelectedAggregationFunc={
                            alert.templateProperties
                                .aggregationFunction as MetricAggFunction
                        }
                        initialSelectedDataset={
                            alert.templateProperties.dataset as string
                        }
                        initialSelectedDatasource={
                            alert.templateProperties.dataSource as string
                        }
                        initialSelectedMetric={
                            alert.templateProperties.aggregationColumn as string
                        }
                        submitButtonLabel={t(
                            "message.generate-dimensions-to-monitor"
                        )}
                        subtitle={t(
                            "message.automatically-detects-dimensions-based-on-your-selection"
                        )}
                        title={t("label.cohort-recommender")}
                        onMetricSelect={(
                            metric,
                            dataset,
                            aggregationFunction
                        ) => {
                            onAlertPropertyChange({
                                templateProperties: {
                                    ...alert.templateProperties,
                                    ...generateTemplateProperties(
                                        metric,
                                        dataset,
                                        aggregationFunction
                                    ),
                                },
                            });
                        }}
                        onSearchButtonClick={handleSearchButtonClick}
                    />
                </Grid>
                <Grid item xs={12}>
                    <CohortsTable
                        cohortsData={cohortsResponse}
                        getCohortsRequestStatus={status}
                        subtitle={t(
                            "message.select-the-dimensions-and-create-a-multi-dimension-alert"
                        )}
                        title={t("label.dimensions-and-outliers-results")}
                        onSelectionChange={handleCohortsSelectionChange}
                    >
                        <Box textAlign="right">
                            <Button
                                color="primary"
                                disabled={selectedCohorts.length === 0}
                                onClick={handleCreateBtnClick}
                            >
                                {t("label.create-multidimension-alert")}
                            </Button>
                        </Box>
                    </CohortsTable>
                </Grid>
            </PageContentsGridV1>

            <WizardBottomBar
                backBtnLink="../"
                nextBtnLink={
                    alert.templateProperties?.enumerationItems
                        ? `../${AppRouteRelative.WELCOME_CREATE_ALERT_SELECT_METRIC}`
                        : undefined
                }
            />
        </>
    );
};
