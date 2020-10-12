# AWS SDK Semantic Conventions

These conventions apply to operations using the AWS SDK. They map request or response parameters
in AWS SDK API calls to attributes on a Span. The conventions have been collected over time based
on feedback from AWS users of tracing and will continue to increase as new interesting conventions
are found.

Some descriptions are also provided for populating OpenTelemetry semantic conventions.

## DynamoDB

### BatchGetItem

- `awssdk.table_names` - Extract the keys of the `RequestItems` object field in the request and store as an array of strings
- `awssdk.consumed_capacity` - JSON-serialize the `ConsumedCapacity` response list field and store as a string

### BatchWriteItem

- `awssdk.table_names` - Extract the keys of the `RequestItems` object field in the request and store as an array of strings
- `awssdk.consumed_capacity` - JSON-serialize the `ConsumedCapacity` response list field and store as a string
- `awssdk.item_collection_metrics` - JSON-serialize the `ItemCollectionMetrics` response object field and store as a string

### CreateTable

- `db.name` - Copy the `TableName` request parameter

- `awssdk.global_secondary_indexes` - JSON-serialize the `GlobalSecondaryIndexes` request list field and store as a string
- `awssdk.local_secondary_indexes` - JSON-serialize the `LocalSecondaryIndexes` request list field and store as a string
- `awssdk.provisioned_throughput.read_capacity_units` - Copy the `ProvisionedThroughput.ReadCapacityUnits` request parameter and store as an integer
- `awssdk.provisioned_throughput.write_capacity_units` - Copy the `ProvisionedThroughput.ReadCapacityUnits` request parameter and store as an integer


### DeleteItem

- `db.name` - Copy the `TableName` request parameter

- `awssdk.consumed_capacity` - JSON-serialize the `ConsumedCapacity` response list field and store as a string
- `awssdk.item_collection_metrics` - JSON-serialize the `ItemCollectionMetrics` response object field and store as a string

### DeleteTable

- `db.name` - Copy the `TableName` request parameter

### DescribeTable

- `db.name` - Copy the `TableName` request parameter

### GetItem

- `db.name` - Copy the `TableName` request parameter

- `awssdk.consistent_read` - Copy the `ConsistentRead` request parameter and store as a boolean
- `awssdk.projection_expression` - Copy the `ProjectionExpression` request parameter and store as a string
- `awssdk.consumed_capacity` - JSON-serialize the `ConsumedCapacity` response list field and store as a string

### ListTables

- `awssdk.exclusive_start_table_name` - Copy the `ExclusiveStartTableName` request parameter and store as a string
- `awssdk.limit` - Copy the `Limit` request parameter and store as an integer
- `awssdk.table_count` - Fill in the number of elements in the `TableNames` response list parameter

### PutItem

- `db.name` - Copy the `TableName` request parameter

- `awssdk.consumed_capacity` - JSON-serialize the `ConsumedCapacity` response list field and store as a string
- `awssdk.item_collection_metrics` - JSON-serialize the `ItemCollectionMetrics` response object field and store as a string

### Query

- `db.name` - Copy the `TableName` request parameter

- `awssdk.attributes_to_get` - Copy the `AttributesToGet` list request parameter and store as an array of strings
- `awssdk.consistent_read` - Copy the `ConsistentRead` request parameter and store as a boolean
- `awssdk.index_name` - Copy the `IndexName` request parameter and store as a string
- `awssdk.limit` - Copy the `Limit` request parameter and store as an integer
- `awssdk.projection_expression` - Copy the `ProjectionExpression` request parameter and store as a string
- `awssdk.scan_index_forward` - Copy the `ScanIndexForward` request parameter and store as a boolean
- `awssdk.select` - Copy the `Select` request parameter and store as a string
- `awssdk.consumed_capacity` - JSON-serialize the `ConsumedCapacity` response list field and store as a string

### Scan

- `db.name` - Copy the `TableName` request parameter

- `awssdk.attributes_to_get` - Copy the `AttributesToGet` list request parameter and store as an array of strings
- `awssdk.consistent_read` - Copy the `ConsistentRead` request parameter and store as a boolean
- `awssdk.index_name` - Copy the `IndexName` request parameter and store as a string
- `awssdk.limit` - Copy the `Limit` request parameter and store as an integer
- `awssdk.projection_expression` - Copy the `ProjectionExpression` request parameter and store as a string
- `awssdk.segment` - Copy the `Segment` request parameter and store as an integer
- `awssdk.select` - Copy the `Select` request parameter and store as a string
- `awssdk.total_segments` - Copy the `TotalSegments` request parameter and store as an integer
- `awssdk.consumed_capacity` - JSON-serialize the `ConsumedCapacity` response list field and store as a string
- `awssdk.count` - Copy the `Count` response parameter and store as an integer
- `awssdk.scanned_count` - Copy the `ScannedCount` response parameter and store as an integer

### UpdateItem

- `db.name` - Copy the `TableName` request parameter

- `awssdk.consumed_capacity` - JSON-serialize the `ConsumedCapacity` response list field and store as a string
- `awssdk.item_collection_metrics` - JSON-serialize the `ItemCollectionMetrics` response object field and store as a string

### UpdateTable

- `db.name` - Copy the `TableName` request parameter

- `awssdk.attribute_definitions` - JSON-serialize the `AttributeDefinitions` request list field and store as a string
- `awssdk.global_secondary_index_updates` - JSON-serialize the `GlobalSecondaryIndexUpdates` request list field and store as a string
- `awssdk.provisioned_throughput.read_capacity_units` - Copy the `ProvisionedThroughput.ReadCapacityUnits` request parameter and store as an integer
- `awssdk.provisioned_throughput.write_capacity_units` - Copy the `ProvisionedThroughput.ReadCapacityUnits` request parameter and store as an integer

## SQS

### AddPermission

- `messaging.url` - Copy the `QueueUrl` request field and store as a string

- `awssdk.label` - Copy the `Label` request field and store as a string

### ChangeMessageVisibility

- `messaging.url` - Copy the `QueueUrl` request field and store as a string

- `awssdk.visibility_timeout` - Copy the `VisibilityTimeout` request field and store as an integer

### ChangeMessageVisibilityBatch

- `messaging.url` - Copy the `QueueUrl` request field and store as a string

<!-- TODO(anuraaga): Confirm Failed, which is in JSON but not in API spec -->

### CreateQueue

- `awssdk.attributes` - JSON serialize the `Attributes` request field and store as a string. If `Attributes` is a list of key/value pairs, pack them into an object before serializing.
- `awssdk.queue_name` - Copy the `QueueName` request field and store as a string

### DeleteMessage

- `messaging.url` - Copy the `QueueUrl` request field and store as a string

### DeleteMessageBatch

- `messaging.url` - Copy the `QueueUrl` request field and store as a string

<!-- TODO(anuraaga): Confirm Failed, which is in JSON but not in API spec -->

### DeleteQueue

- `messaging.url` - Copy the `QueueUrl` request field and store as a string

### GetQueueAttributes

- `messaging.url` - Copy the `QueueUrl` request field and store as a string

- `awssdk.attributes` - JSON serialize the `Attributes` response field and store as a string. If `Attributes` is a list of key/value pairs, pack them into an object before serializing.

### GetQueueUrl

- `messaging.url` - Copy the `QueueUrl` response field and store as a string

- `awssdk.queue_name` - Copy the `QueueName` request field and store as a string
- `awssdk.queue_owner_aws_account_id` - Copy the `QueueOwnerAWSAccountId` request field and store as a string

### ListDeadLetterSourceQueues

- `messaging.url` - Copy the `QueueUrl` request field and store as a string

- `awssdk.queue_urls` - Copy the `QueueUrls` response field and store as an array of strings

### ListQueues

- `awssdk.queue_name_prefix` - Copy the `QueueNamePrefix` request field and store as a string
- `awssdk.queue_count` - Fill in the number of elements in the `QueueUrls` response list field

### PurgeQueue

- `messaging.url` - Copy the `QueueUrl` request field and store as a string

### ReceiveMessage

- `messaging.url` - Copy the `QueueUrl` request field and store as a string
- `messaging.operation` - Fill in `receive`

- `awssdk.attribute_names` - Copy the `AttributeNames` request field and store as an array of strings
- `awssdk.max_number_of_messages` - Copy the `MaxNumberOfMessages` request field and store as an integer
- `awssdk.message_attribute_names` - Copy the `MessageAttributeNames` request field and store as an array of strings
- `awssdk.visibility_timeout` - Copy the `VisibilityTimeout` request field and store as an integer
- `awssdk.wait_time_seconds` Copy the `WaitTimeSeconds` request field and store as an integer
- `awssdk.message_count` - Fill in the number of elements in the `Messages` response list field

### RemovePermission

- `messaging.url` - Copy the `QueueUrl` request field and store as a string

### SendMessage

- `messaging.url` - Copy the `QueueUrl` request field and store as a string
- `messaging.message_id` - Copy the `MessageId` field and store as a string
- `messaging.operation` - Fill in `send`

- `awssdk.delay_seconds` - Copy the `DelaySeconds` request field and store as an integer
- `awssdk.message_attributes` - Copy the keys of the `MessageAttributes` request object field and store as an array of strings

### SendMessageBatch

- `messaging.url` - Copy the `QueueUrl` request field and store as a string
- `messaging.operation` - Fill in `send`

- `awssdk.message_count` - Fill in the number of elements in the `Messages` request list field

<!-- TODO(anuraaga): Confirm Successful and Failed, which is in JSON but not in API spec -->

### SetQueueAttributes

- `messaging.url` - Copy the `QueueUrl` request field and store as a string

- `awssdk.attribute_names` - Copy the keys of the `Attributes` request object field and store as an array of strings 

## Lambda

Refer [here](https://github.com/open-telemetry/opentelemetry-specification/blob/master/specification/trace/semantic_conventions/faas.md#outgoing-invocations)
for OTel FaaS common attributes

### Invoke

- `faas.invoked_name` - Copy the `FunctionName` request field.
- `faas.invoked_provider` - Fill in `aws`
- `faas.invoked_region` - Fill in the value of the region from the API endpoint (SDKs should provide this separate from the request)

- `awssdk.invocation_type` - Copy the `InvocationType` request field and store as a string
- `awssdk.log_type` - Copy the `LogType` request field and store as a string
- `awssdk.qualifier` - Copy the `Qualifier` request field and store as a string
- `awssdk.function_error` - Copy the `X-Amz-Function-Error` response header and store as a string. SDKs may present this as a `FunctionError` field

### InvokeAsync

- `faas.invoked_name` - Copy the `FunctionName` request field.
- `faas.invoked_provider` - Fill in `aws`
- `faas.invoked_region` - Fill in the value of the region from the API endpoint (SDKs should provide this separate from the request)

## S3

### CopyObject

- `awssdk.source_bucket_name` - Copy the `SourceBucketName` request field and store as a string
- `awssdk.source_key` - Copy the `SourceKey` request field and store as a string
- `awssdk.destination_bucket_name` - Copy the `DestinationBucketName` request field and store as a string
- `awssdk.destination_key` - Copy the `DestinationKey` request field and store as a string

### CopyPart

- `awssdk.source_bucket_name` - Copy the `SourceBucketName` request field and store as a string
- `awssdk.source_key` - Copy the `SourceKey` request field and store as a string
- `awssdk.destination_bucket_name` - Copy the `DestinationBucketName` request field and store as a string
- `awssdk.destination_key` - Copy the `DestinationKey` request field and store as a string

### GetObject

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string
- `awssdk.version_id` - Copy the `VersionId` request field and store as a string

### PutObject

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string

### GetObjectAcl

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string
- `awssdk.version_id` - Copy the `VersionId` request field and store as a string

### CreateBucket

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### ListObjectsV2

- `awssdk.prefix` - Copy the `Prefix` request field and store as a string
- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### ListObjects

- `awssdk.prefix` - Copy the `Prefix` request field and store as a string
- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetObjectTagging

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string
- `awssdk.version_id` - Copy the `VersionId` request field and store as a string

### SetObjectTagging

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string
- `awssdk.version_id` - Copy the `VersionId` request field and store as a string

### ListVersions

- `awssdk.prefix` - Copy the `Prefix` request field and store as a string
- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetObjectAcl

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string
- `awssdk.version_id` - Copy the `VersionId` request field and store as a string

### GetBucketAcl

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketAcl

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string


### HeadBucket

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### UploadPart

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string

### DeleteObject

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string

### DeleteBucket

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteObjects

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteVersion

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string
- `awssdk.version_id` - Copy the `VersionId` request field and store as a string

### GetBucketPolicy

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketPolicy

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### ListParts

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string

### RestoreObject

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string
- `awssdk.version_id` - Copy the `VersionId` request field and store as a string

### RestoreObjectV2

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string
- `awssdk.version_id` - Copy the `VersionId` request field and store as a string

### SetBucketNotificationConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteBucketLifecycleConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketNotificationConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteBucketCrossOriginConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketCrossOriginConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketCrossOriginConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### ListBucketInventoryConfigurations

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketReplicationConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketReplicationConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteBucketReplicationConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteBucketAnalyticsConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteBucketInventoryConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### ListBucketAnalyticsConfigurations

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteObjectTagging

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string
- `awssdk.version_id` - Copy the `VersionId` request field and store as a string

### SetBucketVersioningConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketVersioningConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketWebsiteConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketLifecycleConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketLifecycleConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketTaggingConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketTaggingConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetObjectMetadata

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string
- `awssdk.version_id` - Copy the `VersionId` request field and store as a string

### GetBucketLocation

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketLoggingConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### ListMultipartUploads

- `awssdk.prefix` - Copy the `Prefix` request field and store as a string
- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteBucketPolicy

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteBucketEncryption

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketAccelerateConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketWebsiteConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### CompleteMultipartUpload

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string

### InitiateMultipartUpload

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string

### SetBucketEncryption

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketLoggingConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteBucketWebsiteConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketEncryption

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### AbortMultipartUpload

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string

### GeneratePresignedUrl

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string
- `awssdk.key` - Copy the `Key` request field and store as a string
- `awssdk.version_id` - Copy the `VersionId` request field and store as a string

### DeleteBucketTaggingConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketAccelerateConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketMetricsConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### ListBucketMetricsConfigurations

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketInventoryConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketMetricsConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### SetBucketAnalyticsConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### DeleteBucketMetricsConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketAnalyticsConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

### GetBucketInventoryConfiguration

- `awssdk.bucket_name` - Copy the `BucketName` request field and store as a string

## SageMakerRuntime

### InvokeEndpoint

`awssdk.endpoint_name` - Copy the `EndpointName` request field and store as a string
