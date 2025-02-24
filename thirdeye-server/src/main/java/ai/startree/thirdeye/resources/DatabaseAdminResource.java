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
package ai.startree.thirdeye.resources;

import static ai.startree.thirdeye.util.ResourceUtils.resultSetToMap;

import ai.startree.thirdeye.auth.ThirdEyePrincipal;
import ai.startree.thirdeye.datalayer.DatabaseAdministrator;
import com.google.inject.Inject;
import io.dropwizard.auth.Auth;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SecurityRequirement(name="oauth")
@OpenAPIDefinition(security = {
    @SecurityRequirement(name = "oauth")
})
@SecurityScheme(name = "oauth", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER, paramName = HttpHeaders.AUTHORIZATION)
@Produces(MediaType.APPLICATION_JSON)
public class DatabaseAdminResource {

  private static final Logger log = LoggerFactory.getLogger(DatabaseAdminResource.class);

  private final DatabaseAdministrator databaseAdministrator;

  @Inject
  public DatabaseAdminResource(
      final DatabaseAdministrator databaseAdministrator) {
    this.databaseAdministrator = databaseAdministrator;
  }

  @GET
  @Path("tables")
  public Response getTables(@Parameter(hidden = true) @Auth ThirdEyePrincipal principal) throws Exception {
    return Response
        .ok(databaseAdministrator.getTables())
        .build();
  }

  @GET
  @Path("execute-query")
  public Response executeQuery(
      @Parameter(hidden = true) @Auth ThirdEyePrincipal principal,
      @QueryParam("sql") String sql
  ) throws Exception {
    return Response
        .ok(resultSetToMap(databaseAdministrator.executeQuery(sql)))
        .build();
  }

  @POST
  @Path("create-all-tables")
  public Response createAllTables(@Parameter(hidden = true) @Auth ThirdEyePrincipal principal) throws Exception {
    databaseAdministrator.createAllTables();
    return Response.ok().build();
  }

  @DELETE
  @Path("truncate-all-tables")
  public Response deleteAllData(@Parameter(hidden = true) @Auth ThirdEyePrincipal principal) throws Exception {
    log.warn("DELETING ALL DATABASE DATA!!! TRUNCATING TABLES!!!");
    databaseAdministrator.truncateTables();
    return Response.ok().build();
  }

  @DELETE
  @Path("drop-all-tables")
  public Response deleteAllTables(@Parameter(hidden = true) @Auth ThirdEyePrincipal principal) throws Exception {
    log.warn("DELETING ALL DATABASE TABLES!!! DROPPING TABLES!!!");
    databaseAdministrator.dropTables();
    return Response.ok().build();
  }
}
