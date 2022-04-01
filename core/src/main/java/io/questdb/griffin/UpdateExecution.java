/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.MessageBus;
import io.questdb.cairo.*;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.*;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryCM;
import io.questdb.cairo.vm.api.MemoryCMARW;
import io.questdb.cairo.vm.api.MemoryCMR;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.Sequence;
import io.questdb.std.*;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;
import io.questdb.tasks.ColumnVersionPurgeTask;

import java.io.Closeable;

import static io.questdb.cairo.ColumnType.isVariableLength;
import static io.questdb.cairo.TableUtils.dFile;
import static io.questdb.cairo.TableUtils.iFile;

public class UpdateExecution implements Closeable {
    private static final Log LOG = LogFactory.getLog(UpdateExecution.class);
    private final FilesFacade ff;
    private final int rootLen;
    private final IntList updateColumnIndexes = new IntList();
    private final ObjList<MemoryCMR> baseMemory = new ObjList<>();
    private final ObjList<MemoryCMARW> updateMemory = new ObjList<>();
    private final long dataAppendPageSize;
    private final long fileOpenOpts;
    private final StringSink charSink = new StringSink();
    private final LongList cleanupColumnVersions = new LongList();
    private final LongList cleanupColumnVersionsAsync = new LongList();
    private final MessageBus messageBus;
    private final CairoConfiguration configuration;
    private final RebuildIndex rebuildIndex;
    private Path path;

    public UpdateExecution(CairoConfiguration configuration, MessageBus messageBus) {
        this.messageBus = messageBus;
        this.configuration = configuration;
        rebuildIndex = new RebuildIndex();
        ff = configuration.getFilesFacade();
        path = new Path().of(configuration.getRoot());
        rootLen = path.length();
        dataAppendPageSize = configuration.getDataAppendPageSize();
        fileOpenOpts = configuration.getWriterFileOpenOpts();
    }

    @Override
    public void close() {
        path = Misc.free(path);
        rebuildIndex.close();
    }

    public long executeUpdate(TableWriter tableWriter, UpdateStatement updateStatement, SqlExecutionContext executionContext) throws SqlException {
        cleanupColumnVersions.clear();

        final String tableName = tableWriter.getTableName();
        if (tableWriter.inTransaction()) {
            LOG.info().$("committing current transaction before UPDATE execution [table=").$(tableName).I$();
            tableWriter.commit();
        }

        TableWriterMetadata writerMetadata = tableWriter.getMetadata();

        // Check that table structure hasn't changed between planning and executing the UPDATE
        if (writerMetadata.getId() != updateStatement.getTableId() || tableWriter.getStructureVersion() != updateStatement.getTableVersion()) {
            throw ReaderOutOfDateException.of(tableName);
        }

        // Prepare for update, select the rows to be updated
        final RecordCursorFactory updateStatementDataCursorFactory = updateStatement.prepareForUpdate();

        RecordMetadata updateMetadata = updateStatementDataCursorFactory.getMetadata();
        int updateColumnCount = updateMetadata.getColumnCount();

        // Build index column map from table to update to values returned from the update statement row cursors
        updateColumnIndexes.clear();
        for (int i = 0; i < updateColumnCount; i++) {
            CharSequence columnName = updateMetadata.getColumnName(i);
            int tableColumnIndex = writerMetadata.getColumnIndex(columnName);
            assert tableColumnIndex >= 0;
            updateColumnIndexes.add(tableColumnIndex);
        }

        // Create update memory list of all columns to be updated
        initUpdateMemory(writerMetadata, updateColumnCount);

        // Start execution frame by frame
        // Partition to update
        long currentPartitionIndex = -1L;
        long rowsUpdated = 0;

        // Row by row updates for now
        // This should happen parallel per file (partition and column)
        try (RecordCursor recordCursor = updateStatementDataCursorFactory.getCursor(executionContext)) {
            Record masterRecord = recordCursor.getRecord();

            long startPartitionRowId = 0;
            long lastRowId = -1;
            while (recordCursor.hasNext()) {
                long rowId = masterRecord.getUpdateRowId();

                // Some joins expand results set and returns same row multiple times
                if (rowId == lastRowId) {
                    continue;
                }
                lastRowId = rowId;

                int partitionIndex = Rows.toPartitionIndex(rowId);
                if (partitionIndex != currentPartitionIndex) {
                    if (currentPartitionIndex > -1) {
                        long partitionSize = tableWriter.getPartitionSize((int) currentPartitionIndex);
                        copyColumnValues(
                                writerMetadata,
                                updateColumnIndexes,
                                updateColumnCount,
                                baseMemory,
                                updateMemory,
                                startPartitionRowId,
                                partitionSize);
                    }

                    openPartitionColumnsForRead(tableWriter, baseMemory, partitionIndex, updateColumnIndexes);
                    openPartitionColumnsForWrite(tableWriter, updateMemory, partitionIndex, updateColumnIndexes);
                    currentPartitionIndex = partitionIndex;
                    startPartitionRowId = 0;
                }

                long partitionRowId = Rows.toLocalRowID(rowId);
                updateColumnValues(
                        tableWriter,
                        writerMetadata,
                        updateColumnIndexes,
                        updateColumnCount,
                        baseMemory,
                        updateMemory,
                        startPartitionRowId,
                        partitionRowId,
                        masterRecord);

                startPartitionRowId = ++partitionRowId;
                rowsUpdated++;
            }

            if (currentPartitionIndex > -1) {
                long partitionSize = tableWriter.getPartitionSize((int) currentPartitionIndex);
                copyColumnValues(
                        writerMetadata,
                        updateColumnIndexes,
                        updateColumnCount,
                        baseMemory,
                        updateMemory,
                        startPartitionRowId,
                        partitionSize);
            }
        } finally {
            Misc.freeObjList(baseMemory);
            Misc.freeObjList(updateMemory);
            baseMemory.clear();
            updateMemory.clear();
        }
        if (currentPartitionIndex > -1) {
            tableWriter.commit();
            tableWriter.openLastPartition();
            purgeOldColumnVersions(tableWriter, updateColumnIndexes, ff);
        }

        rebuildIndexes(tableName, writerMetadata);

        return rowsUpdated;
    }

    private void rebuildIndexes(String tableName, TableWriterMetadata writerMetadata) {
        int pathTrimToLen = path.length();
        rebuildIndex.of(path.concat(tableName), configuration, false);
        for (int i = 0, n = updateColumnIndexes.size(); i < n; i++) {
            int columnIndex = updateColumnIndexes.get(i);
            if (writerMetadata.isColumnIndexed(columnIndex)) {
                CharSequence colName = writerMetadata.getColumnName(columnIndex);
                rebuildIndex.rebuildColumn(colName);
            }
        }
        rebuildIndex.clear();
        path.trimTo(pathTrimToLen);
    }

    private void openPartitionColumns(TableWriter tableWriter, ObjList<? extends MemoryCM> memList, int partitionIndex, IntList updateColumnIndexes, boolean forWrite) {
        long partitionTimestamp = tableWriter.getPartitionTimestamp(partitionIndex);
        long partitionNameTxn = tableWriter.getPartitionNameTxn(partitionIndex);
        RecordMetadata metadata = tableWriter.getMetadata();
        try {
            final String tableName = tableWriter.getTableName();
            path.concat(tableName);
            TableUtils.setPathForPartition(path, tableWriter.getPartitionBy(), partitionTimestamp, false);
            TableUtils.txnPartitionConditionally(path, partitionNameTxn);
            int pathTrimToLen = path.length();

            for (int i = 0, n = updateColumnIndexes.size(); i < n; i++) {
                int columnIndex = updateColumnIndexes.get(i);
                CharSequence name = metadata.getColumnName(columnIndex);
                int columnType = metadata.getColumnType(columnIndex);

                if (forWrite) {
                    long existingVersion = tableWriter.getColumnNameTxn(partitionTimestamp, columnIndex);
                    tableWriter.upsertColumnVersion(partitionTimestamp, columnIndex);
                    cleanupColumnVersions.add(columnIndex, existingVersion, partitionTimestamp, partitionNameTxn);
                    LOG.debug().$("updating column [table=").$(tableName)
                            .$(", column=").$(name)
                            .$(", oldVersion=").$(existingVersion)
                            .$(", newVersion=").$(tableWriter.getTxn())
                            .$(", partition=").$ts(partitionTimestamp)
                            .$(", partitionNameTxn=").$(partitionNameTxn)
                            .I$();
                }

                long columnNameTxn = tableWriter.getColumnNameTxn(partitionTimestamp, columnIndex);
                if (isVariableLength(columnType)) {
                    MemoryCMR colMemIndex = (MemoryCMR) memList.get(2 * i);
                    colMemIndex.close();
                    colMemIndex.of(
                            ff,
                            iFile(path.trimTo(pathTrimToLen), name, columnNameTxn),
                            dataAppendPageSize,
                            -1,
                            MemoryTag.MMAP_UPDATE,
                            fileOpenOpts
                    );
                    MemoryCMR colMemVar = (MemoryCMR) memList.get(2 * i + 1);
                    colMemVar.close();
                    colMemVar.of(
                            ff,
                            dFile(path.trimTo(pathTrimToLen), name, columnNameTxn),
                            dataAppendPageSize,
                            -1,
                            MemoryTag.MMAP_UPDATE,
                            fileOpenOpts
                    );
                } else {
                    MemoryCMR colMem = (MemoryCMR) memList.get(2 * i);
                    colMem.close();
                    colMem.of(
                            ff,
                            dFile(path.trimTo(pathTrimToLen), name, columnNameTxn),
                            dataAppendPageSize,
                            -1,
                            MemoryTag.MMAP_UPDATE,
                            fileOpenOpts
                    );
                }
                if (forWrite) {
                    if (isVariableLength(columnType)) {
                        ((MemoryCMARW) memList.get(2 * i)).putLong(0);
                    }
                }
            }
        } finally {
            path.trimTo(rootLen);
        }
    }

    private void purgeColumnVersionAsync(
            String tableName,
            CharSequence columnName,
            int tableId,
            int columnType,
            int partitionBy,
            long updatedTxn,
            LongList columnVersions
    ) {
        Sequence pubSeq = messageBus.getColumnVersionPurgePubSeq();
        while (true) {
            long cursor = pubSeq.next();
            if (cursor > -1L) {
                ColumnVersionPurgeTask task = messageBus.getColumnVersionPurgeQueue().get(cursor);
                task.of(tableName, columnName, tableId, columnType, partitionBy, updatedTxn, columnVersions);
                pubSeq.done(cursor);
                return;
            } else if (cursor == -1L) {
                // Queue overflow
                LOG.error().$("cannot schedule to purge updated column version async [table=").$(tableName)
                        .$(", column=").$(columnName)
                        .$(", lastTxn=").$(updatedTxn)
                        .I$();
                return;
            }
        }
    }

    private void copyColumnValues(
            TableWriterMetadata metadata,
            IntList updateColumnIndexes,
            int columnCount,
            ObjList<MemoryCMR> baseMemory,
            ObjList<MemoryCMARW> updateMemory,
            long startPartitionRowId,
            long partitionSize
    ) {
        for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
            MemoryCMR baseFixedColumnFile = baseMemory.get(2 * columnIndex);
            MemoryCMARW updatedFixedColumnFile = updateMemory.get(2 * columnIndex);
            MemoryCMR baseVariableColumnFile = baseMemory.get(2 * columnIndex + 1);
            MemoryCMARW updatedVariableColumnFile = updateMemory.get(2 * columnIndex + 1);

            int columnType = metadata.getColumnType(updateColumnIndexes.get(columnIndex));
            int typeSize = getFixedColumnSize(columnType);
            copyRecords(startPartitionRowId, partitionSize, baseFixedColumnFile, updatedFixedColumnFile, baseVariableColumnFile, updatedVariableColumnFile, columnType, typeSize);
        }
    }

    private int getFixedColumnSize(int columnType) {
        int typeSize = ColumnType.sizeOf(columnType);
        if (isVariableLength(columnType)) {
            typeSize = Long.BYTES;
        }
        return typeSize;
    }

    private void copyRecords(
            long fromRowId,
            long toRowId,
            MemoryCMR baseFixedColumnFile,
            MemoryCMARW updatedFixedColumnFile,
            MemoryCMR baseVariableColumnFile,
            MemoryCMARW updatedVariableColumnFile,
            int columnType,
            int typeSize) {

        long address = baseFixedColumnFile.addressOf(fromRowId * typeSize);
        switch (ColumnType.tagOf(columnType)) {
            case ColumnType.STRING:
            case ColumnType.BINARY:
                long varStartOffset = baseFixedColumnFile.getLong(fromRowId * Long.BYTES);
                long varEndOffset = baseFixedColumnFile.getLong((toRowId) * Long.BYTES);
                long varAddress = baseVariableColumnFile.addressOf(varStartOffset);
                long copyToOffset = updatedVariableColumnFile.getAppendOffset();
                updatedVariableColumnFile.putBlockOfBytes(varAddress, varEndOffset - varStartOffset);
                updatedFixedColumnFile.extend((toRowId + 1) * typeSize);
                Vect.shiftCopyFixedSizeColumnData(varStartOffset - copyToOffset, address + Long.BYTES, 0, toRowId - fromRowId - 1, updatedFixedColumnFile.getAppendAddress());
                updatedFixedColumnFile.jumpTo((toRowId + 1) * typeSize);
                break;
            default:
                updatedFixedColumnFile.putBlockOfBytes(address, (toRowId - fromRowId) * typeSize);
                break;
        }
    }

    private void openPartitionColumnsForRead(TableWriter tableWriter, ObjList<? extends MemoryCM> baseMemory, int partitionIndex, IntList updateColumnIndexes) {
        openPartitionColumns(tableWriter, baseMemory, partitionIndex, updateColumnIndexes, false);
    }

    private void openPartitionColumnsForWrite(TableWriter tableWriter, ObjList<MemoryCMARW> updateMemory, int partitionIndex, IntList updateColumnIndexes) {
        openPartitionColumns(tableWriter, updateMemory, partitionIndex, updateColumnIndexes, true);
    }

    private void initUpdateMemory(RecordMetadata metadata, int columnCount) {
        for (int i = updateMemory.size(); i < columnCount; i++) {
            int columnType = metadata.getColumnType(updateColumnIndexes.get(i));
            switch (columnType) {
                default:
                    baseMemory.add(Vm.getCMRInstance());
                    baseMemory.add(null);
                    updateMemory.add(Vm.getCMARWInstance());
                    updateMemory.add(null);
                    break;
                case ColumnType.STRING:
                case ColumnType.BINARY:
                    // Primary and secondary
                    baseMemory.add(Vm.getCMRInstance());
                    baseMemory.add(Vm.getCMRInstance());
                    updateMemory.add(Vm.getCMARWInstance());
                    updateMemory.add(Vm.getCMARWInstance());
                    break;

            }
        }
    }

    private void purgeOldColumnVersions(TableWriter tableWriter, IntList updateColumnIndexes, FilesFacade ff) {
        boolean anyReadersBeforeCommittedTxn = tableWriter.checkScoreboardHasReadersBeforeLastCommittedTxn();
        TableWriterMetadata writerMetadata = tableWriter.getMetadata();

        int pathTrimToLen = path.length();
        path.concat(tableWriter.getTableName());
        int pathTableLen = path.length();
        long updatedTxn = tableWriter.getTxn();

        // Process updated column by column, one at the time
        for (int updatedCol = 0, nn = updateColumnIndexes.size(); updatedCol < nn; updatedCol++) {
            int processColumnIndex = updateColumnIndexes.getQuick(updatedCol);
            CharSequence columnName = writerMetadata.getColumnName(processColumnIndex);
            int columnType = writerMetadata.getColumnType(processColumnIndex);
            cleanupColumnVersionsAsync.clear();

            for (int i = 0, n = cleanupColumnVersions.size(); i < n; i += 4) {
                int columnIndex = (int) cleanupColumnVersions.getQuick(i);
                long columnVersion = cleanupColumnVersions.getQuick(i + 1);
                long partitionTimestamp = cleanupColumnVersions.getQuick(i + 2);
                long partitionNameTxn = cleanupColumnVersions.getQuick(i + 3);

                // Process updated column by column, one at the time
                if (columnIndex == processColumnIndex) {

                    boolean columnPurged = !anyReadersBeforeCommittedTxn;
                    if (!anyReadersBeforeCommittedTxn) {
                        path.trimTo(pathTableLen);
                        TableUtils.setPathForPartition(path, tableWriter.getPartitionBy(), partitionTimestamp, false);
                        TableUtils.txnPartitionConditionally(path, partitionNameTxn);
                        int pathPartitionLen = path.length();
                        TableUtils.dFile(path, columnName, columnVersion);
                        if (!ff.remove(path.$())) {
                            columnPurged = false;
                        }
                        if (columnPurged && ColumnType.isVariableLength(columnType)) {
                            path.trimTo(pathPartitionLen);
                            TableUtils.iFile(path, columnName, columnVersion);
                            TableUtils.iFile(path.$(), columnName, columnVersion);
                            if (!ff.remove(path.$()) && ff.exists(path)) {
                                columnPurged = false;
                            }
                        }
                        if (columnPurged && writerMetadata.isColumnIndexed(columnIndex)) {
                            path.trimTo(pathPartitionLen);
                            BitmapIndexUtils.valueFileName(path, columnName, columnVersion);
                            if (!ff.remove(path.$()) && ff.exists(path)) {
                                columnPurged = false;
                            }

                            path.trimTo(pathPartitionLen);
                            BitmapIndexUtils.keyFileName(path, columnName, columnVersion);
                            if (!ff.remove(path.$()) && ff.exists(path)) {
                                columnPurged = false;
                            }
                        }
                    }

                    if (!columnPurged) {
                        cleanupColumnVersionsAsync.add(columnVersion, partitionTimestamp, partitionNameTxn, 0L);
                    }
                }
            }

            // if anything not purged, schedule async purge
            if (cleanupColumnVersionsAsync.size() > 0) {
                purgeColumnVersionAsync(
                        tableWriter.getTableName(),
                        columnName,
                        writerMetadata.getId(),
                        columnType, tableWriter.getPartitionBy(),
                        updatedTxn,
                        cleanupColumnVersionsAsync
                );
                LOG.info().$("updated column cleanup scheduled [table=").$(tableWriter.getTableName())
                        .$(", column=").$(columnName)
                        .$(", updateTxn=").$(updatedTxn).I$();
            } else {
                LOG.info().$("updated column version cleaned [table=").$(tableWriter.getTableName())
                        .$(", column=").$(columnName)
                        .$(", updateTxn=").$(updatedTxn).I$();
            }
        }

        path.trimTo(pathTrimToLen);
    }

    private void updateColumnValues(
            TableWriter tableWriter,
            RecordMetadata metadata,
            IntList updateColumnIndexes,
            int columnCount,
            ObjList<MemoryCMR> baseMemory,
            ObjList<MemoryCMARW> updateMemory,
            long startPartitionRowId,
            long partitionRowId,
            Record masterRecord
    ) throws SqlException {
        for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
            MemoryCMR baseFixedColumnFile = baseMemory.get(2 * columnIndex);
            MemoryCMARW updatedFixedColumnFile = updateMemory.get(2 * columnIndex);
            MemoryCMR baseVariableColumnFile = baseMemory.get(2 * columnIndex + 1);
            MemoryCMARW updatedVariableColumnFile = updateMemory.get(2 * columnIndex + 1);

            int columnType = metadata.getColumnType(updateColumnIndexes.get(columnIndex));
            int typeSize = getFixedColumnSize(columnType);
            if (partitionRowId > startPartitionRowId) {
                copyRecords(startPartitionRowId, partitionRowId, baseFixedColumnFile, updatedFixedColumnFile, baseVariableColumnFile, updatedVariableColumnFile, columnType, typeSize);
            }
            switch (ColumnType.tagOf(columnType)) {
                case ColumnType.INT:
                    updatedFixedColumnFile.putInt(masterRecord.getInt(columnIndex));
                    break;
                case ColumnType.FLOAT:
                    updatedFixedColumnFile.putFloat(masterRecord.getFloat(columnIndex));
                    break;
                case ColumnType.LONG:
                    updatedFixedColumnFile.putLong(masterRecord.getLong(columnIndex));
                    break;
                case ColumnType.TIMESTAMP:
                    updatedFixedColumnFile.putLong(masterRecord.getTimestamp(columnIndex));
                    break;
                case ColumnType.DATE:
                    updatedFixedColumnFile.putLong(masterRecord.getDate(columnIndex));
                    break;
                case ColumnType.DOUBLE:
                    updatedFixedColumnFile.putDouble(masterRecord.getDouble(columnIndex));
                    break;
                case ColumnType.SHORT:
                    updatedFixedColumnFile.putShort(masterRecord.getShort(columnIndex));
                    break;
                case ColumnType.CHAR:
                    updatedFixedColumnFile.putChar(masterRecord.getChar(columnIndex));
                    break;
                case ColumnType.BYTE:
                case ColumnType.BOOLEAN:
                    updatedFixedColumnFile.putByte(masterRecord.getByte(columnIndex));
                    break;
                case ColumnType.GEOBYTE:
                    updatedFixedColumnFile.putByte(masterRecord.getGeoByte(columnIndex));
                    break;
                case ColumnType.GEOSHORT:
                    updatedFixedColumnFile.putShort(masterRecord.getGeoShort(columnIndex));
                    break;
                case ColumnType.GEOINT:
                    updatedFixedColumnFile.putInt(masterRecord.getGeoInt(columnIndex));
                    break;
                case ColumnType.GEOLONG:
                    updatedFixedColumnFile.putLong(masterRecord.getGeoLong(columnIndex));
                    break;
                case ColumnType.SYMBOL:
                    CharSequence symValue = masterRecord.getSym(columnIndex);
                    int symbolIndex = tableWriter.getSymbolIndex(updateColumnIndexes.get(columnIndex), symValue);
                    updatedFixedColumnFile.putInt(symbolIndex);
                    break;
                case ColumnType.STRING:
                    charSink.clear();
                    masterRecord.getStr(columnIndex, charSink);
                    long strOffset = updatedVariableColumnFile.putStr(charSink);
                    updatedFixedColumnFile.putLong(strOffset);
                    break;
                case ColumnType.BINARY:
                    BinarySequence binValue = masterRecord.getBin(columnIndex);
                    long binOffset = updatedVariableColumnFile.putBin(binValue);
                    updatedFixedColumnFile.putLong(binOffset);
                    break;
                default:
                    throw SqlException.$(0, "Column type ")
                            .put(ColumnType.nameOf(columnType))
                            .put(" not supported for updates");
            }
        }
    }
}
