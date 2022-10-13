package org.broadinstitute.hellbender.tools.gvs.ingest;

import org.broadinstitute.hellbender.tools.gvs.common.SchemaUtils;
import org.broadinstitute.hellbender.utils.bigquery.BigQueryUtils;

public class SampleInfo {
    private final String projectID;
    private final String datasetName;
    private final String sampleInfoTableName;

    public SampleInfo(String projectID, String datasetName, String sampleInfoTableName) {
        this.projectID = projectID;
        this.datasetName = datasetName;
        this.sampleInfoTableName = sampleInfoTableName;
    }

    public void setSampleInfoIsLoaded(long sampleId) {
        String query = "UPDATE `" + projectID + "." + datasetName + "." + sampleInfoTableName + "` " +
                " SET " + SchemaUtils.SAMPLE_INFO_IS_LOADED_NAME + " = TRUE " +
                " WHERE " + SchemaUtils.SAMPLE_ID_FIELD_NAME + " = " + sampleId;

        BigQueryUtils.executeQuery(projectID, query, false, null);
    }
}
