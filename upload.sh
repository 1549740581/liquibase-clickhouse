mvn install;
mvn source:jar;
mvn deploy:deploy-file -e -Dversion=dx-202312.07.3 -DgroupId=com.darcytech -DartifactId=liquibase-clickhouse -Dpackaging=jar -Dfile=target/liquibase-clickhouse-0.7.3-SNAPSHOT.jar -Dsources=target/liquibase-clickhouse-0.7.3-SNAPSHOT-sources.jar -DrepositoryId=nexus-darcy -Durl=http://nexus.darcytech.com/repository/thirdparty;