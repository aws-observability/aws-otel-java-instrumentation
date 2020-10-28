## Springboot App
This application contains 2 paths 

1. /aws-sdk-call
2. /outgoing-http-call

By hitting `/aws-sdk-call` path app basically makes a call to AWS S3 service and perform `listBuckets` operation and by hitting `/outgoing-http-call` app makes a call `aws.amazon.com` url. This application can be used in integration testing workflow to test various client side instrumentation with Spring web server. App can also have more paths in order to test various instrumentations. e.g. `sql-call` path to test sql instrumentation in integration test. 




