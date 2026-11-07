package dev.conveyor.cdk;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.aps.CfnWorkspace;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.Billing;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.TableV2;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.LifecycleRule;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.amazon.awscdk.services.eks.CfnCluster;
import software.amazon.awscdk.services.eks.CfnNodegroup;
import software.amazon.awscdk.services.elasticache.CfnReplicationGroup;
import software.amazon.awscdk.services.elasticache.CfnSubnetGroup;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.opensearchservice.CapacityConfig;
import software.amazon.awscdk.services.opensearchservice.Domain;
import software.amazon.awscdk.services.opensearchservice.EbsOptions;
import software.amazon.awscdk.services.opensearchservice.EncryptionAtRestOptions;
import software.amazon.awscdk.services.opensearchservice.EngineVersion;
import software.amazon.awscdk.services.opensearchservice.ZoneAwarenessConfig;
import software.amazon.awscdk.services.rds.AuroraPostgresClusterEngineProps;
import software.amazon.awscdk.services.rds.AuroraPostgresEngineVersion;
import software.amazon.awscdk.services.rds.BackupProps;
import software.amazon.awscdk.services.rds.ClusterInstance;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseCluster;
import software.amazon.awscdk.services.rds.DatabaseClusterEngine;
import software.amazon.awscdk.services.rds.IClusterEngine;
import software.amazon.awscdk.services.rds.ParameterGroup;
import software.amazon.awscdk.services.rds.ProvisionedClusterInstanceProps;
import software.constructs.Construct;

/**
 * Conveyor on AWS. Each piece of local middleware becomes the managed service it was chosen to
 * stand in for:
 *
 * <pre>
 *   compose / kind            AWS
 *   Postgres x2 (shards)  ->  Aurora PostgreSQL x2, logical replication on (Debezium reads the WAL)
 *   Redis                 ->  ElastiCache (Redis OSS), Multi-AZ, encrypted in transit
 *   Kafka (KRaft)         ->  MSK, 3 brokers over 3 AZs, TLS, IAM auth
 *   OpenSearch            ->  OpenSearch Service, 2 AZs, encrypted
 *   DynamoDB Local        ->  DynamoDB (tracking_events, shipments): on demand, TTL, PITR
 *   kind + Helm           ->  EKS (Graviton nodes) + the same Helm chart; images in ECR
 *   Prometheus            ->  Amazon Managed Service for Prometheus (SLO rules unchanged)
 * </pre>
 *
 * Temporal and Keycloak run in the cluster (or Temporal Cloud and Cognito); Debezium Connect runs
 * in the cluster against MSK. EKS is declared with the L1 constructs so the stack needs no
 * kubectl Lambda layer; the chart is installed by the pipeline with the endpoints from the
 * outputs below.
 */
public final class ConveyorStack extends Stack {

    static final List<String> SERVICES = List.of(
            "gateway", "catalog-svc", "order-svc", "inventory-svc", "fulfillment-svc", "tracking-svc", "payment-mock");

    /** How big an environment is. Prime Day is a separate, larger environment, not a flag. */
    public record Sizing(
            InstanceSize dbSize,
            int dbReaders,
            String redisNode,
            String kafkaInstance,
            String searchInstance,
            String nodeInstance,
            int nodesMin,
            int nodesDesired,
            int nodesMax,
            boolean protect) {

        public static Sizing forEnvironment(String env) {
            return switch (env) {
                case "prime-day" ->
                    new Sizing(
                            InstanceSize.XLARGE2,
                            2,
                            "cache.r7g.xlarge",
                            "kafka.m7g.xlarge",
                            "r7g.xlarge.search",
                            "m7g.2xlarge",
                            6,
                            12,
                            40,
                            true);
                case "production" ->
                    new Sizing(
                            InstanceSize.LARGE,
                            1,
                            "cache.r7g.large",
                            "kafka.m7g.large",
                            "r7g.large.search",
                            "m7g.xlarge",
                            3,
                            6,
                            20,
                            true);
                default ->
                    new Sizing(
                            InstanceSize.LARGE,
                            0,
                            "cache.t4g.medium",
                            "kafka.t3.small",
                            "t3.medium.search",
                            "m7g.large",
                            2,
                            3,
                            6,
                            false);
            };
        }
    }

    public ConveyorStack(Construct scope, String id, StackProps props, Sizing size) {
        super(scope, id, props);
        Tags.of(this).add("project", "conveyor");
        RemovalPolicy keep = size.protect() ? RemovalPolicy.RETAIN : RemovalPolicy.DESTROY;

        // ---------------------------------------------------------------- network
        Vpc vpc = Vpc.Builder.create(this, "Vpc")
                .maxAzs(3)
                .natGateways(size.protect() ? 3 : 1)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .name("edge")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                .build(),
                        SubnetConfiguration.builder()
                                .name("apps")
                                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                                .cidrMask(20)
                                .build(),
                        SubnetConfiguration.builder()
                                .name("data")
                                .subnetType(SubnetType.PRIVATE_ISOLATED)
                                .cidrMask(24)
                                .build()))
                .build();
        SubnetSelection dataSubnets = SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_ISOLATED)
                .build();
        List<String> appSubnetIds =
                vpc.getPrivateSubnets().stream().map(ISubnet::getSubnetId).toList();
        List<String> dataSubnetIds =
                vpc.getIsolatedSubnets().stream().map(ISubnet::getSubnetId).toList();

        // The cluster's nodes are the only clients of the data tier: every data security group
        // admits this one, on its own port, and nothing else.
        SecurityGroup apps = SecurityGroup.Builder.create(this, "AppsSg")
                .vpc(vpc)
                .description("EKS nodes running the Conveyor services")
                .build();
        SecurityGroup db = dataSg(vpc, "DbSg", "Aurora PostgreSQL shard groups", apps, 5432);
        SecurityGroup redis = dataSg(vpc, "RedisSg", "ElastiCache", apps, 6379);
        SecurityGroup kafka = dataSg(vpc, "KafkaSg", "MSK brokers (TLS + IAM)", apps, 9098);
        SecurityGroup search = dataSg(vpc, "SearchSg", "OpenSearch Service", apps, 443);

        // ---------------------------------------------------------------- Postgres: two shard groups
        IClusterEngine engine = DatabaseClusterEngine.auroraPostgres(AuroraPostgresClusterEngineProps.builder()
                .version(AuroraPostgresEngineVersion.of("16.6", "16"))
                .build());
        ParameterGroup logical = ParameterGroup.Builder.create(this, "LogicalReplication")
                .engine(engine)
                .description("Debezium reads the outbox from the WAL")
                .parameters(Map.of(
                        "rds.logical_replication", "1",
                        "max_replication_slots", "10",
                        "max_wal_senders", "10"))
                .build();
        DatabaseCluster shardsA = shardGroup(
                "ShardsA",
                "orders (shards 0-7), catalog, inventory",
                vpc,
                dataSubnets,
                db,
                engine,
                logical,
                size,
                keep);
        DatabaseCluster shardsB =
                shardGroup("ShardsB", "orders (shards 8-15)", vpc, dataSubnets, db, engine, logical, size, keep);

        // ---------------------------------------------------------------- Redis
        CfnSubnetGroup redisSubnets = CfnSubnetGroup.Builder.create(this, "RedisSubnets")
                .description("Conveyor cache")
                .subnetIds(dataSubnetIds)
                .build();
        CfnReplicationGroup cache = CfnReplicationGroup.Builder.create(this, "Redis")
                .replicationGroupDescription("Product cache, inventory gate counters, rate-limit buckets")
                .engine("redis")
                .engineVersion("7.1")
                .cacheNodeType(size.redisNode())
                .numCacheClusters(2)
                .automaticFailoverEnabled(true)
                .multiAzEnabled(true)
                .atRestEncryptionEnabled(true)
                .transitEncryptionEnabled(true)
                .cacheSubnetGroupName(redisSubnets.getRef())
                .securityGroupIds(List.of(redis.getSecurityGroupId()))
                .build();
        cache.getNode().addDependency(redisSubnets);

        // ---------------------------------------------------------------- Kafka
        software.amazon.awscdk.services.msk.CfnCluster msk =
                software.amazon.awscdk.services.msk.CfnCluster.Builder.create(this, "Kafka")
                        .clusterName("conveyor")
                        .kafkaVersion("3.9.x")
                        .numberOfBrokerNodes(3)
                        .brokerNodeGroupInfo(
                                software.amazon.awscdk.services.msk.CfnCluster.BrokerNodeGroupInfoProperty.builder()
                                        .instanceType(size.kafkaInstance())
                                        .clientSubnets(dataSubnetIds)
                                        .securityGroups(List.of(kafka.getSecurityGroupId()))
                                        .storageInfo(
                                                software.amazon.awscdk.services.msk.CfnCluster.StorageInfoProperty
                                                        .builder()
                                                        .ebsStorageInfo(software.amazon.awscdk.services.msk.CfnCluster
                                                                .EBSStorageInfoProperty.builder()
                                                                .volumeSize(size.protect() ? 1000 : 100)
                                                                .build())
                                                        .build())
                                        .build())
                        .encryptionInfo(software.amazon.awscdk.services.msk.CfnCluster.EncryptionInfoProperty.builder()
                                .encryptionInTransit(
                                        software.amazon.awscdk.services.msk.CfnCluster.EncryptionInTransitProperty
                                                .builder()
                                                .clientBroker("TLS")
                                                .inCluster(true)
                                                .build())
                                .build())
                        .clientAuthentication(
                                software.amazon.awscdk.services.msk.CfnCluster.ClientAuthenticationProperty.builder()
                                        .sasl(software.amazon.awscdk.services.msk.CfnCluster.SaslProperty.builder()
                                                .iam(
                                                        software.amazon.awscdk.services.msk.CfnCluster.IamProperty
                                                                .builder()
                                                                .enabled(true)
                                                                .build())
                                                .build())
                                        .build())
                        .build();

        // ---------------------------------------------------------------- OpenSearch
        Domain searchDomain = Domain.Builder.create(this, "Search")
                .version(EngineVersion.openSearch("2.19"))
                .capacity(CapacityConfig.builder()
                        .dataNodes(2)
                        .dataNodeInstanceType(size.searchInstance())
                        .multiAzWithStandbyEnabled(false)
                        .build())
                .zoneAwareness(ZoneAwarenessConfig.builder()
                        .enabled(true)
                        .availabilityZoneCount(2)
                        .build())
                .ebs(EbsOptions.builder().volumeSize(100).build())
                .vpc(vpc)
                .vpcSubnets(List.of(SubnetSelection.builder()
                        .subnets(vpc.getIsolatedSubnets().subList(0, 2))
                        .build()))
                .securityGroups(List.of(search))
                .encryptionAtRest(
                        EncryptionAtRestOptions.builder().enabled(true).build())
                .nodeToNodeEncryption(true)
                .enforceHttps(true)
                .removalPolicy(keep)
                .build();

        // ---------------------------------------------------------------- DynamoDB
        TableV2 events = TableV2.Builder.create(this, "TrackingEvents")
                .tableName("tracking_events")
                .partitionKey(Attribute.builder()
                        .name("shipmentId")
                        .type(AttributeType.STRING)
                        .build())
                .sortKey(Attribute.builder()
                        .name("seq")
                        .type(AttributeType.NUMBER)
                        .build())
                .billing(Billing.onDemand())
                .timeToLiveAttribute("expiresAt")
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(true)
                        .build())
                .removalPolicy(keep)
                .build();
        TableV2 shipments = TableV2.Builder.create(this, "Shipments")
                .tableName("shipments")
                .partitionKey(Attribute.builder()
                        .name("shipmentId")
                        .type(AttributeType.STRING)
                        .build())
                .billing(Billing.onDemand())
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(true)
                        .build())
                .removalPolicy(keep)
                .build();

        // ---------------------------------------------------------------- images
        for (String service : SERVICES) {
            Repository.Builder.create(this, "Repo-" + service)
                    .repositoryName("conveyor/" + service)
                    .imageScanOnPush(true)
                    .imageTagMutability(TagMutability.IMMUTABLE)
                    .lifecycleRules(List.of(LifecycleRule.builder()
                            .description("keep the last 30 images")
                            .maxImageCount(30)
                            .build()))
                    .removalPolicy(RemovalPolicy.RETAIN)
                    .build();
        }

        // ---------------------------------------------------------------- EKS
        Role clusterRole = Role.Builder.create(this, "EksClusterRole")
                .assumedBy(new ServicePrincipal("eks.amazonaws.com"))
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName("AmazonEKSClusterPolicy")))
                .build();
        CfnCluster eks = CfnCluster.Builder.create(this, "Eks")
                .name("conveyor")
                .version("1.33")
                .roleArn(clusterRole.getRoleArn())
                .resourcesVpcConfig(CfnCluster.ResourcesVpcConfigProperty.builder()
                        .subnetIds(appSubnetIds)
                        .securityGroupIds(List.of(apps.getSecurityGroupId()))
                        .endpointPrivateAccess(true)
                        .endpointPublicAccess(true)
                        .build())
                .accessConfig(CfnCluster.AccessConfigProperty.builder()
                        .authenticationMode("API")
                        .build())
                .build();
        Role nodeRole = Role.Builder.create(this, "EksNodeRole")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonEKSWorkerNodePolicy"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonEKS_CNI_Policy"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryReadOnly")))
                .build();
        CfnNodegroup nodes = CfnNodegroup.Builder.create(this, "Nodes")
                .clusterName(eks.getRef())
                .nodeRole(nodeRole.getRoleArn())
                .subnets(appSubnetIds)
                .instanceTypes(List.of(size.nodeInstance()))
                .amiType("AL2023_ARM_64_STANDARD") // Graviton: the images are built multi-arch
                .scalingConfig(CfnNodegroup.ScalingConfigProperty.builder()
                        .minSize(size.nodesMin())
                        .desiredSize(size.nodesDesired())
                        .maxSize(size.nodesMax())
                        .build())
                .updateConfig(CfnNodegroup.UpdateConfigProperty.builder()
                        .maxUnavailablePercentage(25)
                        .build())
                .build();
        nodes.getNode().addDependency(eks);

        // ---------------------------------------------------------------- observability
        CfnWorkspace prometheus = CfnWorkspace.Builder.create(this, "Prometheus")
                .alias("conveyor")
                .build();

        // ---------------------------------------------------------------- what the chart needs
        output(
                "ShardsAEndpoint",
                shardsA.getClusterEndpoint().getHostname(),
                "endpoints.pg0 (jdbc:postgresql://<this>:5432)");
        output("ShardsBEndpoint", shardsB.getClusterEndpoint().getHostname(), "endpoints.pg1");
        output("RedisEndpoint", cache.getAttrPrimaryEndPointAddress(), "endpoints.redis");
        output("KafkaClusterArn", msk.getAttrArn(), "bootstrap brokers: aws kafka get-bootstrap-brokers");
        output("SearchEndpoint", "https://" + searchDomain.getDomainEndpoint(), "endpoints.opensearch");
        output("TrackingEventsTable", events.getTableName(), "tracking_events");
        output("ShipmentsTable", shipments.getTableName(), "shipments");
        output("EksCluster", eks.getRef(), "aws eks update-kubeconfig --name <this>");
        output("PrometheusWorkspace", prometheus.getAttrPrometheusEndpoint(), "remote_write target for the SLO rules");
    }

    private SecurityGroup dataSg(Vpc vpc, String id, String what, SecurityGroup clients, int port) {
        SecurityGroup sg = SecurityGroup.Builder.create(this, id)
                .vpc(vpc)
                .description(what)
                .allowAllOutbound(false)
                .build();
        sg.addIngressRule(clients, Port.tcp(port), "from the Conveyor services only");
        return sg;
    }

    private DatabaseCluster shardGroup(
            String id,
            String holds,
            Vpc vpc,
            SubnetSelection subnets,
            SecurityGroup sg,
            IClusterEngine engine,
            ParameterGroup params,
            Sizing size,
            RemovalPolicy keep) {
        ProvisionedClusterInstanceProps instance = ProvisionedClusterInstanceProps.builder()
                .instanceType(InstanceType.of(InstanceClass.R7G, size.dbSize()))
                .build();
        List<software.amazon.awscdk.services.rds.IClusterInstance> readers = new java.util.ArrayList<>();
        for (int i = 0; i < size.dbReaders(); i++) {
            readers.add(ClusterInstance.provisioned("reader" + i, instance));
        }
        DatabaseCluster cluster = DatabaseCluster.Builder.create(this, id)
                .engine(engine)
                .writer(ClusterInstance.provisioned("writer", instance))
                .readers(readers)
                .vpc(vpc)
                .vpcSubnets(subnets)
                .securityGroups(List.of(sg))
                .parameterGroup(params)
                .defaultDatabaseName("orders")
                .credentials(Credentials.fromGeneratedSecret("conveyor"))
                .storageEncrypted(true)
                .deletionProtection(size.protect())
                .backup(BackupProps.builder()
                        .retention(Duration.days(size.protect() ? 14 : 1))
                        .build())
                .cloudwatchLogsExports(List.of("postgresql"))
                .removalPolicy(keep == RemovalPolicy.RETAIN ? RemovalPolicy.SNAPSHOT : RemovalPolicy.DESTROY)
                .build();
        Tags.of(cluster).add("holds", holds);
        return cluster;
    }

    private void output(String id, String value, String use) {
        CfnOutput.Builder.create(this, id).value(value).description(use).build();
    }
}
