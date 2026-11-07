package dev.conveyor.cdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

/** The synthesized CloudFormation, asserted without an AWS account. */
class ConveyorStackTest {

    private static Template synth(String env) {
        App app = new App();
        ConveyorStack stack = new ConveyorStack(
                app,
                "Conveyor-" + env,
                StackProps.builder()
                        .env(Environment.builder()
                                .account("123456789012")
                                .region("us-east-1")
                                .build())
                        .build(),
                ConveyorStack.Sizing.forEnvironment(env));
        return Template.fromStack(stack);
    }

    private static final Template PROD = synth("production");
    private static final Template PRIME = synth("prime-day");
    private static final Template STAGING = synth("staging");

    @Test
    void twoAuroraShardGroupsWithLogicalReplicationForDebezium() {
        PROD.resourceCountIs("AWS::RDS::DBCluster", 2);
        PROD.allResourcesProperties(
                "AWS::RDS::DBCluster",
                Map.of("StorageEncrypted", true, "DeletionProtection", true, "Engine", "aurora-postgresql"));
        PROD.hasResourceProperties(
                "AWS::RDS::DBClusterParameterGroup",
                Map.of("Parameters", Match.objectLike(Map.of("rds.logical_replication", "1"))));
        STAGING.allResourcesProperties("AWS::RDS::DBCluster", Map.of("DeletionProtection", false));
    }

    @Test
    void trackingTablesMatchWhatTrackingSvcCreatesLocally() {
        PROD.hasResourceProperties(
                "AWS::DynamoDB::GlobalTable",
                Map.of(
                        "TableName",
                        "tracking_events",
                        "KeySchema",
                        List.of(
                                Map.of("AttributeName", "shipmentId", "KeyType", "HASH"),
                                Map.of("AttributeName", "seq", "KeyType", "RANGE")),
                        "BillingMode",
                        "PAY_PER_REQUEST",
                        "TimeToLiveSpecification",
                        Map.of("AttributeName", "expiresAt", "Enabled", true)));
        PROD.hasResourceProperties(
                "AWS::DynamoDB::GlobalTable",
                Map.of(
                        "TableName",
                        "shipments",
                        "KeySchema",
                        List.of(Map.of("AttributeName", "shipmentId", "KeyType", "HASH"))));
    }

    @Test
    void theDataTierAdmitsOnlyTheServicesSecurityGroup() {
        Map<String, Map<String, Object>> groups = PROD.findResources("AWS::EC2::SecurityGroup");
        groups.forEach((id, group) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) group.get("Properties");
            Object ingress = props.get("SecurityGroupIngress");
            assertThat(ingress)
                    .as("%s has no inline ingress open to an address range", id)
                    .isNull();
        });
        Map<String, Map<String, Object>> rules = PROD.findResources("AWS::EC2::SecurityGroupIngress");
        assertThat(rules).hasSize(4); // Postgres, Redis, Kafka, OpenSearch
        rules.values().forEach(rule -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) rule.get("Properties");
            assertThat(props).containsKey("SourceSecurityGroupId").doesNotContainKey("CidrIp");
        });
    }

    @Test
    void cacheKafkaAndSearchAreEncryptedAndSpreadOverZones() {
        PROD.hasResourceProperties(
                "AWS::ElastiCache::ReplicationGroup",
                Map.of(
                        "MultiAZEnabled", true,
                        "AtRestEncryptionEnabled", true,
                        "TransitEncryptionEnabled", true));
        PROD.hasResourceProperties(
                "AWS::MSK::Cluster",
                Map.of(
                        "NumberOfBrokerNodes",
                        3,
                        "EncryptionInfo",
                        Map.of("EncryptionInTransit", Map.of("ClientBroker", "TLS", "InCluster", true))));
        PROD.hasResourceProperties(
                "AWS::OpenSearchService::Domain",
                Map.of(
                        "NodeToNodeEncryptionOptions", Map.of("Enabled", true),
                        "EncryptionAtRestOptions", Map.of("Enabled", true),
                        "ClusterConfig", Match.objectLike(Map.of("ZoneAwarenessEnabled", true))));
    }

    @Test
    void everyServiceHasAnImmutableScannedRepository() {
        PROD.resourceCountIs("AWS::ECR::Repository", ConveyorStack.SERVICES.size());
        PROD.allResourcesProperties(
                "AWS::ECR::Repository",
                Map.of("ImageTagMutability", "IMMUTABLE", "ImageScanningConfiguration", Map.of("ScanOnPush", true)));
    }

    @Test
    void primeDayIsABiggerEnvironmentNotAFlag() {
        PRIME.hasResourceProperties(
                "AWS::EKS::Nodegroup", Map.of("ScalingConfig", Map.of("MinSize", 6, "DesiredSize", 12, "MaxSize", 40)));
        PROD.hasResourceProperties(
                "AWS::EKS::Nodegroup", Map.of("ScalingConfig", Map.of("MinSize", 3, "DesiredSize", 6, "MaxSize", 20)));
        PRIME.resourceCountIs("AWS::RDS::DBInstance", 2 * 3); // writer + 2 readers per shard group
        STAGING.resourceCountIs("AWS::RDS::DBInstance", 2); // writers only
    }
}
