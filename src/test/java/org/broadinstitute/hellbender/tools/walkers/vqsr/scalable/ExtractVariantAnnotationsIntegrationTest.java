package org.broadinstitute.hellbender.tools.walkers.vqsr.scalable;

import org.broadinstitute.hellbender.CommandLineProgramTest;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public final class ExtractVariantAnnotationsIntegrationTest extends CommandLineProgramTest {
    @Test
    public void test1kgp50ExomesSNP() {
        final String[] arguments = {
                "-L", "chr1",
                "-V", "/home/slee/working/vqsr/1kgp-50-exomes/resources/1kgp-50-exomes.sites_only.vcf.gz",
                "-O", "/home/slee/working/vqsr/scalable/extract-test/test.snp",
                "--maximum-chunk-size", "100000",
                "-an", "FS",
                "-an", "ReadPosRankSum",
                "-an", "MQRankSum",
                "-an", "QD",
                "-an", "SOR",
                "-an", "MQ",
                "--trust-all-polymorphic",
                "-mode", "SNP",
                "--resource:hapmap,training=true,truth=true", "/mnt/4AB658D7B658C4DB/working/ref/hapmap_3.3.hg38.vcf.gz",
                "--resource:omni,training=true,truth=true", "/mnt/4AB658D7B658C4DB/working/ref/1000G_omni2.5.hg38.vcf.gz",
                "--resource:1000G,training=true,truth=false", "/mnt/4AB658D7B658C4DB/working/ref/1000G_phase1.snps.high_confidence.hg38.vcf.gz",
                "--verbosity", "DEBUG"
        };
        runCommandLine(arguments);
    }

    @Test
    public void test1kgp50ExomesIndel() {
        final String[] arguments = {
                "-L", "chr1",
                "-V", "/home/slee/working/vqsr/1kgp-50-exomes/resources/1kgp-50-exomes.sites_only.vcf.gz",
                "-O", "/home/slee/working/vqsr/scalable/extract-test/test.indel",
                "-an", "FS",
                "-an", "ReadPosRankSum",
                "-an", "MQRankSum",
                "-an", "QD",
                "-an", "SOR",
                "-an", "MQ",
                "--trust-all-polymorphic",
                "-mode", "INDEL",
                "--resource:mills,training=true,truth=true", "/mnt/4AB658D7B658C4DB/working/ref/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz",
                "--verbosity", "DEBUG"
        };
        runCommandLine(arguments);
    }

    @Test
    public void testJbxSNP() {
        final String[] arguments = {
                "-V", "/home/slee/working/vqsr/scalable/jbx/resources/Test50Callset.annoated_pids.sites-only.vcf.gz",
                "-O", "/home/slee/working/vqsr/scalable/jbx/Test50Callset.snp.extract",
                "-an", "FS",
                "-an", "ReadPosRankSum",
                "-an", "MQRankSum",
                "-an", "QD",
                "-an", "SOR",
                "-an", "MQ",
                "-an", "COMBINED_TREE_SCORE",
                "--trust-all-polymorphic",
                "-mode", "SNP",
                "--resource:hapmap,training=true,truth=true", "/mnt/4AB658D7B658C4DB/working/ref/hapmap_3.3.hg38.vcf.gz",
                "--resource:omni,training=true,truth=true", "/mnt/4AB658D7B658C4DB/working/ref/1000G_omni2.5.hg38.vcf.gz",
                "--resource:1000G,training=true,truth=false", "/mnt/4AB658D7B658C4DB/working/ref/1000G_phase1.snps.high_confidence.hg38.vcf.gz",
                "--verbosity", "DEBUG"
        };
        runCommandLine(arguments);
    }
}