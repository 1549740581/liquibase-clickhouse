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
import java.util.UUID;

import liquibase.ext.clickhouse.database.ClickHouseDatabase;
import liquibase.ext.clickhouse.params.ClusterConfig;
import liquibase.ext.clickhouse.params.ParamsLoader;

import liquibase.Scope;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
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
import liquibase.statement.core.RawParameterizedSqlStatement;
import liquibase.statement.core.RawSqlStatement;
import liquibase.statement.core.SelectFromDatabaseChangeLogLockStatement;
import liquibase.util.NetUtil;

public class ClickHouseLockService extends StandardLockService {

  private boolean isLockTableInitialized;
  private String lockOwner;

  @Override
  public boolean acquireLock() throws LockException {
    if (hasChangeLogLock) {
      return true;
    }
    try {
      init();
      if (readLockState().locked) {
        return false;
      }
      if (lockOwner == null) {
        lockOwner =
            NetUtil.getLocalHostName()
                + " ("
                + NetUtil.getLocalHostAddress()
                + ") "
                + UUID.randomUUID();
      }
      // ClickHouse mutations do not report a reliable affected-row count.
      executeMutation(
          "LOCKED = 1, LOCKEDBY = ?, " + lockTimeColumn() + " = now()",
          "ID = 1 AND LOCKED = 0",
          lockOwner);
      LockState state = readLockState();
      hasChangeLogLock = state.locked && lockOwner.equals(state.owner);
      if (hasChangeLogLock) {
        database.commit();
        Scope.getCurrentScope().getSingleton(ChangeLogHistoryServiceFactory.class).resetAll();
        database.setCanCacheLiquibaseTableInfo(true);
        getLogger().info("Successfully acquired change log lock");
      }
      return hasChangeLogLock;
    } catch (Exception e) {
      throw new LockException(e);
    }
  }

  @Override
  public void releaseLock() throws LockException {
    if (hasChangeLogLock) {
      unlock(false);
    }
  }

  @Override
  public void forceReleaseLock() throws LockException, DatabaseException {
    init();
    unlock(true);
  }

  private void unlock(boolean force) throws LockException {
    try {
      String assignments = "LOCKED = 0, LOCKEDBY = NULL, " + lockTimeColumn() + " = NULL";
      if (force) {
        executeMutation(assignments, "ID = 1 AND LOCKED = 1");
      } else {
        executeMutation(assignments, "ID = 1 AND LOCKED = 1 AND LOCKEDBY = ?", lockOwner);
      }
      if (readLockState().locked) {
        throw new LockException(
            "Change log lock remains held; ordinary unlock only releases this instance's lock");
      }
      database.commit();
      getLogger().info("Successfully released change log lock");
    } catch (Exception e) {
      throw new LockException(e);
    } finally {
      hasChangeLogLock = false;
      database.setCanCacheLiquibaseTableInfo(false);
    }
  }

  private void executeMutation(String assignments, String condition, Object... parameters)
      throws DatabaseException {
    ClusterConfig cluster = ParamsLoader.getLiquibaseClickhouseProperties();
    String onCluster = cluster == null ? "" : "ON CLUSTER '" + cluster.getClusterName() + "' ";
    String sql =
        "ALTER TABLE "
            + lockTable()
            + " "
            + onCluster
            + "UPDATE "
            + assignments
            + " WHERE "
            + condition
            + " SETTINGS mutations_sync = 1";
    getExecutor().execute(new RawParameterizedSqlStatement(sql, parameters));
  }

  private LockState readLockState() throws DatabaseException {
    List<Map<String, ?>> rows =
        getExecutor()
            .queryForList(
                new RawSqlStatement(
                    "SELECT LOCKED, LOCKEDBY FROM " + lockTable() + " WHERE ID = 1"));
    if (rows.size() != 1) {
      throw new DatabaseException("Expected exactly one change log lock row, found " + rows.size());
    }
    Object locked = rows.get(0).get("LOCKED");
    if (!(locked instanceof Boolean) && !(locked instanceof Number)) {
      throw new DatabaseException("Invalid LOCKED value: " + locked);
    }
    boolean isLocked =
        locked instanceof Boolean ? (Boolean) locked : ((Number) locked).intValue() != 0;
    return new LockState(isLocked, (String) rows.get(0).get("LOCKEDBY"));
  }

  private String lockTable() {
    return database.escapeTableName(
        database.getLiquibaseCatalogName(),
        database.getLiquibaseSchemaName(),
        database.getDatabaseChangeLogLockTableName());
  }

  private String lockTimeColumn() {
    String name =
        System.getProperty(LOCK_TIME_COLUMN_NAME_PROPERTY_NAME, LOCK_TIME_DEFAULT_COLUMN_NAME);
    return database.escapeColumnName(
        database.getLiquibaseCatalogName(),
        database.getLiquibaseSchemaName(),
        database.getDatabaseChangeLogLockTableName(),
        name);
  }

  @Override
  public void reset() {
    super.reset();
    isLockTableInitialized = false;
  }

  private static class LockState {
    private final boolean locked;
    private final String owner;

    private LockState(boolean locked, String owner) {
      this.locked = locked;
      this.owner = owner;
    }
  }

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
      if (!this.isDatabaseChangeLogLockTableCreated()) {
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
  protected boolean isDatabaseChangeLogLockTableCreated() {
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
