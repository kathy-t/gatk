version 1.0

workflow GvsPrepareCallset {
  input {
    String project_id
    String dataset_name

    # true for control samples only, false for participant samples only
    Boolean control_samples = false

    String extract_table_prefix

    String query_project = project_id
    String destination_project = project_id
    String destination_dataset = dataset_name
    String fq_temp_table_dataset = "~{destination_project}.temp_tables"

    Array[String]? query_labels
    File? sample_names_to_extract
    String? service_account_json_path
  }

  String full_extract_prefix = if (control_samples) then "~{extract_table_prefix}_controls" else extract_table_prefix
  String fq_petvet_dataset = "~{project_id}.~{dataset_name}"
  String fq_sample_mapping_table = "~{project_id}.~{dataset_name}.sample_info"
  String fq_destination_dataset = "~{destination_project}.~{destination_dataset}"

  call PrepareRangesCallsetTask {
    input:
      destination_cohort_table_prefix = full_extract_prefix,
      sample_names_to_extract         = sample_names_to_extract,
      query_project                   = query_project,
      query_labels                    = query_labels,
      fq_petvet_dataset               = fq_petvet_dataset,
      fq_sample_mapping_table         = fq_sample_mapping_table,
      fq_temp_table_dataset           = fq_temp_table_dataset,
      fq_destination_dataset          = fq_destination_dataset,
      temp_table_ttl_in_hours         = 72,
      control_samples                 = control_samples,
      service_account_json_path       = service_account_json_path,
  }

  output {
    String fq_cohort_extract_table_prefix = PrepareRangesCallsetTask.fq_cohort_extract_table_prefix
  }
}

task PrepareRangesCallsetTask {
  # indicates that this task should NOT be call cached
  meta {
    volatile: true
  }

  input {
    String destination_cohort_table_prefix
    File? sample_names_to_extract
    String query_project

    Boolean control_samples
    String fq_petvet_dataset
    String fq_sample_mapping_table
    String fq_temp_table_dataset
    String fq_destination_dataset
    Array[String]? query_labels
    Int temp_table_ttl_in_hours = 24

    String? service_account_json_path
  }
  # Note the coercion of optional query_labels using select_first([expr, default])
  Array[String] query_label_args = if defined(query_labels) then prefix("--query_labels ", select_first([query_labels])) else []

  String has_service_account_file = if (defined(service_account_json_path)) then 'true' else 'false'
  String use_sample_names_file = if (defined(sample_names_to_extract)) then 'true' else 'false'
  String sample_list_param = if (defined(sample_names_to_extract)) then '--sample_names_to_extract sample_names_file' else '--fq_cohort_sample_names ' + fq_sample_mapping_table

  parameter_meta {
    sample_names_to_extract: {
      localization_optional: true
    }
  }

  command <<<
      set -e

      echo ~{sample_list_param}

      if [ ~{has_service_account_file} = 'true' ]; then
          gsutil cp ~{service_account_json_path} local.service_account.json
          SERVICE_ACCOUNT_STANZA="--sa_key_path local.service_account.json "
      fi

      if [ ~{use_sample_names_file} = 'true' ]; then
          gsutil cp  ~{sample_names_to_extract} sample_names_file
      fi

      python3 /app/create_ranges_cohort_extract_data_table.py \
          --control_samples ~{control_samples} \
          --fq_ranges_dataset ~{fq_petvet_dataset} \
          --fq_temp_table_dataset ~{fq_temp_table_dataset} \
          --fq_destination_dataset ~{fq_destination_dataset} \
          --destination_cohort_table_prefix ~{destination_cohort_table_prefix} \
          ~{sample_list_param} \
          --query_project ~{query_project} \
          ~{sep=" " query_label_args} \
          --fq_sample_mapping_table ~{fq_sample_mapping_table} \
          --ttl ~{temp_table_ttl_in_hours} \
          $SERVICE_ACCOUNT_STANZA
  >>>
  output {
    String fq_cohort_extract_table_prefix = "~{fq_destination_dataset}.~{destination_cohort_table_prefix}" # implementation detail of create_ranges_cohort_extract_data_table.py
  }

  runtime {
    docker: "us.gcr.io/broad-dsde-methods/variantstore:rsa_add_sample_columns_2022_03_28"
    memory: "3 GB"
    disks: "local-disk 100 HDD"
    bootDiskSizeGb: 15
    preemptible: 0
    cpu: 1
  }
}

task LocalizeFile {
  input {
    String file
    String service_account_json_path
  }

  command {
    set -euo pipefail

    gsutil cp ~{service_account_json_path} local.service_account.json
    gcloud auth activate-service-account --key-file=local.service_account.json
    gsutil cp '~{file}' .
  }

  output {
    File localized_file = basename(file)
  }

  runtime {
    docker: "gcr.io/google.com/cloudsdktool/cloud-sdk:305.0.0"
    memory: "3.75 GiB"
    cpu: "1"
    disks: "local-disk 50 HDD"
  }
}