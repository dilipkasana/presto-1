/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.tests.hive;

import io.prestosql.tempto.ProductTest;
import io.prestosql.tempto.Requirement;
import io.prestosql.tempto.Requirements;
import io.prestosql.tempto.RequirementsProvider;
import io.prestosql.tempto.configuration.Configuration;
import io.prestosql.tempto.fulfillment.table.MutableTableRequirement;
import io.prestosql.tempto.fulfillment.table.TableDefinitionsRepository;
import io.prestosql.tempto.fulfillment.table.hive.HiveTableDefinition;
import org.testng.annotations.Test;

import static io.prestosql.tempto.assertions.QueryAssert.Row.row;
import static io.prestosql.tempto.assertions.QueryAssert.assertThat;
import static io.prestosql.tempto.fulfillment.table.MutableTableRequirement.State.CREATED;
import static io.prestosql.tempto.fulfillment.table.MutableTablesState.mutableTablesState;
import static io.prestosql.tempto.fulfillment.table.TableRequirements.immutableTable;
import static io.prestosql.tempto.fulfillment.table.hive.tpch.TpchTableDefinitions.NATION;
import static io.prestosql.tempto.query.QueryExecutor.query;
import static io.prestosql.tests.TestGroups.BIG_QUERY;
import static io.prestosql.tests.utils.QueryExecutors.onHive;
import static java.lang.String.format;

public class TestHiveBucketedTables
        extends ProductTest
        implements RequirementsProvider
{
    @TableDefinitionsRepository.RepositoryTableDefinition
    public static final HiveTableDefinition BUCKETED_PARTITIONED_NATION = HiveTableDefinition.builder("bucket_partition_nation")
            .setCreateTableDDLTemplate("CREATE TABLE %NAME%(" +
                    "n_nationkey     BIGINT," +
                    "n_name          STRING," +
                    "n_regionkey     BIGINT," +
                    "n_comment       STRING) " +
                    "PARTITIONED BY (part_key STRING) " +
                    "CLUSTERED BY (n_regionkey) " +
                    "INTO 2 BUCKETS " +
                    "ROW FORMAT DELIMITED FIELDS TERMINATED BY '|'")
            .setNoData()
            .build();

    @Override
    public Requirement getRequirements(Configuration configuration)
    {
        return Requirements.compose(
                MutableTableRequirement.builder(BUCKETED_PARTITIONED_NATION).withState(CREATED).build(),
                immutableTable(NATION));
    }

    @Test(groups = {BIG_QUERY})
    public void testIgnorePartitionBucketingIfNotBucketed()
    {
        String tableName = mutableTablesState().get(BUCKETED_PARTITIONED_NATION).getNameInDatabase();
        populateHivePartitionedTable(tableName, NATION.getName(), "part_key = 'insert_1'");
        populateHivePartitionedTable(tableName, NATION.getName(), "part_key = 'insert_2'");

        onHive().executeQuery(format("ALTER TABLE %s NOT CLUSTERED", tableName));

        assertThat(query(format("SELECT count(DISTINCT n_nationkey), count(*) FROM %s", tableName)))
                .hasRowsCount(1)
                .contains(row(25, 50));

        assertThat(query(format("SELECT count(*) FROM %s WHERE n_nationkey = 1", tableName)))
                .containsExactly(row(2));
    }

    @Test(groups = {BIG_QUERY})
    public void testAllowMultipleFilesPerBucket()
    {
        String tableName = mutableTablesState().get(BUCKETED_PARTITIONED_NATION).getNameInDatabase();
        for (int i = 0; i < 3; i++) {
            populateHivePartitionedTable(tableName, NATION.getName(), "part_key = 'insert'");
        }

        assertThat(query(format("SELECT count(DISTINCT n_nationkey), count(*) FROM %s", tableName)))
                .hasRowsCount(1)
                .contains(row(25, 75));

        assertThat(query(format("SELECT count(*) FROM %s WHERE n_nationkey = 1", tableName)))
                .containsExactly(row(3));
    }

    private static void populateHivePartitionedTable(String destination, String source, String partition)
    {
        String queryStatement = format("INSERT INTO TABLE %s PARTITION (%s) SELECT * FROM %s", destination, partition, source);

        onHive().executeQuery("set hive.enforce.bucketing = true");
        onHive().executeQuery("set hive.enforce.sorting = true");
        onHive().executeQuery(queryStatement);
    }
}
