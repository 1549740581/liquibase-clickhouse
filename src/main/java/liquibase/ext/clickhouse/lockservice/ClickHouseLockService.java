/*-
 * #%L
 * Liquibase extension for Clickhouse
 * %%
 * Copyright (C) 2020 - 2025 Mediarithmics
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package liquibase.ext.clickhouse.lockservice;

import static liquibase.ext.clickhouse.lockservice.LiquibaseClickhouseLockConstants.LOCK_TIME_COLUMN_NAME_PROPERTY_NAME;
import static liquibase.ext.clickhouse.lockservice.LiquibaseClickhouseLockConstants.LOCK_TIME_DEFAULT_COLUMN_NAME;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import liquibase.ext.clickhouse.database.ClickHouseDatabase;

import liquibase.Scope;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.exception.LockException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.executor.Executor;
import liquibase.executor.ExecutorService;
import liquibase.lockservice.DatabaseChangeLogLock;
import liquibase.lockservice.StandardLockService;
import liquibase.logging.Logger;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.RawSqlStatement;
import liquibase.statement.core.SelectFromDatabaseChangeLogLockStatement;

public class ClickHouseLockService extends StandardLockService {

  private boolean isLockTableInitialized;

  @Override
  public int getPriority() {
    return PRIORITY_DATABASE;
  }

  @Override
  public boolean supports(Database database) {
    return database instanceof ClickHouseDatabase;
  }

  @Override
  public DatabaseChangeLogLock[] listLocks() throws LockException {
    try {
      if (!this.hasDatabaseChangeLogLockTable()) {
        return new DatabaseChangeLogLock[0];
      }
      String lockTimeFieldName =
          System.getProperty(LOCK_TIME_COLUMN_NAME_PROPERTY_NAME, LOCK_TIME_DEFAULT_COLUMN_NAME);

      List<DatabaseChangeLogLock> allLocks = new ArrayList<>();
      SqlStatement sqlStatement =
          new SelectFromDatabaseChangeLogLockStatement(
              "ID", "LOCKED", lockTimeFieldName, "LOCKEDBY");
      List<Map<String, ?>> rows =
          Scope.getCurrentScope()
              .getSingleton(ExecutorService.class)
              .getExecutor("jdbc", database)
              .queryForList(sqlStatement);
      for (Map columnMap : rows) {
        Object lockedValue = columnMap.get("LOCKED");
        Boolean locked;
        if (lockedValue instanceof Number) {
          locked = ((Number) lockedValue).intValue() == 1;
        } else {
          locked = (Boolean) lockedValue;
        }
        if ((locked != null) && locked) {
          Object LOCKTIME = columnMap.get(lockTimeFieldName);
          final Date castedLOCKTIME;
          if (LOCKTIME instanceof LocalDateTime) {
            castedLOCKTIME =
                Date.from(((LocalDateTime) LOCKTIME).atZone(ZoneId.systemDefault()).toInstant());
          } else {
            castedLOCKTIME = (Date) LOCKTIME;
          }
          allLocks.add(
              new DatabaseChangeLogLock(
                  ((Number) columnMap.get("ID")).intValue(),
                  castedLOCKTIME,
                  (String) columnMap.get("LOCKEDBY")));
        }
      }
      return allLocks.toArray(new DatabaseChangeLogLock[allLocks.size()]);
    } catch (Exception e) {
      throw new LockException(e);
    }
  }

  @Override
  public boolean isDatabaseChangeLogLockTableInitialized(boolean tableJustCreated) {
    if (!isLockTableInitialized) {
      try {
        String query =
            String.format(
                "SELECT COUNT(*) FROM `%s`.%s",
                database.getDefaultSchemaName(), database.getDatabaseChangeLogLockTableName());
        int nbRows = getExecutor().queryForInt(new RawSqlStatement(query));
        isLockTableInitialized = nbRows > 0;
      } catch (LiquibaseException e) {
        if (getExecutor().updatesDatabase()) {
          throw new UnexpectedLiquibaseException(e);
        } else {
          isLockTableInitialized = !tableJustCreated;
        }
      }
    }
    return isLockTableInitialized;
  }

  @Override
  public boolean hasDatabaseChangeLogLockTable() {
    boolean hasTable = false;
    try {
      String query =
          String.format(
              "SELECT ID FROM `%s`.%s LIMIT 1",
              database.getDefaultSchemaName(), database.getDatabaseChangeLogLockTableName());
      getExecutor().execute(new RawSqlStatement(query));
      hasTable = true;
    } catch (DatabaseException e) {
      getLogger()
          .info(
              String.format("No %s table available", database.getDatabaseChangeLogLockTableName()));
    }
    return hasTable;
  }

  private Executor getExecutor() {
    return Scope.getCurrentScope()
        .getSingleton(ExecutorService.class)
        .getExecutor("jdbc", database);
  }

  private Logger getLogger() {
    return Scope.getCurrentScope().getLog(ClickHouseLockService.class);
  }
}
