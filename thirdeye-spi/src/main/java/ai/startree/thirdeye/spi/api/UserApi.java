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
package ai.startree.thirdeye.spi.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Date;

@JsonInclude(Include.NON_NULL)
public class UserApi implements ThirdEyeApi {

  private Long id;
  private String principal;
  private Date created;

  public Long getId() {
    return id;
  }

  public UserApi setId(final Long id) {
    this.id = id;
    return this;
  }

  public String getPrincipal() {
    return principal;
  }

  public UserApi setPrincipal(final String principal) {
    this.principal = principal;
    return this;
  }

  public Date getCreated() {
    return created;
  }

  public UserApi setCreated(final Date created) {
    this.created = created;
    return this;
  }
}
