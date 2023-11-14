# Validator
This validator is a version of the ADOT test framework validator fitted to the needs of the app signals E2E tests.
It validates the metrics and traces that come out of the application after app signals has been enabled.

## Run
### Run as a command

Run the following command in the root directory of the repository to run the app signals metric and trace validations

```shell
./gradlew :testing:validator:run --args='-c validation.yml --endpoint <app-endpoint> --region <REGION> --account-id <ACCOUNT_ID> --metric-namespace <METRIC_NAMESPACE> --rollup'
```

Help

```shell
./gradlew :testing:validator:run --args='-h'
```

## Add a validation suite

1. add a config file under `resources/validations`
2. add an expected data under `resources/expected-data-template`