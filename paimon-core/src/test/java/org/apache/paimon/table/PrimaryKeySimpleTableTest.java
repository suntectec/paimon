/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.table;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.CoreOptions.ChangelogProducer;
import org.apache.paimon.CoreOptions.LookupLocalFileType;
import org.apache.paimon.KeyValue;
import org.apache.paimon.Snapshot;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.disk.IOManager;
import org.apache.paimon.disk.IOManagerImpl;
import org.apache.paimon.fs.FileIOFinder;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.io.BundleRecords;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.manifest.FileKind;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.manifest.ManifestFileMeta;
import org.apache.paimon.operation.AbstractFileStoreWrite;
import org.apache.paimon.operation.DefaultValueAssigner;
import org.apache.paimon.operation.FileStoreScan;
import org.apache.paimon.options.MemorySize;
import org.apache.paimon.options.Options;
import org.apache.paimon.postpone.PostponeBucketFileStoreWrite;
import org.apache.paimon.postpone.PostponeBucketWriter;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.predicate.PredicateBuilder;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.SchemaUtils;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.query.LocalTableQuery;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.CommitMessageImpl;
import org.apache.paimon.table.sink.InnerTableCommit;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.sink.StreamWriteBuilder;
import org.apache.paimon.table.sink.TableWriteImpl;
import org.apache.paimon.table.sink.WriteSelector;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.InnerTableRead;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.ScanMode;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.table.source.StreamTableScan;
import org.apache.paimon.table.source.TableRead;
import org.apache.paimon.table.source.TableScan;
import org.apache.paimon.table.source.snapshot.SnapshotReader;
import org.apache.paimon.table.system.AuditLogTable;
import org.apache.paimon.table.system.FileMonitorTable;
import org.apache.paimon.table.system.ReadOptimizedTable;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowKind;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.ChangelogManager;
import org.apache.paimon.utils.Pair;

import org.apache.parquet.hadoop.ParquetOutputFormat;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.apache.paimon.CoreOptions.BUCKET;
import static org.apache.paimon.CoreOptions.CHANGELOG_FILE_FORMAT;
import static org.apache.paimon.CoreOptions.CHANGELOG_FILE_STATS_MODE;
import static org.apache.paimon.CoreOptions.CHANGELOG_NUM_RETAINED_MAX;
import static org.apache.paimon.CoreOptions.CHANGELOG_NUM_RETAINED_MIN;
import static org.apache.paimon.CoreOptions.CHANGELOG_PRODUCER;
import static org.apache.paimon.CoreOptions.ChangelogProducer.LOOKUP;
import static org.apache.paimon.CoreOptions.DELETION_VECTORS_ENABLED;
import static org.apache.paimon.CoreOptions.FILE_FORMAT;
import static org.apache.paimon.CoreOptions.FILE_FORMAT_PARQUET;
import static org.apache.paimon.CoreOptions.FILE_FORMAT_PER_LEVEL;
import static org.apache.paimon.CoreOptions.LOOKUP_LOCAL_FILE_TYPE;
import static org.apache.paimon.CoreOptions.MERGE_ENGINE;
import static org.apache.paimon.CoreOptions.METADATA_STATS_MODE;
import static org.apache.paimon.CoreOptions.METADATA_STATS_MODE_PER_LEVEL;
import static org.apache.paimon.CoreOptions.MergeEngine;
import static org.apache.paimon.CoreOptions.MergeEngine.AGGREGATE;
import static org.apache.paimon.CoreOptions.MergeEngine.DEDUPLICATE;
import static org.apache.paimon.CoreOptions.MergeEngine.FIRST_ROW;
import static org.apache.paimon.CoreOptions.MergeEngine.PARTIAL_UPDATE;
import static org.apache.paimon.CoreOptions.SNAPSHOT_EXPIRE_LIMIT;
import static org.apache.paimon.CoreOptions.SOURCE_SPLIT_OPEN_FILE_COST;
import static org.apache.paimon.CoreOptions.SOURCE_SPLIT_TARGET_SIZE;
import static org.apache.paimon.CoreOptions.TARGET_FILE_SIZE;
import static org.apache.paimon.Snapshot.CommitKind.COMPACT;
import static org.apache.paimon.data.DataFormatTestUtil.internalRowToString;
import static org.apache.paimon.io.DataFileTestUtils.row;
import static org.apache.paimon.predicate.PredicateBuilder.and;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link PrimaryKeyFileStoreTable}. */
public class PrimaryKeySimpleTableTest extends SimpleTableTestBase {

    @Test
    public void testPostponeBucketWithManyPartitions() throws Exception {
        FileStoreTable table =
                createFileStoreTable(options -> options.set(BUCKET, BucketMode.POSTPONE_BUCKET));

        BatchWriteBuilder writeBuilder = table.newBatchWriteBuilder();
        try (BatchTableWrite write = writeBuilder.newWrite();
                BatchTableCommit commit = writeBuilder.newCommit()) {
            write.withIOManager(new IOManagerImpl(tempDir.toString()));
            for (int i = 0; i < 100; i++) {
                write.write(rowData(i, i, (long) i));
            }

            for (Map<Integer, AbstractFileStoreWrite.WriterContainer<KeyValue>> bucketWriters :
                    ((PostponeBucketFileStoreWrite) ((TableWriteImpl<?>) write).getWrite())
                            .writers()
                            .values()) {
                for (AbstractFileStoreWrite.WriterContainer<KeyValue> writerContainer :
                        bucketWriters.values()) {
                    PostponeBucketWriter writer = (PostponeBucketWriter) writerContainer.writer;
                    assertThat(writer.useBufferedSinkWriter()).isTrue();
                }
            }
            commit.commit(write.prepareCommit());
        }

        Snapshot snapshot = table.latestSnapshot().get();
        ManifestFileMeta manifest =
                table.manifestListReader().read(snapshot.deltaManifestList()).get(0);
        List<ManifestEntry> entries = table.manifestFileReader().read(manifest.fileName());
        assertThat(entries.size()).isEqualTo(100);
    }

    @Test
    public void testPostponeBucket() throws Exception {
        FileStoreTable table =
                createFileStoreTable(options -> options.set(BUCKET, BucketMode.POSTPONE_BUCKET));

        BatchWriteBuilder writeBuilder = table.newBatchWriteBuilder();
        try (BatchTableWrite write = writeBuilder.newWrite();
                BatchTableCommit commit = writeBuilder.newCommit()) {
            write.write(rowData(0, 0, 0L));
            commit.commit(write.prepareCommit());
        }

        Snapshot snapshot = table.latestSnapshot().get();
        ManifestFileMeta manifest =
                table.manifestListReader().read(snapshot.deltaManifestList()).get(0);
        ManifestEntry entry = table.manifestFileReader().read(manifest.fileName()).get(0);
        assertThat(entry.bucket()).isEqualTo(BucketMode.POSTPONE_BUCKET);

        DataFileMeta file = entry.file();
        assertThat(file.fileName()).endsWith(".avro");
        assertThat(file.level()).isEqualTo(0);
        assertThat(file.valueStatsCols()).isEmpty();
    }

    @ParameterizedTest(name = "format-{0}")
    @ValueSource(strings = {"avro", "parquet"})
    public void testStatsModePerLevel(String format) throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set(FILE_FORMAT, format);
                            options.set(METADATA_STATS_MODE_PER_LEVEL, singletonMap("0", "none"));
                        });

        BatchWriteBuilder writeBuilder = table.newBatchWriteBuilder();

        // first write
        try (BatchTableWrite write = writeBuilder.newWrite();
                BatchTableCommit commit = writeBuilder.newCommit()) {
            write.write(rowData(0, 0, 0L));
            commit.commit(write.prepareCommit());
        }

        // assert level 0
        DataSplit split = (DataSplit) table.newScan().plan().splits().get(0);
        DataFileMeta file = split.dataFiles().get(0);
        assertThat(file.level()).isEqualTo(0);
        assertThat(file.valueStatsCols()).isEmpty();

        // second write
        try (BatchTableWrite write = writeBuilder.newWrite();
                BatchTableCommit commit = writeBuilder.newCommit()) {
            write.write(rowData(0, 0, 0L));
            commit.commit(write.prepareCommit());
        }

        // compact
        try (BatchTableWrite write = writeBuilder.newWrite();
                BatchTableCommit commit = writeBuilder.newCommit()) {
            write.compact(split.partition(), split.bucket(), true);
            commit.commit(write.prepareCommit());
        }

        // assert level 5
        file = ((DataSplit) table.newScan().plan().splits().get(0)).dataFiles().get(0);
        assertThat(file.level()).isEqualTo(5);
        assertThat(file.valueStats().maxValues().getFieldCount()).isGreaterThan(4);
    }

    @Test
    public void testMultipleWriters() throws Exception {
        WriteSelector selector =
                createFileStoreTable(options -> options.set("bucket", "5"))
                        .newBatchWriteBuilder()
                        .newWriteSelector()
                        .orElse(null);
        assertThat(selector).isNotNull();
        assertThat(selector.select(rowData(1, 1, 2L), 3)).isEqualTo(1);
        assertThat(selector.select(rowData(1, 3, 4L), 3)).isEqualTo(1);
        assertThat(selector.select(rowData(1, 5, 6L), 3)).isEqualTo(2);
    }

    @Test
    public void testAsyncReader() throws Exception {
        FileStoreTable table = createFileStoreTable();
        table = table.copy(singletonMap(CoreOptions.FILE_READER_ASYNC_THRESHOLD.key(), "1 b"));

        Map<Integer, GenericRow> rows = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            BatchWriteBuilder writeBuilder = table.newBatchWriteBuilder();
            BatchTableWrite write = writeBuilder.newWrite();
            BatchTableCommit commit = writeBuilder.newCommit();
            for (int j = 0; j < 1000; j++) {
                GenericRow row = rowData(1, i * j, 100L * i * j);
                rows.put(row.getInt(1), row);
                write.write(row);
            }
            commit.commit(write.prepareCommit());
            write.close();
            commit.close();
        }

        ReadBuilder readBuilder = table.newReadBuilder();
        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        TableRead read = readBuilder.newRead();

        Function<InternalRow, String> toString =
                r -> r.getInt(0) + "|" + r.getInt(1) + "|" + r.getLong(2);
        String[] expected =
                rows.values().stream()
                        .sorted(Comparator.comparingInt(o -> o.getInt(1)))
                        .map(toString)
                        .toArray(String[]::new);
        assertThat(getResult(read, splits, toString)).containsExactly(expected);
    }

    @Test
    public void testBatchWriteBuilder() throws Exception {
        FileStoreTable table = createFileStoreTable();
        BatchWriteBuilder writeBuilder = table.newBatchWriteBuilder();
        BatchTableWrite write = writeBuilder.newWrite();
        BatchTableCommit commit = writeBuilder.newCommit();
        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 11, 101L));
        commit.commit(write.prepareCommit());

        assertThatThrownBy(write::prepareCommit)
                .hasMessageContaining("BatchTableWrite only support one-time committing");
        assertThatThrownBy(() -> commit.commit(Collections.emptyList()))
                .hasMessageContaining("BatchTableCommit only support one-time committing");

        write.close();
        commit.close();

        ReadBuilder readBuilder = table.newReadBuilder();
        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        TableRead read = readBuilder.newRead();
        assertThat(getResult(read, splits, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "1|10|100|binary|varbinary|mapKey:mapVal|multiset",
                                "1|11|101|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testBatchRecordsWrite() throws Exception {
        FileStoreTable table = createFileStoreTable();

        List<InternalRow> list = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            list.add(rowData(i, 10, 100L));
        }

        BatchTableWrite write = table.newBatchWriteBuilder().newWrite();

        write.writeBundle(
                binaryRow(1),
                0,
                new BundleRecords() {
                    @Override
                    public long rowCount() {
                        return 1000;
                    }

                    @Override
                    public Iterator<InternalRow> iterator() {
                        return list.iterator();
                    }
                });

        List<CommitMessage> commitMessages = write.prepareCommit();

        table.newBatchWriteBuilder().newCommit().commit(commitMessages);

        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        TableRead read = table.newRead();
        AtomicInteger i = new AtomicInteger(0);
        read.createReader(splits)
                .forEachRemaining(
                        r -> {
                            i.incrementAndGet();
                            assertThat(r.getInt(1)).isEqualTo(10);
                            assertThat(r.getLong(2)).isEqualTo(100);
                        });
        Assertions.assertThat(i.get()).isEqualTo(1000);
    }

    @Test
    public void testSequenceField() throws Exception {
        FileStoreTable table =
                createFileStoreTable(conf -> conf.set(CoreOptions.SEQUENCE_FIELD, "b"));
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);
        write.write(rowData(1, 10, 200L));
        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 11, 101L));
        commit.commit(0, write.prepareCommit(true, 0));
        write.write(rowData(1, 11, 55L));
        commit.commit(1, write.prepareCommit(true, 1));
        write.close();
        commit.close();

        List<Split> splits = table.newSnapshotReader().read().splits();
        InnerTableRead read = table.newRead();
        Function<InternalRow, String> toString = BATCH_ROW_TO_STRING;
        assertThat(getResult(read, splits, binaryRow(1), 0, toString))
                .isEqualTo(
                        Arrays.asList(
                                "1|10|200|binary|varbinary|mapKey:mapVal|multiset",
                                "1|11|101|binary|varbinary|mapKey:mapVal|multiset"));

        // verify projection with sequence field

        // read with sequence field
        read = read.withProjection(new int[] {2, 1});
        toString = r -> r.getLong(0) + "|" + r.getInt(1);
        assertThat(getResult(read, splits, binaryRow(1), 0, toString))
                .isEqualTo(Arrays.asList("200|10", "101|11"));

        // read without sequence field
        read = read.withProjection(new int[] {1, 0});
        toString = r -> r.getInt(0) + "|" + r.getInt(1);
        assertThat(getResult(read, splits, binaryRow(1), 0, toString))
                .isEqualTo(Arrays.asList("10|1", "11|1"));
    }

    @Test
    public void testBatchReadWrite() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "1|10|1000|binary|varbinary|mapKey:mapVal|multiset"));
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "2|21|20001|binary|varbinary|mapKey:mapVal|multiset",
                                "2|22|202|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testBranchBatchReadWrite() throws Exception {
        FileStoreTable table = createFileStoreTable();
        generateBranch(table);
        FileStoreTable tableBranch = createBranchTable(BRANCH_NAME);
        writeBranchData(tableBranch);
        List<Split> splits = toSplits(tableBranch.newSnapshotReader().read().dataSplits());
        TableRead read = tableBranch.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "1|10|1000|binary|varbinary|mapKey:mapVal|multiset"));
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "2|21|20001|binary|varbinary|mapKey:mapVal|multiset",
                                "2|22|202|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testBatchProjection() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        TableRead read = table.newRead().withProjection(PROJECTION);
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Collections.singletonList("1000|10"));
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Arrays.asList("20001|21", "202|22"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testBatchFilter(boolean statsDenseStore) throws Exception {
        Consumer<Options> optionsSetter =
                options -> {
                    if (statsDenseStore) {
                        // pk table doesn't need value stats
                        options.set(CoreOptions.METADATA_STATS_MODE, "none");
                    }
                };
        writeData(optionsSetter);
        FileStoreTable table = createFileStoreTable(optionsSetter);

        PredicateBuilder builder = new PredicateBuilder(table.schema().logicalRowType());

        Predicate predicate = and(builder.equal(2, 201L), builder.equal(1, 21));
        List<Split> splits =
                toSplits(table.newSnapshotReader().withFilter(predicate).read().dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING)).isEmpty();
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                // only filter on key should be performed,
                                // and records from the same file should also be selected
                                "2|21|20001|binary|varbinary|mapKey:mapVal|multiset",
                                "2|22|202|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testStreamingReadWrite() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits =
                toSplits(table.newSnapshotReader().withMode(ScanMode.DELTA).read().dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "-1|11|1001|binary|varbinary|mapKey:mapVal|multiset"));
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "-2|20|200|binary|varbinary|mapKey:mapVal|multiset",
                                "+2|21|20001|binary|varbinary|mapKey:mapVal|multiset",
                                "+2|22|202|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testBranchStreamingReadWrite() throws Exception {
        FileStoreTable table = createFileStoreTable();
        generateBranch(table);

        FileStoreTable tableBranch = createBranchTable(BRANCH_NAME);
        writeBranchData(tableBranch);

        List<Split> splits =
                toSplits(
                        tableBranch
                                .newSnapshotReader()
                                .withMode(ScanMode.DELTA)
                                .read()
                                .dataSplits());
        TableRead read = tableBranch.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "-1|11|1001|binary|varbinary|mapKey:mapVal|multiset"));
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "-2|20|200|binary|varbinary|mapKey:mapVal|multiset",
                                "+2|21|20001|binary|varbinary|mapKey:mapVal|multiset",
                                "+2|22|202|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testStreamingProjection() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits =
                toSplits(table.newSnapshotReader().withMode(ScanMode.DELTA).read().dataSplits());
        TableRead read = table.newRead().withProjection(PROJECTION);

        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Collections.singletonList("-1001|11"));
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Arrays.asList("-200|20", "+20001|21", "+202|22"));
    }

    @Test
    public void testStreamingFilter() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();
        PredicateBuilder builder = new PredicateBuilder(table.schema().logicalRowType());

        Predicate predicate = and(builder.equal(2, 201L), builder.equal(1, 21));
        List<Split> splits =
                toSplits(
                        table.newSnapshotReader()
                                .withMode(ScanMode.DELTA)
                                .withFilter(predicate)
                                .read()
                                .dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_ROW_TO_STRING)).isEmpty();
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                // only filter on key should be performed,
                                // and records from the same file should also be selected
                                "-2|20|200|binary|varbinary|mapKey:mapVal|multiset",
                                "+2|21|20001|binary|varbinary|mapKey:mapVal|multiset",
                                "+2|22|202|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testStreamingInputChangelog(boolean specificConfig) throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(CHANGELOG_PRODUCER, ChangelogProducer.INPUT);
                            if (specificConfig) {
                                conf.set(FILE_FORMAT, "parquet");
                                conf.set(METADATA_STATS_MODE, "full");
                                conf.set(CHANGELOG_FILE_FORMAT, "avro");
                                conf.set(CHANGELOG_FILE_STATS_MODE, "none");
                            }
                        });
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);
        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 20, 200L));
        write.write(rowDataWithKind(RowKind.DELETE, 1, 10, 100L));
        write.write(rowData(1, 10, 101L));
        write.write(rowDataWithKind(RowKind.UPDATE_BEFORE, 1, 20, 200L));
        write.write(rowDataWithKind(RowKind.UPDATE_AFTER, 1, 20, 201L));
        write.write(rowDataWithKind(RowKind.UPDATE_BEFORE, 1, 10, 101L));
        write.write(rowDataWithKind(RowKind.UPDATE_AFTER, 1, 10, 102L));
        commit.commit(0, write.prepareCommit(true, 0));
        write.close();
        commit.close();

        List<Split> splits =
                toSplits(
                        table.newSnapshotReader().withMode(ScanMode.CHANGELOG).read().dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder(
                        "+I 1|10|100|binary|varbinary|mapKey:mapVal|multiset",
                        "+I 1|20|200|binary|varbinary|mapKey:mapVal|multiset",
                        "-D 1|10|100|binary|varbinary|mapKey:mapVal|multiset",
                        "+I 1|10|101|binary|varbinary|mapKey:mapVal|multiset",
                        "-U 1|20|200|binary|varbinary|mapKey:mapVal|multiset",
                        "+U 1|20|201|binary|varbinary|mapKey:mapVal|multiset",
                        "-U 1|10|101|binary|varbinary|mapKey:mapVal|multiset",
                        "+U 1|10|102|binary|varbinary|mapKey:mapVal|multiset");

        if (specificConfig) {
            Snapshot snapshot = table.latestSnapshot().get();
            ManifestFileMeta manifest =
                    table.manifestListReader().read(snapshot.changelogManifestList()).get(0);
            DataFileMeta file = table.manifestFileReader().read(manifest.fileName()).get(0).file();
            assertThat(file.fileName()).endsWith(".avro");
            assertThat(file.valueStats().minValues().getFieldCount()).isEqualTo(0);
        }
    }

    @Test
    public void testStreamingFullChangelog() throws Exception {
        innerTestStreamingFullChangelog(options -> {});
    }

    @Test
    public void testStreamingFullChangelogWithSpill() throws Exception {
        innerTestStreamingFullChangelog(
                options -> options.set(CoreOptions.SORT_SPILL_THRESHOLD, 2));
    }

    private void innerTestStreamingFullChangelog(Consumer<Options> configure) throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(CHANGELOG_PRODUCER, ChangelogProducer.FULL_COMPACTION);
                            configure.accept(conf);
                        });
        StreamTableWrite write =
                table.newWrite(commitUser).withIOManager(new IOManagerImpl(tempDir.toString()));
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 110L));
        write.write(rowData(1, 20, 120L));
        write.write(rowData(2, 10, 210L));
        write.write(rowData(2, 20, 220L));
        write.write(rowDataWithKind(RowKind.DELETE, 2, 10, 210L));
        write.compact(binaryRow(1), 0, true);
        write.compact(binaryRow(2), 0, true);
        commit.commit(0, write.prepareCommit(true, 0));

        List<Split> splits =
                toSplits(
                        table.newSnapshotReader().withMode(ScanMode.CHANGELOG).read().dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder(
                        "+I 1|10|110|binary|varbinary|mapKey:mapVal|multiset",
                        "+I 1|20|120|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(getResult(read, splits, binaryRow(2), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder("+I 2|20|220|binary|varbinary|mapKey:mapVal|multiset");

        write.write(rowData(1, 30, 130L));
        write.write(rowData(1, 40, 140L));
        write.write(rowData(2, 30, 230L));
        write.write(rowData(2, 40, 240L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowDataWithKind(RowKind.DELETE, 1, 40, 140L));
        write.write(rowData(2, 40, 241L));
        write.compact(binaryRow(1), 0, true);
        write.compact(binaryRow(2), 0, true);
        commit.commit(2, write.prepareCommit(true, 2));

        splits =
                toSplits(
                        table.newSnapshotReader().withMode(ScanMode.CHANGELOG).read().dataSplits());
        assertThat(getResult(read, splits, binaryRow(1), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder("+I 1|30|130|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(getResult(read, splits, binaryRow(2), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder(
                        "+I 2|30|230|binary|varbinary|mapKey:mapVal|multiset",
                        "+I 2|40|241|binary|varbinary|mapKey:mapVal|multiset");

        write.write(rowData(1, 20, 121L));
        write.write(rowData(1, 30, 131L));
        write.write(rowData(2, 30, 231L));
        commit.commit(3, write.prepareCommit(true, 3));

        write.write(rowDataWithKind(RowKind.DELETE, 1, 20, 121L));
        write.write(rowData(1, 30, 132L));
        write.write(rowData(1, 40, 141L));
        write.write(rowDataWithKind(RowKind.DELETE, 2, 20, 220L));
        write.write(rowData(2, 20, 221L));
        write.write(rowDataWithKind(RowKind.DELETE, 2, 20, 221L));
        write.write(rowData(2, 40, 242L));
        write.compact(binaryRow(1), 0, true);
        write.compact(binaryRow(2), 0, true);
        commit.commit(4, write.prepareCommit(true, 4));

        write.close();
        commit.close();

        splits =
                toSplits(
                        table.newSnapshotReader().withMode(ScanMode.CHANGELOG).read().dataSplits());
        assertThat(getResult(read, splits, binaryRow(1), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder(
                        "-D 1|20|120|binary|varbinary|mapKey:mapVal|multiset",
                        "-U 1|30|130|binary|varbinary|mapKey:mapVal|multiset",
                        "+U 1|30|132|binary|varbinary|mapKey:mapVal|multiset",
                        "+I 1|40|141|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(getResult(read, splits, binaryRow(2), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder(
                        "-D 2|20|220|binary|varbinary|mapKey:mapVal|multiset",
                        "-U 2|30|230|binary|varbinary|mapKey:mapVal|multiset",
                        "+U 2|30|231|binary|varbinary|mapKey:mapVal|multiset",
                        "-U 2|40|241|binary|varbinary|mapKey:mapVal|multiset",
                        "+U 2|40|242|binary|varbinary|mapKey:mapVal|multiset");
    }

    private void writeData() throws Exception {
        writeData(options -> {});
    }

    private void writeData(Consumer<Options> optionsSetter) throws Exception {
        FileStoreTable table = createFileStoreTable(optionsSetter);
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 100L));
        write.write(rowData(2, 20, 200L));
        write.write(rowData(1, 11, 101L));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(1, 10, 1000L));
        write.write(rowData(2, 21, 201L));
        write.write(rowData(2, 21, 2001L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowData(1, 11, 1001L));
        write.write(rowData(2, 21, 20001L));
        write.write(rowData(2, 22, 202L));
        write.write(rowDataWithKind(RowKind.DELETE, 1, 11, 1001L));
        write.write(rowDataWithKind(RowKind.DELETE, 2, 20, 200L));
        commit.commit(2, write.prepareCommit(true, 2));

        write.close();
        commit.close();
    }

    private void writeBranchData(FileStoreTable table) throws Exception {
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 100L));
        write.write(rowData(2, 20, 200L));
        write.write(rowData(1, 11, 101L));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(1, 10, 1000L));
        write.write(rowData(2, 21, 201L));
        write.write(rowData(2, 21, 2001L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowData(1, 11, 1001L));
        write.write(rowData(2, 21, 20001L));
        write.write(rowData(2, 22, 202L));
        write.write(rowDataWithKind(RowKind.DELETE, 1, 11, 1001L));
        write.write(rowDataWithKind(RowKind.DELETE, 2, 20, 200L));
        commit.commit(2, write.prepareCommit(true, 2));

        write.close();
        commit.close();
    }

    @Override
    @Test
    public void testReadFilter() throws Exception {
        FileStoreTable table = createFileStoreTable();

        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 20, 200L));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(1, 30, 300L));
        write.write(rowData(1, 40, 400L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowData(1, 50, 500L));
        write.write(rowData(1, 60, 600L));
        commit.commit(2, write.prepareCommit(true, 2));

        PredicateBuilder builder = new PredicateBuilder(ROW_TYPE);
        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());

        // push down key filter a = 30
        TableRead read = table.newRead().withFilter(builder.equal(1, 30));
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "1|30|300|binary|varbinary|mapKey:mapVal|multiset",
                                "1|40|400|binary|varbinary|mapKey:mapVal|multiset"));

        // push down value filter b = 300L
        read = table.newRead().withFilter(builder.equal(2, 300L));
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "1|30|300|binary|varbinary|mapKey:mapVal|multiset",
                                "1|40|400|binary|varbinary|mapKey:mapVal|multiset"));

        // push down both key filter and value filter
        read =
                table.newRead()
                        .withFilter(
                                PredicateBuilder.or(builder.equal(1, 10), builder.equal(2, 300L)));
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "1|10|100|binary|varbinary|mapKey:mapVal|multiset",
                                "1|20|200|binary|varbinary|mapKey:mapVal|multiset",
                                "1|30|300|binary|varbinary|mapKey:mapVal|multiset",
                                "1|40|400|binary|varbinary|mapKey:mapVal|multiset"));

        // update pk 60, 10
        write.write(rowData(1, 60, 500L));
        write.write(rowData(1, 10, 10L));
        commit.commit(3, write.prepareCommit(true, 3));

        write.close();
        commit.close();

        // cannot push down value filter b = 600L
        splits = toSplits(table.newSnapshotReader().read().dataSplits());
        read = table.newRead().withFilter(builder.equal(2, 600L));
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "1|10|10|binary|varbinary|mapKey:mapVal|multiset",
                                "1|20|200|binary|varbinary|mapKey:mapVal|multiset",
                                "1|30|300|binary|varbinary|mapKey:mapVal|multiset",
                                "1|40|400|binary|varbinary|mapKey:mapVal|multiset",
                                "1|50|500|binary|varbinary|mapKey:mapVal|multiset",
                                "1|60|500|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testPartialUpdateIgnoreDelete() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(
                                    CoreOptions.MERGE_ENGINE,
                                    CoreOptions.MergeEngine.PARTIAL_UPDATE);
                            conf.set(CoreOptions.IGNORE_DELETE, true);
                        });
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);
        write.write(rowData(1, 10, 200L));
        write.write(rowDataWithKind(RowKind.DELETE, 1, 10, 100L));
        write.write(rowDataWithKind(RowKind.UPDATE_BEFORE, 1, 10, 100L));
        write.write(rowDataWithKind(RowKind.DELETE, 1, 20, 400L));
        commit.commit(0, write.prepareCommit(true, 0));
        write.close();
        commit.close();

        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "1|10|200|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testWithShard() throws Exception {
        FileStoreTable table = createFileStoreTable(conf -> conf.set(BUCKET, 5));
        innerTestWithShard(table);
    }

    @Test
    public void testWithShardDeletionVectors() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(BUCKET, 5);
                            conf.set(DELETION_VECTORS_ENABLED, true);
                        });
        innerTestWithShard(table);
    }

    @Test
    public void testDeletionVectorsWithFileIndexInFile() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(BUCKET, 1);
                            conf.set(DELETION_VECTORS_ENABLED, true);
                            conf.set(TARGET_FILE_SIZE, MemorySize.ofBytes(1));
                            conf.set("file-index.bloom-filter.columns", "b");
                            conf.set(FILE_FORMAT_PER_LEVEL, singletonMap("0", "avro"));
                        });

        StreamTableWrite write =
                table.newWrite(commitUser).withIOManager(new IOManagerImpl(tempDir.toString()));
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 1, 300L));
        write.write(rowData(1, 2, 400L));
        write.write(rowData(1, 3, 200L));
        write.write(rowData(1, 4, 500L));
        commit.commit(0, write.prepareCommit(true, 0));

        // assert forcing rewriting when upgrading from level 0 to level x with different file
        // formats
        List<Split> splits = table.newReadBuilder().newScan().plan().splits();
        assertThat(((DataSplit) splits.get(0)).dataFiles().get(0).fileName()).endsWith("parquet");

        write.write(rowData(1, 5, 100L));
        write.write(rowData(1, 6, 600L));
        write.write(rowData(1, 7, 400L));
        commit.commit(1, write.prepareCommit(true, 1));

        PredicateBuilder builder = new PredicateBuilder(ROW_TYPE);
        splits = toSplits(table.newSnapshotReader().read().dataSplits());
        assertThat(((DataSplit) splits.get(0)).dataFiles().size()).isEqualTo(2);
        TableRead read = table.newRead().withFilter(builder.equal(2, 300L));
        assertThat(getResult(read, splits, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "1|1|300|binary|varbinary|mapKey:mapVal|multiset",
                                "1|2|400|binary|varbinary|mapKey:mapVal|multiset",
                                "1|3|200|binary|varbinary|mapKey:mapVal|multiset",
                                "1|4|500|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testDeletionVectorsWithParquetFilter() throws Exception {
        // RowGroup record range [pk] :
        //
        // RowGroup-0 :  [0-93421)
        // RowGroup-1 :  [93421-187794)
        // RowGroup-2 :  [187794-200000)
        //
        // ColumnPage record count :
        //
        // col-0 : 300
        // col-1 : 200
        // col-2 : 300
        // col-3 : 300
        // col-4 : 300
        // col-5 : 200
        // col-6 : 100
        // col-7 : 100
        // col-8 : 100
        // col-9 : 100
        // col-10 : 100
        // col-11 : 300

        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(BUCKET, 1);
                            conf.set(DELETION_VECTORS_ENABLED, true);
                            conf.set(FILE_FORMAT, "parquet");
                            conf.set("parquet.block.size", "1048576");
                            conf.set("parquet.page.size", "1024");
                        });

        BatchWriteBuilder writeBuilder = table.newBatchWriteBuilder();

        BatchTableWrite write =
                (BatchTableWrite)
                        writeBuilder
                                .newWrite()
                                .withIOManager(new IOManagerImpl(tempDir.toString()));

        for (int i = 0; i < 200000; i++) {
            write.write(rowData(1, i, i * 100L));
        }

        List<CommitMessage> messages = write.prepareCommit();
        BatchTableCommit commit = writeBuilder.newCommit();
        commit.commit(messages);
        write =
                (BatchTableWrite)
                        writeBuilder
                                .newWrite()
                                .withIOManager(new IOManagerImpl(tempDir.toString()));
        for (int i = 110000; i < 115000; i++) {
            write.write(rowDataWithKind(RowKind.DELETE, 1, i, i * 100L));
        }

        for (int i = 130000; i < 135000; i++) {
            write.write(rowDataWithKind(RowKind.DELETE, 1, i, i * 100L));
        }

        messages = write.prepareCommit();
        commit = writeBuilder.newCommit();
        commit.commit(messages);

        PredicateBuilder builder = new PredicateBuilder(ROW_TYPE);
        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        Random random = new Random();

        // point filter

        for (int i = 0; i < 10; i++) {
            int value = random.nextInt(110000);
            TableRead read = table.newRead().withFilter(builder.equal(1, value)).executeFilter();
            assertThat(getResult(read, splits, BATCH_ROW_TO_STRING))
                    .isEqualTo(
                            Arrays.asList(
                                    String.format(
                                            "%d|%d|%d|binary|varbinary|mapKey:mapVal|multiset",
                                            1, value, value * 100L)));
        }

        for (int i = 0; i < 10; i++) {
            int value = 130000 + random.nextInt(5000);
            TableRead read = table.newRead().withFilter(builder.equal(1, value)).executeFilter();
            assertThat(getResult(read, splits, BATCH_ROW_TO_STRING)).isEmpty();
        }

        TableRead tableRead =
                table.newRead()
                        .withFilter(
                                PredicateBuilder.and(
                                        builder.greaterOrEqual(1, 100000),
                                        builder.lessThan(1, 150000)))
                        .executeFilter();

        List<String> result = getResult(tableRead, splits, BATCH_ROW_TO_STRING);

        assertThat(result.size()).isEqualTo(40000); // filter 10000

        assertThat(result)
                .doesNotContain("1|110000|11000000|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(result)
                .doesNotContain("1|114999|11499900|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(result)
                .doesNotContain("1|130000|13000000|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(result)
                .doesNotContain("1|134999|13499900|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(result).contains("1|100000|10000000|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(result).contains("1|149999|14999900|binary|varbinary|mapKey:mapVal|multiset");

        assertThat(result).contains("1|101099|10109900|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(result).contains("1|115000|11500000|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(result).contains("1|129999|12999900|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(result).contains("1|135000|13500000|binary|varbinary|mapKey:mapVal|multiset");
    }

    @Test
    public void testDeletionVectorsWithFileIndexInMeta() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(BUCKET, 1);
                            conf.set(DELETION_VECTORS_ENABLED, true);
                            conf.set(TARGET_FILE_SIZE, MemorySize.ofBytes(1));
                            conf.set("file-index.bloom-filter.columns", "b");
                            conf.set("file-index.bloom-filter.b.items", "20");
                        });

        StreamTableWrite write =
                table.newWrite(commitUser).withIOManager(new IOManagerImpl(tempDir.toString()));
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 1, 300L));
        write.write(rowData(1, 2, 400L));
        write.write(rowData(1, 3, 200L));
        write.write(rowData(1, 4, 500L));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(1, 5, 100L));
        write.write(rowData(1, 6, 600L));
        write.write(rowData(1, 7, 400L));
        commit.commit(1, write.prepareCommit(true, 1));

        PredicateBuilder builder = new PredicateBuilder(ROW_TYPE);
        Predicate predicate = builder.equal(2, 300L);

        List<Split> splits =
                toSplits(table.newSnapshotReader().withFilter(predicate).read().dataSplits());

        assertThat(((DataSplit) splits.get(0)).dataFiles().size()).isEqualTo(1);
    }

    @Test
    public void testDeletionVectorsCombineWithFileIndexPushDownParquet() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(BUCKET, 1);
                            conf.set(FILE_FORMAT, FILE_FORMAT_PARQUET);
                            conf.set(DELETION_VECTORS_ENABLED, true);
                            conf.set(ParquetOutputFormat.BLOCK_SIZE, "524288");
                            conf.set(ParquetOutputFormat.MIN_ROW_COUNT_FOR_PAGE_SIZE_CHECK, "100");
                            conf.set(ParquetOutputFormat.PAGE_ROW_COUNT_LIMIT, "300");
                            conf.set("file-index.bitmap.columns", "b");
                        });

        BatchWriteBuilder batchWriteBuilder = table.newBatchWriteBuilder();
        BatchTableWrite batchWrite;
        BatchTableCommit batchCommit;

        // test bitmap index and deletion vector merge and using EmptyFileRecordReader to read
        batchWrite =
                (BatchTableWrite)
                        batchWriteBuilder
                                .newWrite()
                                .withIOManager(new IOManagerImpl(tempDir.toString()));
        batchCommit = batchWriteBuilder.newCommit();
        batchWrite.write(rowData(1, 1, 300L));
        batchWrite.write(rowData(1, 2, 400L));
        batchWrite.write(rowData(1, 3, 100L));
        batchWrite.write(rowData(1, 4, 100L));
        batchCommit.commit(batchWrite.prepareCommit());
        batchWrite.close();
        batchCommit.close();

        batchWrite =
                (BatchTableWrite)
                        batchWriteBuilder
                                .newWrite()
                                .withIOManager(new IOManagerImpl(tempDir.toString()));
        batchCommit = batchWriteBuilder.newCommit();
        batchWrite.write(rowDataWithKind(RowKind.DELETE, 1, 1, 300L));
        batchCommit.commit(batchWrite.prepareCommit());
        batchWrite.close();
        batchCommit.close();

        Predicate predicate = new PredicateBuilder(table.rowType()).equal(2, 300L);
        TableScan.Plan plan = table.newScan().plan();
        RecordReader<InternalRow> reader =
                table.newRead().withFilter(predicate).createReader(plan.splits());
        AtomicInteger cnt = new AtomicInteger(0);
        reader.forEachRemaining(row -> cnt.incrementAndGet());
        assertThat(cnt.get()).isEqualTo(0);
        reader.close();

        // truncate table
        BatchTableCommit truncateCommit = table.newBatchWriteBuilder().newCommit();
        truncateCommit.truncateTable();
        truncateCommit.close();

        // test parquet row ranges filtering
        int bound = 100;
        Random random = new Random();
        Map<Integer, Long> expectedMap = new HashMap<>();
        batchWrite =
                (BatchTableWrite)
                        batchWriteBuilder
                                .newWrite()
                                .withIOManager(new IOManagerImpl(tempDir.toString()));
        batchCommit = batchWriteBuilder.newCommit();
        for (int i = 0; i < 100000; i++) {
            long next = random.nextInt(bound);
            expectedMap.put(i, next);
            batchWrite.write(rowData(1, i, next));
        }
        batchCommit.commit(batchWrite.prepareCommit());
        batchWrite.close();
        batchCommit.close();

        batchWrite =
                (BatchTableWrite)
                        batchWriteBuilder
                                .newWrite()
                                .withIOManager(new IOManagerImpl(tempDir.toString()));
        batchCommit = batchWriteBuilder.newCommit();
        for (int i = 25000; i < 50000; i++) {
            batchWrite.write(rowDataWithKind(RowKind.DELETE, 1, i, expectedMap.remove(i)));
        }
        batchCommit.commit(batchWrite.prepareCommit());
        batchWrite.close();
        batchCommit.close();

        for (int i = 0; i < bound; i++) {
            long next = i;
            predicate = new PredicateBuilder(table.rowType()).equal(2, next);
            plan = table.newScan().plan();
            reader = table.newRead().withFilter(predicate).createReader(plan.splits());
            AtomicLong expectedCnt = new AtomicLong(0);
            reader.forEachRemaining(
                    row -> {
                        expectedCnt.incrementAndGet();
                        assertThat(row.getLong(2)).isEqualTo(expectedMap.get(row.getInt(1)));
                    });
            long count =
                    expectedMap.entrySet().stream().filter(x -> x.getValue().equals(next)).count();
            assertThat(expectedCnt.get()).isEqualTo(count);
            reader.close();
        }
    }

    @Test
    public void testWithShardFirstRow() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(BUCKET, 5);
                            conf.set(MERGE_ENGINE, FIRST_ROW);
                        });
        innerTestWithShard(table);
    }

    @Test
    public void testSlowCommit() throws Exception {
        FileStoreTable table = createFileStoreTable();
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 20, 200L));
        List<CommitMessage> committables0 = write.prepareCommit(false, 0);

        write.write(rowData(2, 10, 300L));
        List<CommitMessage> committables1 = write.prepareCommit(false, 1);

        write.write(rowData(1, 20, 201L));
        List<CommitMessage> committables2 = write.prepareCommit(true, 2);

        commit.commit(0, committables0);
        commit.commit(1, committables1);
        commit.commit(2, committables2);

        write.close();
        commit.close();

        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "1|10|100|binary|varbinary|mapKey:mapVal|multiset",
                                "1|20|201|binary|varbinary|mapKey:mapVal|multiset"));
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "2|10|300|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testIncrementalSplitOverwrite() throws Exception {
        FileStoreTable table = createFileStoreTable();
        StreamTableWrite write = table.newWrite(commitUser);
        InnerTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 20, 200L));
        commit.commit(0, write.prepareCommit(true, 0));

        SnapshotReader snapshotReader = table.newSnapshotReader().withMode(ScanMode.DELTA);
        List<DataSplit> splits0 = snapshotReader.read().dataSplits();
        assertThat(splits0).hasSize(1);
        assertThat(splits0.get(0).dataFiles()).hasSize(1);

        write.write(rowData(1, 10, 1000L));
        write.write(rowData(1, 20, 2000L));
        Map<String, String> overwritePartition = new HashMap<>();
        overwritePartition.put("pt", "1");
        commit.withOverwrite(overwritePartition);
        commit.commit(1, write.prepareCommit(true, 1));

        List<DataSplit> splits1 = snapshotReader.read().dataSplits();
        assertThat(splits1).hasSize(1);
        assertThat(splits1.get(0).dataFiles()).hasSize(1);
        assertThat(splits1.get(0).dataFiles().get(0).fileName())
                .isNotEqualTo(splits0.get(0).dataFiles().get(0).fileName());
        write.close();
        commit.close();
    }

    @Test
    public void testAuditLogWithDefaultValueFields() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(CHANGELOG_PRODUCER, ChangelogProducer.INPUT);
                            conf.set(
                                    String.format(
                                            "%s.%s.%s",
                                            CoreOptions.FIELDS_PREFIX,
                                            "b",
                                            DefaultValueAssigner.DEFAULT_VALUE_SUFFIX),
                                    "0");
                        });
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowDataWithKind(RowKind.INSERT, 2, 20, 200L));
        write.write(rowDataWithKind(RowKind.INSERT, 2, 21, null));
        commit.commit(0, write.prepareCommit(true, 0));

        write.close();
        commit.close();

        AuditLogTable auditLogTable = new AuditLogTable(table);
        Function<InternalRow, String> rowDataToString =
                row ->
                        internalRowToString(
                                row,
                                DataTypes.ROW(
                                        DataTypes.STRING(),
                                        DataTypes.INT(),
                                        DataTypes.INT(),
                                        DataTypes.BIGINT()));

        PredicateBuilder predicateBuilder = new PredicateBuilder(auditLogTable.rowType());

        SnapshotReader snapshotReader =
                auditLogTable
                        .newSnapshotReader()
                        .withFilter(
                                and(
                                        predicateBuilder.equal(predicateBuilder.indexOf("b"), 300),
                                        predicateBuilder.equal(predicateBuilder.indexOf("pt"), 2)));
        InnerTableRead read = auditLogTable.newRead();
        List<String> result =
                getResult(read, toSplits(snapshotReader.read().dataSplits()), rowDataToString);
        assertThat(result).containsExactlyInAnyOrder("+I[+I, 2, 20, 200]", "+I[+I, 2, 21, 0]");
    }

    @Test
    public void testAuditLog() throws Exception {
        FileStoreTable table =
                createFileStoreTable(conf -> conf.set(CHANGELOG_PRODUCER, ChangelogProducer.INPUT));
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        // first commit
        write.write(rowDataWithKind(RowKind.INSERT, 1, 10, 100L));
        write.write(rowDataWithKind(RowKind.INSERT, 2, 20, 200L));
        commit.commit(0, write.prepareCommit(true, 0));

        // second commit
        write.write(rowDataWithKind(RowKind.UPDATE_AFTER, 1, 30, 300L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.close();
        commit.close();

        AuditLogTable auditLogTable = new AuditLogTable(table);
        Function<InternalRow, String> rowDataToString =
                row ->
                        internalRowToString(
                                row,
                                DataTypes.ROW(
                                        DataTypes.STRING(),
                                        DataTypes.INT(),
                                        DataTypes.INT(),
                                        DataTypes.BIGINT()));
        PredicateBuilder predicateBuilder = new PredicateBuilder(auditLogTable.rowType());

        // Read all
        SnapshotReader snapshotReader = auditLogTable.newSnapshotReader();
        TableRead read = auditLogTable.newRead();
        List<String> result =
                getResult(read, toSplits(snapshotReader.read().dataSplits()), rowDataToString);
        assertThat(result)
                .containsExactlyInAnyOrder(
                        "+I[+I, 2, 20, 200]", "+I[+U, 1, 30, 300]", "+I[+I, 1, 10, 100]");

        // Read by filter row kind (No effect)
        Predicate rowKindEqual = predicateBuilder.equal(0, BinaryString.fromString("+I"));
        snapshotReader = auditLogTable.newSnapshotReader().withFilter(rowKindEqual);
        read = auditLogTable.newRead().withFilter(rowKindEqual);
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowDataToString);
        assertThat(result)
                .containsExactlyInAnyOrder(
                        "+I[+I, 2, 20, 200]", "+I[+U, 1, 30, 300]", "+I[+I, 1, 10, 100]");

        // Read by filter
        snapshotReader =
                auditLogTable.newSnapshotReader().withFilter(predicateBuilder.equal(2, 10));
        read = auditLogTable.newRead().withFilter(predicateBuilder.equal(2, 10));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowDataToString);
        assertThat(result).containsExactlyInAnyOrder("+I[+I, 1, 10, 100]");

        // Read by requiredType
        RowType rowType = auditLogTable.rowType();
        snapshotReader = auditLogTable.newSnapshotReader();
        read = auditLogTable.newRead().withReadType(rowType.project("a", "rowkind", "pt"));
        Function<InternalRow, String> projectToString1 =
                row ->
                        internalRowToString(
                                row,
                                DataTypes.ROW(
                                        DataTypes.INT(), DataTypes.STRING(), DataTypes.INT()));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), projectToString1);
        assertThat(result)
                .containsExactlyInAnyOrder("+I[20, +I, 2]", "+I[30, +U, 1]", "+I[10, +I, 1]");

        // Read by requiredType without rowkind
        snapshotReader = auditLogTable.newSnapshotReader();
        read = auditLogTable.newRead().withReadType(rowType.project("a", "pt"));
        Function<InternalRow, String> projectToString2 =
                row -> internalRowToString(row, DataTypes.ROW(DataTypes.INT(), DataTypes.INT()));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), projectToString2);
        assertThat(result).containsExactlyInAnyOrder("+I[20, 2]", "+I[30, 1]", "+I[10, 1]");
    }

    @Test
    public void testAggregationRemoveRecordOnDelete() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT(), DataTypes.INT(), DataTypes.INT(), DataTypes.INT()
                        },
                        new String[] {"pt", "a", "b", "c"});
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set("merge-engine", "aggregation");
                            options.set("aggregation.remove-record-on-delete", "true");
                        },
                        rowType);
        Function<InternalRow, String> rowToString = row -> internalRowToString(row, rowType);
        SnapshotReader snapshotReader = table.newSnapshotReader();
        TableRead read = table.newRead();
        StreamTableWrite write = table.newWrite("");
        StreamTableCommit commit = table.newCommit("");
        // 1. Inserts
        write.write(GenericRow.of(1, 1, 3, 3));
        write.write(GenericRow.of(1, 1, 1, 1));
        write.write(GenericRow.of(1, 1, 2, 2));
        commit.commit(0, write.prepareCommit(true, 0));
        List<String> result =
                getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 2]");

        // 2. Update Before: retract
        write.write(GenericRow.ofKind(RowKind.UPDATE_BEFORE, 1, 1, 2, 2));
        commit.commit(1, write.prepareCommit(true, 1));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactly("+I[1, 1, NULL, NULL]");

        // 3. Update After
        write.write(GenericRow.ofKind(RowKind.UPDATE_AFTER, 1, 1, 2, 3));
        commit.commit(2, write.prepareCommit(true, 2));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 3]");

        // 4. Retracts
        write.write(GenericRow.ofKind(RowKind.DELETE, 1, 1, 2, 3));
        commit.commit(3, write.prepareCommit(true, 3));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).isEmpty();

        // 5. Inserts
        write.write(GenericRow.of(1, 1, 2, 2));
        commit.commit(4, write.prepareCommit(true, 4));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 2]");

        write.close();
        commit.close();
    }

    @Test
    public void testAggregationRemoveRecordOnDeleteWithoutInsertData() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT(), DataTypes.INT(), DataTypes.INT(), DataTypes.INT()
                        },
                        new String[] {"pt", "a", "b", "c"});
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set("merge-engine", "aggregation");
                            options.set("aggregation.remove-record-on-delete", "true");
                        },
                        rowType);

        // delete twice to trigger merge when read
        try (StreamTableWrite write = table.newWrite("");
                StreamTableCommit commit = table.newCommit("")) {
            write.write(GenericRow.ofKind(RowKind.DELETE, 1, 1, 2, 2));
            commit.commit(0, write.prepareCommit(true, 0));
            write.write(GenericRow.ofKind(RowKind.DELETE, 1, 1, 2, 2));
            commit.commit(1, write.prepareCommit(true, 1));
        }

        // read auditLog table
        ReadBuilder builder = new AuditLogTable(table).newReadBuilder();
        try (RecordReader<InternalRow> reader =
                builder.newRead().createReader(builder.newScan().plan())) {
            reader.forEachRemaining(
                    row -> {
                        assertThat(row.getString(0).toString()).isEqualTo("-D");
                        assertThat(row.isNullAt(1)).isFalse();
                        assertThat(row.getInt(1)).isEqualTo(1);
                    });
        }
    }

    @Test
    public void testPartialUpdateRemoveRecordOnDelete() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT(), DataTypes.INT(), DataTypes.INT(), DataTypes.INT()
                        },
                        new String[] {"pt", "a", "b", "c"});
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set("merge-engine", "partial-update");
                            options.set("partial-update.remove-record-on-delete", "true");
                        },
                        rowType);
        Function<InternalRow, String> rowToString = row -> internalRowToString(row, rowType);
        SnapshotReader snapshotReader = table.newSnapshotReader();
        TableRead read = table.newRead();
        StreamTableWrite write = table.newWrite("");
        StreamTableCommit commit = table.newCommit("");
        // 1. Inserts
        write.write(GenericRow.of(1, 1, 3, 3));
        write.write(GenericRow.of(1, 1, 1, 1));
        write.write(GenericRow.of(1, 1, 2, 2));
        commit.commit(0, write.prepareCommit(true, 0));
        List<String> result =
                getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 2]");

        // 2. Update Before
        write.write(GenericRow.ofKind(RowKind.UPDATE_BEFORE, 1, 1, 2, 2));
        commit.commit(1, write.prepareCommit(true, 1));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 2]");

        // 3. Update After
        write.write(GenericRow.ofKind(RowKind.UPDATE_AFTER, 1, 1, 2, 3));
        commit.commit(2, write.prepareCommit(true, 2));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 3]");

        // 4. Retracts
        write.write(GenericRow.ofKind(RowKind.DELETE, 1, 1, 2, 3));
        commit.commit(3, write.prepareCommit(true, 3));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).isEmpty();

        // 5. Inserts
        write.write(GenericRow.of(1, 1, 2, 2));
        commit.commit(4, write.prepareCommit(true, 4));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 2]");

        write.close();
        commit.close();
    }

    @Test
    public void testPartialUpdateRemoveRecordOnSequenceGroup() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT()
                        },
                        new String[] {"pt", "a", "b", "seq1", "c", "d", "seq2"});
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set("merge-engine", "partial-update");
                            options.set("fields.seq1.sequence-group", "b");
                            options.set("fields.seq2.sequence-group", "c,d");
                            options.set("partial-update.remove-record-on-sequence-group", "seq2");
                        },
                        rowType);
        FileStoreTable wrongTable =
                createFileStoreTable(
                        options -> {
                            options.set("merge-engine", "partial-update");
                            options.set("fields.seq1.sequence-group", "b");
                            options.set("fields.seq2.sequence-group", "c,d");
                            options.set("partial-update.remove-record-on-sequence-group", "b");
                        },
                        rowType);
        Function<InternalRow, String> rowToString = row -> internalRowToString(row, rowType);

        assertThatThrownBy(() -> wrongTable.newWrite(""))
                .hasMessageContaining(
                        "field 'b' defined in 'partial-update.remove-record-on-sequence-group' option must be part of sequence groups");

        SnapshotReader snapshotReader = table.newSnapshotReader();
        TableRead read = table.newRead();
        StreamTableWrite write = table.newWrite("");
        StreamTableCommit commit = table.newCommit("");
        // 1. Inserts
        write.write(GenericRow.of(1, 1, 10, 1, 20, 20, 1));
        write.write(GenericRow.of(1, 1, 11, 2, 25, 25, 0));
        write.write(GenericRow.of(1, 1, 12, 1, 29, 29, 2));
        commit.commit(0, write.prepareCommit(true, 0));
        List<String> result =
                getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 11, 2, 29, 29, 2]");

        // 2. Update Before
        write.write(GenericRow.ofKind(RowKind.UPDATE_BEFORE, 1, 1, 11, 2, 29, 29, 2));
        commit.commit(1, write.prepareCommit(true, 1));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, NULL, 2, NULL, NULL, 2]");

        // 3. Update After
        write.write(GenericRow.ofKind(RowKind.UPDATE_AFTER, 1, 1, 11, 2, 30, 30, 3));
        commit.commit(2, write.prepareCommit(true, 2));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 11, 2, 30, 30, 3]");

        // 4. Retracts
        write.write(GenericRow.ofKind(RowKind.DELETE, 1, 1, 12, 3, 30, 30, 2));
        commit.commit(3, write.prepareCommit(true, 3));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, NULL, 3, 30, 30, 3]");

        write.write(GenericRow.ofKind(RowKind.DELETE, 1, 1, 12, 2, 30, 31, 5));
        commit.commit(4, write.prepareCommit(true, 4));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).isEmpty();

        // 5. Inserts
        write.write(GenericRow.of(1, 1, 11, 2, 30, 31, 6));
        commit.commit(5, write.prepareCommit(true, 5));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 11, 2, 30, 31, 6]");

        write.close();
        commit.close();
    }

    @Test
    public void testPartialUpdateWithAgg() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT(), DataTypes.INT(), DataTypes.INT(), DataTypes.INT()
                        },
                        new String[] {"pt", "a", "b", "c"});
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set("merge-engine", "partial-update");
                            options.set("fields.a.sequence-group", "c");
                            options.set("fields.c.aggregate-function", "sum");
                        },
                        rowType);
        Function<InternalRow, String> rowToString = row -> internalRowToString(row, rowType);
        SnapshotReader snapshotReader = table.newSnapshotReader();
        TableRead read = table.newRead();
        StreamTableWrite write = table.newWrite("");
        StreamTableCommit commit = table.newCommit("");
        // 1. inserts
        write.write(GenericRow.of(1, 1, 3, 3));
        write.write(GenericRow.of(1, 1, 1, 1));
        write.write(GenericRow.of(1, 1, 2, 2));
        commit.commit(0, write.prepareCommit(true, 0));
        List<String> result =
                getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 6]");

        // 2. Update Before
        write.write(GenericRow.ofKind(RowKind.UPDATE_BEFORE, 1, 1, 2, 2));
        commit.commit(1, write.prepareCommit(true, 1));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 4]");

        // 3. Update After
        write.write(GenericRow.ofKind(RowKind.UPDATE_AFTER, 1, 1, 2, 3));
        commit.commit(2, write.prepareCommit(true, 2));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 7]");

        // 4. Retracts
        write.write(GenericRow.ofKind(RowKind.DELETE, 1, 1, 2, 3));
        commit.commit(3, write.prepareCommit(true, 3));
        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 4]");
        write.close();
        commit.close();
    }

    @Test
    public void testAggMergeFunc() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT(), DataTypes.INT(), DataTypes.INT(), DataTypes.INT()
                        },
                        new String[] {"pt", "a", "b", "c"});
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set("merge-engine", "aggregation");
                            options.set("fields.b.aggregate-function", "sum");
                            options.set("fields.c.aggregate-function", "max");
                            options.set("fields.c.ignore-retract", "true");
                        },
                        rowType);
        Function<InternalRow, String> rowToString = row -> internalRowToString(row, rowType);
        SnapshotReader snapshotReader = table.newSnapshotReader();
        TableRead read = table.newRead();
        StreamTableWrite write = table.newWrite("");
        StreamTableCommit commit = table.newCommit("");

        // 1. inserts
        write.write(GenericRow.of(1, 1, 3, 3));
        write.write(GenericRow.of(1, 1, 1, 1));
        write.write(GenericRow.of(1, 1, 2, 2));
        commit.commit(0, write.prepareCommit(true, 0));

        List<String> result =
                getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 6, 3]");

        // 2. Retracts
        write.write(GenericRow.ofKind(RowKind.UPDATE_BEFORE, 1, 1, 1, 1));
        write.write(GenericRow.ofKind(RowKind.UPDATE_BEFORE, 1, 1, 2, 2));
        write.write(GenericRow.ofKind(RowKind.DELETE, 1, 1, 1, 1));
        commit.commit(1, write.prepareCommit(true, 1));

        result = getResult(read, toSplits(snapshotReader.read().dataSplits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 3]");
        write.close();
        commit.close();
    }

    @Test
    public void testAggMergeFuncNotAllowRetract() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT(), DataTypes.INT(), DataTypes.INT(), DataTypes.INT()
                        },
                        new String[] {"pt", "a", "b", "c"});
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set("merge-engine", "aggregation");
                            options.set("fields.b.aggregate-function", "sum");
                            options.set("fields.c.aggregate-function", "max");
                        },
                        rowType);
        StreamTableWrite write = table.newWrite("");
        write.write(GenericRow.of(1, 1, 3, 3));
        write.write(GenericRow.ofKind(RowKind.UPDATE_BEFORE, 1, 1, 3, 3));
        assertThatThrownBy(() -> write.prepareCommit(true, 0))
                .hasMessageContaining(
                        "Aggregate function 'max' does not support retraction,"
                                + " If you allow this function to ignore retraction messages,"
                                + " you can configure 'fields.${field_name}.ignore-retract'='true'");
    }

    @Test
    public void testAggMergeFuncRetract() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT(), DataTypes.INT(), DataTypes.INT(), DataTypes.INT()
                        },
                        new String[] {"pt", "a", "b", "c"});
        FileStoreTable table =
                createFileStoreTable(
                        options -> options.set("merge-engine", "aggregation"), rowType);
        StreamTableWrite write = table.newWrite("");
        StreamTableCommit commit = table.newCommit("");
        write.write(GenericRow.of(1, 1, 3, 3));
        write.write(GenericRow.ofKind(RowKind.DELETE, 1, 1, null, 3));
        commit.commit(0, write.prepareCommit(true, 0));

        write.close();
        commit.close();

        ReadBuilder readBuilder = table.newReadBuilder();
        BiFunction<InternalRow, Integer, Integer> getField =
                (row, i) -> row.isNullAt(i) ? null : row.getInt(i);
        Function<InternalRow, String> toString =
                row ->
                        getField.apply(row, 0)
                                + "|"
                                + getField.apply(row, 1)
                                + "|"
                                + getField.apply(row, 2)
                                + "|"
                                + getField.apply(row, 3);
        assertThat(
                        getResult(
                                readBuilder.newRead(),
                                readBuilder.newScan().plan().splits(),
                                toString))
                .containsExactly("1|1|3|null");
    }

    @Test
    public void testFullCompactedRead() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(CoreOptions.FULL_COMPACTION_DELTA_COMMITS.key(), "2");
        options.put(CoreOptions.SCAN_MODE.key(), "compacted-full");
        options.put(BUCKET.key(), "1");
        FileStoreTable table = createFileStoreTable().copy(options);

        StreamWriteBuilder writeBuilder = table.newStreamWriteBuilder();
        StreamTableWrite write = writeBuilder.newWrite();
        StreamTableCommit commit = writeBuilder.newCommit();

        write.write(rowData(1, 10, 100L));
        commit.commit(0, write.prepareCommit(true, 0));

        ReadBuilder readBuilder = table.newReadBuilder();
        assertThat(
                        getResult(
                                readBuilder.newRead(),
                                readBuilder.newScan().plan().splits(),
                                BATCH_ROW_TO_STRING))
                .containsExactly("1|10|100|binary|varbinary|mapKey:mapVal|multiset");

        write.write(rowData(1, 10, 200L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.compact(binaryRow(1), 0, true);
        commit.commit(2, write.prepareCommit(true, 2));

        write.write(rowData(1, 10, 300L));
        write.compact(binaryRow(1), 0, true);
        commit.commit(3, write.prepareCommit(true, 3));

        write.close();
        commit.close();

        assertThat(
                        getResult(
                                readBuilder.newRead(),
                                readBuilder.newScan().plan().splits(),
                                BATCH_ROW_TO_STRING))
                .containsExactly("1|10|200|binary|varbinary|mapKey:mapVal|multiset");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testFileMonitorTableScan(boolean dvEnabled) throws Exception {
        FileStoreTable table =
                createFileStoreTable(options -> options.set(DELETION_VECTORS_ENABLED, dvEnabled));

        FileMonitorTable monitorTable = new FileMonitorTable(table);
        ReadBuilder readBuilder = monitorTable.newReadBuilder();
        StreamTableScan scan = readBuilder.newStreamScan();
        TableRead read = readBuilder.newRead();
        IOManager ioManager = IOManager.create(tempDir.toString());

        // 1. first write

        BatchWriteBuilder writeBuilder = table.newBatchWriteBuilder();
        BatchTableWrite write = writeBuilder.newWrite();
        write.withIOManager(ioManager);
        BatchTableCommit commit = writeBuilder.newCommit();

        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 11, 101L));
        commit.commit(write.prepareCommit());

        List<InternalRow> results = new ArrayList<>();
        read.createReader(scan.plan()).forEachRemaining(results::add);
        read.createReader(scan.plan()).forEachRemaining(results::add);
        assertThat(results).hasSize(1);
        FileMonitorTable.FileChange change = FileMonitorTable.toFileChange(results.get(0));
        assertThat(change.beforeFiles()).hasSize(0);
        assertThat(change.dataFiles()).hasSize(1);
        results.clear();

        // 2. second write and compact

        write.close();
        commit.close();
        writeBuilder = table.newBatchWriteBuilder();
        write = writeBuilder.newWrite();
        write.withIOManager(ioManager);
        commit = writeBuilder.newCommit();
        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 11, 101L));
        write.compact(binaryRow(1), 0, true);
        commit.commit(write.prepareCommit());

        // 2.1 read add file

        read.createReader(scan.plan()).forEachRemaining(results::add);
        assertThat(results).hasSize(1);
        change = FileMonitorTable.toFileChange(results.get(0));
        assertThat(change.beforeFiles()).hasSize(0);
        assertThat(change.dataFiles()).hasSize(1);
        results.clear();

        // 2.2 read compact

        read.createReader(scan.plan()).forEachRemaining(results::add);
        assertThat(results).hasSize(1);
        change = FileMonitorTable.toFileChange(results.get(0));
        assertThat(change.beforeFiles()).hasSize(2);
        assertThat(change.dataFiles()).hasSize(1);
        results.clear();

        // 3 overwrite
        write.close();
        commit.close();
        writeBuilder = table.newBatchWriteBuilder().withOverwrite();
        write = writeBuilder.newWrite();
        write.withIOManager(ioManager);
        commit = writeBuilder.newCommit();
        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 11, 101L));
        commit.commit(write.prepareCommit());

        read.createReader(scan.plan()).forEachRemaining(results::add);
        assertThat(results).hasSize(1);
        change = FileMonitorTable.toFileChange(results.get(0));
        assertThat(change.beforeFiles()).hasSize(1);
        assertThat(change.dataFiles()).hasSize(1);

        write.close();
        commit.close();
    }

    @Test
    public void testReadOptimizedTable() throws Exception {
        // let max level has many files
        FileStoreTable table =
                createFileStoreTable(options -> options.set(TARGET_FILE_SIZE, new MemorySize(1)));
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowDataWithKind(RowKind.INSERT, 1, 10, 100L));
        write.write(rowDataWithKind(RowKind.INSERT, 2, 20, 200L));
        commit.commit(0, write.prepareCommit(true, 0));
        write.write(rowDataWithKind(RowKind.INSERT, 1, 11, 110L));
        write.write(rowDataWithKind(RowKind.INSERT, 2, 20, 201L));
        commit.commit(1, write.prepareCommit(true, 1));

        ReadOptimizedTable roTable = new ReadOptimizedTable(table);
        Function<InternalRow, String> rowDataToString =
                row ->
                        internalRowToString(
                                row,
                                DataTypes.ROW(
                                        DataTypes.INT(), DataTypes.INT(), DataTypes.BIGINT()));

        TableRead read = roTable.newRead();
        List<String> result = getResult(read, roTable.newScan().plan().splits(), rowDataToString);
        assertThat(result).isEmpty();

        write.compact(binaryRow(1), 0, true);
        commit.commit(2, write.prepareCommit(true, 2));

        result = getResult(read, roTable.newScan().plan().splits(), rowDataToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 10, 100]", "+I[1, 11, 110]");

        write.write(rowDataWithKind(RowKind.INSERT, 1, 10, 101L));
        write.write(rowDataWithKind(RowKind.INSERT, 2, 21, 210L));
        write.compact(binaryRow(2), 0, true);
        commit.commit(3, write.prepareCommit(true, 3));

        result = getResult(read, roTable.newScan().plan().splits(), rowDataToString);
        assertThat(result)
                .containsExactlyInAnyOrder(
                        "+I[1, 10, 100]", "+I[1, 11, 110]", "+I[2, 20, 201]", "+I[2, 21, 210]");

        // test value filter on ro table

        PredicateBuilder builder = new PredicateBuilder(ROW_TYPE);
        Predicate filter = builder.equal(0, 2);

        // no value filter, return two files
        List<Split> splits = roTable.newScan().withFilter(filter).plan().splits();
        assertThat(splits).hasSize(1);
        assertThat(splits.get(0).convertToRawFiles().get()).hasSize(2);

        // with value filter, return one files
        filter = and(filter, builder.equal(2, 210L));
        splits = roTable.newScan().withFilter(filter).plan().splits();
        assertThat(splits).hasSize(1);
        assertThat(splits.get(0).convertToRawFiles().get()).hasSize(1);

        write.close();
        commit.close();
    }

    @Test
    public void testStreamingReadOptimizedTable() throws Exception {
        FileStoreTable table =
                createFileStoreTable(options -> options.set(TARGET_FILE_SIZE, new MemorySize(1)));
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowDataWithKind(RowKind.INSERT, 1, 10, 100L));
        write.compact(binaryRow(1), 0, true);
        commit.commit(0, write.prepareCommit(true, 0));

        ReadOptimizedTable roTable = new ReadOptimizedTable(table);
        Function<InternalRow, String> rowDataToString =
                row ->
                        internalRowToString(
                                row,
                                DataTypes.ROW(
                                        DataTypes.INT(), DataTypes.INT(), DataTypes.BIGINT()));

        TableRead read = roTable.newRead();
        List<String> result = getResult(read, roTable.newScan().plan().splits(), rowDataToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 10, 100]");

        assertThatThrownBy(roTable::newStreamScan)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Unsupported streaming scan for read optimized table");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testReadDeletionVectorTable(boolean statsDenseStore) throws Exception {
        Consumer<Options> optionsSetter =
                options -> {
                    // let level has many files
                    options.set(TARGET_FILE_SIZE, new MemorySize(1));
                    options.set(DELETION_VECTORS_ENABLED, true);

                    if (statsDenseStore) {
                        options.set(CoreOptions.METADATA_STATS_MODE, "none");
                        options.set("fields.b.stats-mode", "full");
                    }
                };

        FileStoreTable table = createFileStoreTable(optionsSetter);
        StreamTableWrite write = table.newWrite(commitUser);
        IOManager ioManager = IOManager.create(tablePath.toString());
        write.withIOManager(ioManager);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowDataWithKind(RowKind.INSERT, 1, 10, 100L));
        write.write(rowDataWithKind(RowKind.INSERT, 2, 20, 100L));
        commit.commit(0, write.prepareCommit(true, 0));
        write.write(rowDataWithKind(RowKind.INSERT, 1, 20, 200L));
        commit.commit(1, write.prepareCommit(true, 1));
        write.write(rowDataWithKind(RowKind.INSERT, 1, 10, 110L));
        commit.commit(2, write.prepareCommit(true, 2));

        // test result
        Function<InternalRow, String> rowDataToString =
                row ->
                        internalRowToString(
                                row,
                                DataTypes.ROW(
                                        DataTypes.INT(), DataTypes.INT(), DataTypes.BIGINT()));
        List<String> result =
                getResult(table.newRead(), table.newScan().plan().splits(), rowDataToString);
        assertThat(result)
                .containsExactlyInAnyOrder("+I[1, 10, 110]", "+I[1, 20, 200]", "+I[2, 20, 100]");

        // file layout
        // pt 1
        // level 4 (1, 10, 110L)
        // level 5 (1, 10, 100L), (1, 20, 200L)
        // pt 2
        // level 5 (2, 20, 100L)

        // test filter on dv table
        // with key filter pt = 1
        PredicateBuilder builder = new PredicateBuilder(ROW_TYPE);
        Predicate filter = builder.equal(0, 1);
        int files =
                table.newScan().withFilter(filter).plan().splits().stream()
                        .mapToInt(split -> ((DataSplit) split).dataFiles().size())
                        .sum();
        assertThat(files).isEqualTo(3);

        // with key filter pt = 1 and value filter idx2 = 110L
        filter = and(filter, builder.equal(2, 110L));
        files =
                table.newScan().withFilter(filter).plan().splits().stream()
                        .mapToInt(split -> ((DataSplit) split).dataFiles().size())
                        .sum();
        assertThat(files).isEqualTo(1);

        write.close();
        commit.close();
    }

    @Test
    public void testReadWithRawConvertibleSplits() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set(FILE_FORMAT, CoreOptions.FILE_FORMAT_AVRO);
                            options.set(SOURCE_SPLIT_OPEN_FILE_COST, MemorySize.ofBytes(1));
                            options.set(SOURCE_SPLIT_TARGET_SIZE, MemorySize.ofKibiBytes(5));
                        });
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        // file1
        write.write(rowDataWithKind(RowKind.INSERT, 1, 0, 0L));
        commit.commit(0, write.prepareCommit(true, 0));

        // file2
        for (int i = 1; i < 1000; i++) {
            write.write(rowDataWithKind(RowKind.INSERT, 1, i, (long) i));
        }
        commit.commit(1, write.prepareCommit(true, 1));

        // file3
        write.write(rowDataWithKind(RowKind.INSERT, 1, 1000, 1000L));
        commit.commit(2, write.prepareCommit(true, 2));

        // file4
        write.write(rowDataWithKind(RowKind.INSERT, 1, 1000, 1001L));
        commit.commit(3, write.prepareCommit(true, 3));

        // split1[file1], split2[file2], split3[file3, file4]
        List<DataSplit> dataSplits = table.newSnapshotReader().read().dataSplits();
        assertThat(dataSplits).hasSize(3);
        assertThat(dataSplits.get(0).dataFiles()).hasSize(1);
        assertThat(dataSplits.get(0).convertToRawFiles()).isPresent();
        assertThat(dataSplits.get(1).dataFiles()).hasSize(1);
        assertThat(dataSplits.get(1).convertToRawFiles()).isPresent();
        assertThat(dataSplits.get(2).dataFiles()).hasSize(2);
        assertThat(dataSplits.get(2).convertToRawFiles()).isEmpty();

        Function<InternalRow, String> rowDataToString =
                row ->
                        internalRowToString(
                                row,
                                DataTypes.ROW(
                                        DataTypes.INT(), DataTypes.INT(), DataTypes.BIGINT()));
        List<String> result =
                getResult(table.newRead(), table.newScan().plan().splits(), rowDataToString);
        assertThat(result.size()).isEqualTo(1001);
        for (int i = 0; i < 1000; i++) {
            assertThat(result.get(i)).isEqualTo(String.format("+I[1, %s, %s]", i, i));
        }
        assertThat(result.get(1000)).isEqualTo("+I[1, 1000, 1001]");

        // compact all files
        write.compact(binaryRow(1), 0, true);
        commit.commit(4, write.prepareCommit(true, 4));

        // split1[compactedFile]
        dataSplits = table.newSnapshotReader().read().dataSplits();
        assertThat(dataSplits).hasSize(1);
        assertThat(dataSplits.get(0).dataFiles()).hasSize(1);
        assertThat(dataSplits.get(0).convertToRawFiles()).isPresent();

        result = getResult(table.newRead(), table.newScan().plan().splits(), rowDataToString);
        assertThat(result.size()).isEqualTo(1001);
        for (int i = 0; i < 1000; i++) {
            assertThat(result.get(i)).isEqualTo(String.format("+I[1, %s, %s]", i, i));
        }
        assertThat(result.get(1000)).isEqualTo("+I[1, 1000, 1001]");

        write.close();
        commit.close();
    }

    @Test
    public void testTableQueryForLookup() throws Exception {
        FileStoreTable table =
                createFileStoreTable(options -> options.set(CHANGELOG_PRODUCER, LOOKUP));
        innerTestTableQuery(table);
    }

    @Test
    public void testTableQueryForLookupLocalSortFile() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set(CHANGELOG_PRODUCER, LOOKUP);
                            options.set(LOOKUP_LOCAL_FILE_TYPE, LookupLocalFileType.SORT);
                        });
        innerTestTableQuery(table);
    }

    @Test
    public void testTableQueryForNormal() throws Exception {
        FileStoreTable table = createFileStoreTable();
        innerTestTableQuery(table);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testLookupWithDropDelete(boolean specificConfig) throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(CHANGELOG_PRODUCER, LOOKUP);
                            conf.set("num-levels", "2");
                            if (specificConfig) {
                                conf.set(FILE_FORMAT, "parquet");
                                conf.set(METADATA_STATS_MODE, "full");
                                conf.set(CHANGELOG_FILE_FORMAT, "avro");
                                conf.set(CHANGELOG_FILE_STATS_MODE, "none");
                            }
                        });
        IOManager ioManager = IOManager.create(tablePath.toString());
        StreamTableWrite write = table.newWrite(commitUser).withIOManager(ioManager);
        StreamTableCommit commit = table.newCommit(commitUser);
        write.write(rowData(1, 1, 100L));
        write.write(rowData(1, 2, 200L));
        commit.commit(0, write.prepareCommit(true, 0));

        // set num-levels = 2 to make sure that this delete can trigger compaction with drop delete
        write.write(rowDataWithKind(RowKind.DELETE, 1, 1, 100L));
        commit.commit(1, write.prepareCommit(true, 0));
        write.close();
        commit.close();

        Snapshot latestSnapshot = table.newSnapshotReader().snapshotManager().latestSnapshot();
        assertThat(latestSnapshot.commitKind()).isEqualTo(COMPACT);
        assertThat(latestSnapshot.totalRecordCount()).isEqualTo(1);

        assertThat(
                        getResult(
                                table.newRead(),
                                toSplits(table.newSnapshotReader().read().dataSplits()),
                                binaryRow(1),
                                0,
                                BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "1|2|200|binary|varbinary|mapKey:mapVal|multiset"));

        if (specificConfig) {
            ManifestFileMeta manifest =
                    table.manifestListReader().read(latestSnapshot.changelogManifestList()).get(0);
            DataFileMeta file = table.manifestFileReader().read(manifest.fileName()).get(0).file();
            assertThat(file.fileName()).endsWith(".avro");
            assertThat(file.valueStats().minValues().getFieldCount()).isEqualTo(0);
        }
    }

    @ParameterizedTest(name = "changelog-producer = {0}")
    @ValueSource(strings = {"none", "input"})
    public void testRollbackToTagWithChangelogDecoupled(String changelogProducer) throws Exception {
        int commitTimes = ThreadLocalRandom.current().nextInt(100) + 6;
        FileStoreTable table =
                createFileStoreTable(
                        options ->
                                options.setString(
                                        CoreOptions.CHANGELOG_PRODUCER.key(), changelogProducer));
        table = prepareRollbackTable(commitTimes, table);

        int t1 = 1;
        int t2 = commitTimes - 3;
        int t3 = commitTimes - 1;
        table.createTag("test1", t1);
        table.createTag("test2", t2);
        table.createTag("test3", t3);

        // expire snapshots
        Options options = new Options();
        options.set(SNAPSHOT_EXPIRE_LIMIT, Integer.MAX_VALUE);
        // -------------changelog--------- |-------snapshot------|
        // s(1)test1 -------- s(c - 3)test2 --- s(c - 1) test3
        options.set(CoreOptions.SNAPSHOT_NUM_RETAINED_MIN, 2);
        options.set(CoreOptions.SNAPSHOT_NUM_RETAINED_MAX, 2);
        options.set(SNAPSHOT_EXPIRE_LIMIT, Integer.MAX_VALUE);
        options.set(CHANGELOG_NUM_RETAINED_MIN, 5);
        options.set(CHANGELOG_NUM_RETAINED_MAX, 5);
        table.copy(options.toMap()).newCommit("").expireSnapshots();

        table.rollbackTo("test3");
        assertReadChangelog(t3, table);

        table.rollbackTo("test2");
        assertReadChangelog(t2, table);

        table.rollbackTo("test1");

        assertRollbackTo(table, singletonList(1L), 1, 1, singletonList("test1"));
        ChangelogManager changelogManager = table.changelogManager();
        assertThat(changelogManager.earliestLongLivedChangelogId()).isNull();
        assertThat(changelogManager.latestLongLivedChangelogId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(CoreOptions.MergeEngine.class)
    public void testForceLookupCompaction(CoreOptions.MergeEngine mergeEngine) throws Exception {
        Map<MergeEngine, Pair<Long, Long>> testData = new HashMap<>();
        testData.put(DEDUPLICATE, Pair.of(50L, 100L));
        testData.put(PARTIAL_UPDATE, Pair.of(null, 100L));
        testData.put(AGGREGATE, Pair.of(30L, 70L));
        testData.put(FIRST_ROW, Pair.of(100L, 70L));

        Pair<Long, Long> currentTestData = testData.get(mergeEngine);
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set(CoreOptions.FORCE_LOOKUP, true);
                            options.set(MERGE_ENGINE, mergeEngine);
                            if (mergeEngine == AGGREGATE) {
                                options.set("fields.b.aggregate-function", "sum");
                            }
                        });
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);
        write.withIOManager(IOManager.create(tempDir.toString()));

        // write data
        write.write(rowData(1, 10, currentTestData.getLeft()));
        commit.commit(1, write.prepareCommit(true, 1));
        assertThat(table.snapshotManager().snapshotCount()).isEqualTo(2L);

        write.write(rowData(1, 10, currentTestData.getRight()));
        commit.commit(0, write.prepareCommit(true, 0));
        write.close();
        commit.close();
        assertThat(table.snapshotManager().snapshotCount()).isEqualTo(4L);
        assertThat(table.snapshotManager().latestSnapshot())
                .matches(snapshot -> snapshot.commitKind() == COMPACT);

        // 3 data files + bucket-0 directory
        List<java.nio.file.Path> files =
                Files.walk(new File(tablePath.toUri().getPath(), "pt=1/bucket-0").toPath())
                        .collect(Collectors.toList());
        assertThat(files.size()).isEqualTo(4);

        // 2 data files compact into 1 file
        FileStoreScan scan = table.store().newScan().withKind(ScanMode.DELTA);
        assertThat(scan.plan().files(FileKind.ADD).size()).isEqualTo(1);
        assertThat(scan.plan().files(FileKind.DELETE).size()).isEqualTo(2);

        // check result
        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "1|10|100|binary|varbinary|mapKey:mapVal|multiset"));
    }

    private void assertReadChangelog(int id, FileStoreTable table) throws Exception {
        // read the changelog at #{id}
        table = table.copy(singletonMap(CoreOptions.SCAN_SNAPSHOT_ID.key(), String.valueOf(id)));
        ReadBuilder readBuilder = table.newReadBuilder();
        StreamTableScan scan = readBuilder.newStreamScan();

        // skip the NextSnapshot
        scan.plan();
        List<String> results =
                getResult(readBuilder.newRead(), scan.plan().splits(), BATCH_ROW_TO_STRING);
        if (id == 1) {
            assertThat(results).isEmpty();
        } else {
            assertThat(results)
                    .containsExactlyInAnyOrder(
                            String.format(
                                    "%s|%s|%s|binary|varbinary|mapKey:mapVal|multiset",
                                    id - 1, (id - 1) * 10, (id - 1) * 100));
        }
    }

    private void innerTestTableQuery(FileStoreTable table) throws Exception {
        IOManager ioManager = IOManager.create(tablePath.toString());
        StreamTableWrite write = table.newWrite(commitUser).withIOManager(ioManager);
        StreamTableCommit commit = table.newCommit(commitUser);

        // first write

        write.write(rowData(1, 10, 100L));
        List<CommitMessage> commitMessages1 = write.prepareCommit(true, 0);
        commit.commit(0, commitMessages1);

        LocalTableQuery query = table.newLocalTableQuery();
        query.withIOManager(ioManager);

        refreshTableService(query, commitMessages1);
        InternalRow value = query.lookup(row(1), 0, row(10));
        assertThat(value).isNotNull();
        assertThat(BATCH_ROW_TO_STRING.apply(value))
                .isEqualTo("1|10|100|binary|varbinary|mapKey:mapVal|multiset");

        // second write

        write.write(rowData(1, 10, 200L));
        write.write(rowData(3, 10, 300L));
        List<CommitMessage> commitMessages2 = write.prepareCommit(true, 0);
        commit.commit(0, commitMessages2);

        refreshTableService(query, commitMessages2);

        value = query.lookup(row(1), 0, row(10));
        assertThat(value).isNotNull();
        assertThat(BATCH_ROW_TO_STRING.apply(value))
                .isEqualTo("1|10|200|binary|varbinary|mapKey:mapVal|multiset");

        value = query.lookup(row(3), 0, row(10));
        assertThat(value).isNotNull();
        assertThat(BATCH_ROW_TO_STRING.apply(value))
                .isEqualTo("3|10|300|binary|varbinary|mapKey:mapVal|multiset");

        // query non value

        value = query.lookup(row(1), 0, row(20));
        assertThat(value).isNull();

        // projection

        query.close();
        query.withValueProjection(new int[] {2, 1, 0});
        refreshTableService(query, commitMessages1);
        refreshTableService(query, commitMessages2);
        value = query.lookup(row(1), 0, row(10));
        assertThat(value).isNotNull();
        Function<InternalRow, String> projectToString =
                rowData -> rowData.getLong(0) + "|" + rowData.getInt(1) + "|" + rowData.getInt(2);
        assertThat(projectToString.apply(value)).isEqualTo("200|10|1");

        query.close();
        write.close();
        commit.close();
    }

    private void refreshTableService(LocalTableQuery query, List<CommitMessage> commitMessages) {
        for (CommitMessage m : commitMessages) {
            CommitMessageImpl msg = (CommitMessageImpl) m;
            query.refreshFiles(
                    msg.partition(),
                    msg.bucket(),
                    Collections.emptyList(),
                    msg.newFilesIncrement().newFiles());
            query.refreshFiles(
                    msg.partition(),
                    msg.bucket(),
                    msg.compactIncrement().compactBefore(),
                    msg.compactIncrement().compactAfter());
        }
    }

    @Test
    public void writeMultiplePartitions() throws Exception {
        testWritePreemptMemory(false);
    }

    @Test
    public void writeSinglePartition() throws Exception {
        testWritePreemptMemory(true);
    }

    private void testWritePreemptMemory(boolean singlePartition) throws Exception {
        // write
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            // Run with minimal memory to ensure a more intense preempt
                            // Currently a writer needs at least one page
                            int pages = 10;
                            options.set(
                                    CoreOptions.WRITE_BUFFER_SIZE, new MemorySize(pages * 1024));
                            options.set(CoreOptions.PAGE_SIZE, new MemorySize(1024));
                        });
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);
        Random random = new Random();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            GenericRow row = rowData(singlePartition ? 0 : random.nextInt(5), i, i * 10L);
            write.write(row);
            expected.add(BATCH_ROW_TO_STRING.apply(row));
        }
        commit.commit(0, write.prepareCommit(true, 0));
        write.close();
        commit.close();

        // read
        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        TableRead read = table.newRead();
        List<String> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.addAll(getResult(read, splits, binaryRow(i), 0, BATCH_ROW_TO_STRING));
        }
        assertThat(results).containsExactlyInAnyOrder(expected.toArray(new String[0]));
    }

    @Override
    protected FileStoreTable createFileStoreTable(Consumer<Options> configure, RowType rowType)
            throws Exception {
        Options options = new Options();
        options.set(CoreOptions.PATH, tablePath.toString());
        options.set(BUCKET, 1);
        configure.accept(options);
        TableSchema tableSchema =
                SchemaUtils.forceCommit(
                        new SchemaManager(LocalFileIO.create(), tablePath),
                        new Schema(
                                rowType.getFields(),
                                Collections.singletonList("pt"),
                                Arrays.asList("pt", "a"),
                                options.toMap(),
                                ""));
        return new PrimaryKeyFileStoreTable(FileIOFinder.find(tablePath), tablePath, tableSchema);
    }
}
