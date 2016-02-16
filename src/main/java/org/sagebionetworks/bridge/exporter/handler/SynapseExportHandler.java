package org.sagebionetworks.bridge.exporter.handler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.TableEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.exporter.exceptions.BridgeExporterException;
import org.sagebionetworks.bridge.exporter.metrics.Metrics;
import org.sagebionetworks.bridge.exporter.util.BridgeExporterUtil;
import org.sagebionetworks.bridge.exporter.worker.ExportSubtask;
import org.sagebionetworks.bridge.exporter.worker.ExportTask;
import org.sagebionetworks.bridge.exporter.worker.ExportWorkerManager;
import org.sagebionetworks.bridge.exporter.worker.TsvInfo;
import org.sagebionetworks.bridge.file.FileHelper;
import org.sagebionetworks.bridge.exporter.synapse.SynapseHelper;

/**
 * This is a handler who's solely responsible for a single table in Synapse. This handler is assigned a stream of DDB
 * records to create a TSV, then uploads the TSV to the Synapse table. If the Synapse Table doesn't exist, this handler
 * will create it.
 */
public abstract class SynapseExportHandler extends ExportHandler {
    private static final Logger LOG = LoggerFactory.getLogger(SynapseExportHandler.class);

    // package-scoped so the unit tests can test against these
    static final Set<ACCESS_TYPE> ACCESS_TYPE_ALL = ImmutableSet.copyOf(ACCESS_TYPE.values());
    static final Set<ACCESS_TYPE> ACCESS_TYPE_READ = ImmutableSet.of(ACCESS_TYPE.READ, ACCESS_TYPE.DOWNLOAD);
    static final String DDB_KEY_TABLE_ID = "tableId";

    // Package-scoped to be available to unit tests.
    static final List<ColumnModel> COMMON_COLUMN_LIST;
    static {
        ImmutableList.Builder<ColumnModel> columnListBuilder = ImmutableList.builder();

        ColumnModel recordIdColumn = new ColumnModel();
        recordIdColumn.setName("recordId");
        recordIdColumn.setColumnType(ColumnType.STRING);
        recordIdColumn.setMaximumSize(36L);
        columnListBuilder.add(recordIdColumn);

        ColumnModel healthCodeColumn = new ColumnModel();
        healthCodeColumn.setName("healthCode");
        healthCodeColumn.setColumnType(ColumnType.STRING);
        healthCodeColumn.setMaximumSize(36L);
        columnListBuilder.add(healthCodeColumn);

        ColumnModel externalIdColumn = new ColumnModel();
        externalIdColumn.setName("externalId");
        externalIdColumn.setColumnType(ColumnType.STRING);
        externalIdColumn.setMaximumSize(128L);
        columnListBuilder.add(externalIdColumn);

        ColumnModel dataGroupsColumn = new ColumnModel();
        dataGroupsColumn.setName("dataGroups");
        dataGroupsColumn.setColumnType(ColumnType.STRING);
        dataGroupsColumn.setMaximumSize(100L);
        columnListBuilder.add(dataGroupsColumn);

        // NOTE: ColumnType.DATE is actually a timestamp. There is no calendar date type.
        ColumnModel uploadDateColumn = new ColumnModel();
        uploadDateColumn.setName("uploadDate");
        uploadDateColumn.setColumnType(ColumnType.STRING);
        uploadDateColumn.setMaximumSize(10L);
        columnListBuilder.add(uploadDateColumn);

        ColumnModel createdOnColumn = new ColumnModel();
        createdOnColumn.setName("createdOn");
        createdOnColumn.setColumnType(ColumnType.DATE);
        columnListBuilder.add(createdOnColumn);

        ColumnModel appVersionColumn = new ColumnModel();
        appVersionColumn.setName("appVersion");
        appVersionColumn.setColumnType(ColumnType.STRING);
        appVersionColumn.setMaximumSize(48L);
        columnListBuilder.add(appVersionColumn);

        ColumnModel phoneInfoColumn = new ColumnModel();
        phoneInfoColumn.setName("phoneInfo");
        phoneInfoColumn.setColumnType(ColumnType.STRING);
        phoneInfoColumn.setMaximumSize(48L);
        columnListBuilder.add(phoneInfoColumn);

        COMMON_COLUMN_LIST = columnListBuilder.build();
    }

    private static final Joiner DATA_GROUP_JOINER = Joiner.on(',').useForNull("");

    /**
     * Given the record (contained in the subtask), serialize the results and write to a TSV. If a TSV hasn't been
     * created for this handler for the parent task, this will also initialize that TSV.
     */
    @Override
    public void handle(ExportSubtask subtask) {
        String tableKey = getDdbTableKeyValue();
        ExportTask task = subtask.getParentTask();
        Metrics metrics = task.getMetrics();
        String recordId = subtask.getOriginalRecord().getString("id");

        try {
            // get TSV info (init if necessary)
            TsvInfo tsvInfo = initTsvForTask(task);

            // Construct row value map. Merge row values from common columns and getTsvRowValueMap()
            Map<String, String> rowValueMap = new HashMap<>();
            rowValueMap.putAll(getCommonRowValueMap(subtask));
            rowValueMap.putAll(getTsvRowValueMap(subtask));

            // write to TSV
            tsvInfo.writeRow(rowValueMap);
            metrics.incrementCounter(tableKey + ".lineCount");
        } catch (BridgeExporterException | IOException | RuntimeException | SynapseException ex) {
            metrics.incrementCounter(tableKey + ".errorCount");
            LOG.error("Error processing record " + recordId + " for table " + tableKey + ": " + ex.getMessage(), ex);
        }
    }

    // Gets the TSV for the task, initializing it if it hasn't been created yet. Also initializes the Synapse table if
    // it hasn't been created.
    private synchronized TsvInfo initTsvForTask(ExportTask task) throws BridgeExporterException {
        // check if the TSV is already saved in the task
        TsvInfo savedTsvInfo = getTsvInfoForTask(task);
        if (savedTsvInfo != null) {
            return savedTsvInfo;
        }

        try {
            // get column name list
            List<String> columnNameList = getColumnNameList(task);

            // create TSV and writer
            FileHelper fileHelper = getManager().getFileHelper();
            File tsvFile = fileHelper.newFile(task.getTmpDir(), getDdbTableKeyValue() + ".tsv");
            PrintWriter tsvWriter = new PrintWriter(fileHelper.getWriter(tsvFile));

            // create TSV info
            TsvInfo tsvInfo = new TsvInfo(columnNameList, tsvFile, tsvWriter);
            setTsvInfoForTask(task, tsvInfo);
            return tsvInfo;
        } catch (FileNotFoundException | SynapseException ex) {
            throw new BridgeExporterException("Error initializing TSV: " + ex.getMessage(), ex);
        }
    }

    // Gets the column name list from Synapse. If the Synapse table doesn't exist, this will create it. This is called
    // when initializing the TSV for a task.
    private List<String> getColumnNameList(ExportTask task) throws BridgeExporterException, SynapseException {
        String synapseTableId = getSynapseTableIdFromDdb(task);
        if (synapseTableId != null) {
            return getColumnNameListForExistingTable(synapseTableId);
        } else {
            return getColumnNameListForNewTable(task);
        }
    }

    // Gets the Synapse table ID, using the DDB Synapse table map. Returns null if the Synapse table doesn't exist (no
    // entry in the DDB table).
    private String getSynapseTableIdFromDdb(ExportTask task) {
        Table synapseTableMap = getSynapseDdbTable(task);
        Item tableMapItem = synapseTableMap.getItem(getDdbTableKeyName(), getDdbTableKeyValue());
        if (tableMapItem != null) {
            return tableMapItem.getString(DDB_KEY_TABLE_ID);
        } else {
            return null;
        }
    }

    // Writes the Synapse table ID back to the DDB Synapse table map. This is called at the end of Synapse table
    // creation.
    private void setSynapseTableIdToDdb(ExportTask task, String synapseTableId) {
        Table synapseTableMap = getSynapseDdbTable(task);
        Item synapseTableNewItem = new Item();
        synapseTableNewItem.withString(getDdbTableKeyName(), getDdbTableKeyValue());
        synapseTableNewItem.withString(DDB_KEY_TABLE_ID, synapseTableId);
        synapseTableMap.putItem(synapseTableNewItem);
    }

    // Helper method to get the DDB Synapse table map, called both to read and write the Synapse table ID to and from
    // DDB.
    private Table getSynapseDdbTable(ExportTask task) {
        ExportWorkerManager manager = getManager();
        String ddbPrefix = manager.getExporterDdbPrefixForTask(task);
        return manager.getDdbClient().getTable(ddbPrefix + getDdbTableName());
    }

    // Helper method to get the column name list for an existing Synapse table. This queries the column model list from
    // Synapse.
    private List<String> getColumnNameListForExistingTable(String synapseTableId) throws SynapseException {
        // Get columns from Synapse
        ExportWorkerManager manager = getManager();
        SynapseHelper synapseHelper = manager.getSynapseHelper();
        List<ColumnModel> columnModelList = synapseHelper.getColumnModelsForTableWithRetry(synapseTableId);

        // Extract column names from column models
        List<String> columnNameList = new ArrayList<>();
        //noinspection Convert2streamapi
        for (ColumnModel oneColumnModel : columnModelList) {
            columnNameList.add(oneColumnModel.getName());
        }
        return columnNameList;
    }

    // Helper method to get the column name list for a new table. This creates a new Synapse table and returns its
    // column list.
    private List<String> getColumnNameListForNewTable(ExportTask task) throws BridgeExporterException,
            SynapseException {
        ExportWorkerManager manager = getManager();
        SynapseHelper synapseHelper = manager.getSynapseHelper();

        // Construct column definition list. Merge COMMON_COLUMN_LIST with getSynapseTableColumnList.
        List<ColumnModel> columnList = new ArrayList<>();
        columnList.addAll(COMMON_COLUMN_LIST);
        columnList.addAll(getSynapseTableColumnList());

        // Create columns
        List<ColumnModel> createdColumnList = synapseHelper.createColumnModelsWithRetry(columnList);
        if (columnList.size() != createdColumnList.size()) {
            throw new BridgeExporterException("Error creating Synapse table " + getDdbTableKeyValue()
                    + ": Tried to create " + columnList.size() + " columns. Actual: " + createdColumnList.size()
                    + " columns.");
        }

        List<String> columnIdList = new ArrayList<>();
        List<String> columnNameList = new ArrayList<>();
        for (ColumnModel oneCreatedColumn : createdColumnList) {
            columnIdList.add(oneCreatedColumn.getId());
            columnNameList.add(oneCreatedColumn.getName());
        }

        // create table - Synapse table names must be unique, so use the DDB key value as the name.
        TableEntity synapseTable = new TableEntity();
        synapseTable.setName(getDdbTableKeyValue());
        synapseTable.setParentId(manager.getSynapseProjectIdForStudyAndTask(getStudyId(), task));
        synapseTable.setColumnIds(columnIdList);
        TableEntity createdTable = synapseHelper.createTableWithRetry(synapseTable);
        String synapseTableId = createdTable.getId();

        // create ACLs
        Set<ResourceAccess> resourceAccessSet = new HashSet<>();

        ResourceAccess exporterOwnerAccess = new ResourceAccess();
        exporterOwnerAccess.setPrincipalId(manager.getSynapsePrincipalId());
        exporterOwnerAccess.setAccessType(ACCESS_TYPE_ALL);
        resourceAccessSet.add(exporterOwnerAccess);

        ResourceAccess dataAccessTeamAccess = new ResourceAccess();
        dataAccessTeamAccess.setPrincipalId(manager.getDataAccessTeamIdForStudy(getStudyId()));
        dataAccessTeamAccess.setAccessType(ACCESS_TYPE_READ);
        resourceAccessSet.add(dataAccessTeamAccess);

        AccessControlList acl = new AccessControlList();
        acl.setId(synapseTableId);
        acl.setResourceAccess(resourceAccessSet);
        synapseHelper.createAclWithRetry(acl);

        // write back to DDB table
        setSynapseTableIdToDdb(task, synapseTableId);

        return columnNameList;
    }

    // Helper method to get row values that are common across all Synapse tables and handlers.
    private Map<String, String> getCommonRowValueMap(ExportSubtask subtask) {
        ExportTask task = subtask.getParentTask();
        Item record = subtask.getOriginalRecord();
        String recordId = record.getString("id");

        // get phone and app info
        PhoneAppVersionInfo phoneAppVersionInfo = PhoneAppVersionInfo.fromRecord(record);
        String appVersion = phoneAppVersionInfo.getAppVersion();
        String phoneInfo = phoneAppVersionInfo.getPhoneInfo();

        // construct row
        Map<String, String> rowValueMap = new HashMap<>();
        rowValueMap.put("recordId", recordId);
        rowValueMap.put("healthCode", record.getString("healthCode"));
        rowValueMap.put("externalId", BridgeExporterUtil.sanitizeDdbValue(record, "userExternalId", 128, recordId));

        // Data groups, if present. Sort them in alphabetical order, so they appear consistently in Synapse.
        Set<String> dataGroupSet = record.getStringSet("userDataGroups");
        if (dataGroupSet != null) {
            List<String> dataGroupList = new ArrayList<>();
            dataGroupList.addAll(dataGroupSet);
            Collections.sort(dataGroupList);
            rowValueMap.put("dataGroups", DATA_GROUP_JOINER.join(dataGroupList));
        }

        rowValueMap.put("uploadDate", task.getExporterDate().toString());

        // createdOn as a long epoch millis
        rowValueMap.put("createdOn", String.valueOf(record.getLong("createdOn")));

        rowValueMap.put("appVersion", appVersion);
        rowValueMap.put("phoneInfo", phoneInfo);

        return rowValueMap;
    }

    /**
     * This is called at the end of the record stream for a given export task. This will then upload the TSV to
     * Synapse.
     */
    public void uploadToSynapseForTask(ExportTask task) throws BridgeExporterException, IOException, SynapseException {
        ExportWorkerManager manager = getManager();
        TsvInfo tsvInfo = getTsvInfoForTask(task);
        if (tsvInfo == null) {
            // No TSV. This means we never wrote any records. Skip.
            return;
        }

        File tsvFile = tsvInfo.getFile();
        tsvInfo.flushAndCloseWriter();

        // filter on line count
        int lineCount = tsvInfo.getLineCount();
        if (lineCount > 0) {
            String projectId = manager.getSynapseProjectIdForStudyAndTask(getStudyId(), task);
            String synapseTableId = getSynapseTableIdFromDdb(task);
            long linesProcessed = manager.getSynapseHelper().uploadTsvFileToTable(projectId, synapseTableId, tsvFile);
            if (linesProcessed != lineCount) {
                throw new BridgeExporterException("Wrong number of lines processed importing to table=" +
                        synapseTableId + ", expected=" + lineCount + ", actual=" + linesProcessed);
            }

            LOG.info("Done uploading to Synapse for table name=" + getDdbTableKeyValue() + ", id=" + synapseTableId);
        }

        // We've successfully processed the file. We can delete the file now.
        manager.getFileHelper().deleteFile(tsvFile);
    }

    /** Table name (excluding prefix) of the DDB table that holds Synapse table IDs. */
    protected abstract String getDdbTableName();

    /** Hash key name of the DDB table that holds Synapse table IDs. */
    protected abstract String getDdbTableKeyName();

    /**
     * Hash key value for the DDB table that holds the Synapse table IDs. Since this uniquely identifies the Synapse
     * table, and since Synapse table names need to be unique, this is also used as the Synapse table name.
     */
    protected abstract String getDdbTableKeyValue();

    /**
     * List of Synapse table column model objects, to be used to create both the column models and the Synapse table.
     * This excludes columns common to all Bridge tables defined in COMMON_COLUMN_LIST.
     */
    protected abstract List<ColumnModel> getSynapseTableColumnList();

    /** Get the TSV saved in the task for this handler. */
    protected abstract TsvInfo getTsvInfoForTask(ExportTask task);

    /** Save the TSV into the task for this handler. */
    protected abstract void setTsvInfoForTask(ExportTask task, TsvInfo tsvInfo);

    /** Creates a row values for a single row from the given export task. */
    protected abstract Map<String, String> getTsvRowValueMap(ExportSubtask subtask) throws IOException, SynapseException;
}