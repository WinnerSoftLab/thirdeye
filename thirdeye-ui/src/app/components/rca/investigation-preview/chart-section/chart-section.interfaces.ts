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
import { AlertInsight } from "../../../../rest/dto/alert.interfaces";
import { Anomaly } from "../../../../rest/dto/anomaly.interfaces";
import { Event } from "../../../../rest/dto/event.interfaces";
import { AnomalyFilterOption } from "../../anomaly-breakdown-comparison-heatmap/anomaly-breakdown-comparison-heatmap.interfaces";
import { ChartType } from "../investigation-preview.interfaces";

export interface ChartSectionProps {
    availableDimensionCombinations: AnomalyFilterOption[][];
    selectedDimensionCombinations: Set<string>;
    anomaly: Anomaly | null;
    alertInsight: AlertInsight | null;
    events: Event[];
    chartType: ChartType;
}
