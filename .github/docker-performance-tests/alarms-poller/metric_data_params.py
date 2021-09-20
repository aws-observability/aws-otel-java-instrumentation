import argparse

# AWS Client API Constants

COMMIT_SHA_DIMENSION_NAME = "commit_sha"
GITHUB_RUN_ID_DIMENSION_NAME = "github_run_id"
PROCESS_COMMAND_LINE_DIMENSION_NAME = "process.command_line"

METRIC_DATA_STATISTIC = "Sum"


def add_arguments(parser: argparse.ArgumentParser):
    parser.add_argument(
        "--logs-namespace",
        required=True,
        help="""
        The namespace of the logs that the alarm should poll.

        Examples:

            --logs-namespace=aws-observability/aws-otel-python/soak-tests
        """,
    )

    parser.add_argument(
        "--metrics-period",
        required=True,
        type=int,
        help="""
        The interval at which performance metrics are collected. This is the
        period used for the metrics monitored by the alarms and is the interval
        with which the script polls the Performance Test alarms (in seconds).

        Examples:

            --metrics-period=600
        """,
    )

    parser.add_argument(
        "--num-of-cpus",
        required=True,
        type=int,
        help="""
        The number of CPUs used when running the performance tests.

        Examples:

            --num-of-cpus=2
        """,
    )

    parser.add_argument(
        "--app-process-command-line-dimension-value",
        required=True,
        help="""
        The Cloudwatch metric dimension value which corresponds to the command
        line string used to run the sample app process. This sample app is the
        one being tested for performance. The alarms being polled in this script
        monitor metrics which contain this dimension value.

        Examples:

            --app-process-command-line-dimension-value='/usr/local/bin/python3 application.py'
        """,
    )

    parser.add_argument(
        "--target-sha",
        required=True,
        help="""
        The SHA of the commit for the current GitHub workflow run. Used to
        query Cloudwatch by metric dimension value so metrics returned
        correspond to the app that was performance tested.

        Examples:

            --target-sha=${{ github.sha }}
        """,
    )

    parser.add_argument(
        "--github-run-id",
        required=True,
        help="""
        The Id for the current GitHub workflow run. Used as a dimension by which
        metrics are queried in Cloudwatch by so that metrics returned correspond
        to the correct run of the app that was performance tested.

        Examples:

            --github-run-id=$GITHUB_RUN_ID
        """,
    )


def get_metric_data_params(args):
    cpu_load_metric_data_queries = [
        {
            "Id": "cpu_time_raw",
            "MetricStat": {
                "Metric": {
                    "Namespace": args.logs_namespace,
                    "MetricName": "process.cpu.time",
                    "Dimensions": [
                        {
                            "Name": PROCESS_COMMAND_LINE_DIMENSION_NAME,
                            "Value": args.app_process_command_line_dimension_value,
                        },
                        {
                            "Name": COMMIT_SHA_DIMENSION_NAME,
                            "Value": args.target_sha,
                        },
                        {
                            "Name": GITHUB_RUN_ID_DIMENSION_NAME,
                            "Value": args.github_run_id,
                        },
                    ],
                },
                "Stat": METRIC_DATA_STATISTIC,
                "Period": args.metrics_period,
            },
            "Label": "CPU Time Raw",
            "ReturnData": False,
        },
        {
            "Id": "cpu_load_expr",
            "Expression": f"cpu_time_raw/PERIOD(cpu_time_raw)/{args.num_of_cpus}*100",
            "Label": f"CPU Load Percentage for {args.num_of_cpus} CPUs",
            "ReturnData": True,
            "Period": args.metrics_period,
        },
    ]

    total_memory_metric_data_queries = [
        {
            "Id": "virtual_memory_raw",
            "MetricStat": {
                "Metric": {
                    "Namespace": args.logs_namespace,
                    "MetricName": "process.memory.virtual_usage",
                    "Dimensions": [
                        {
                            "Name": PROCESS_COMMAND_LINE_DIMENSION_NAME,
                            "Value": args.app_process_command_line_dimension_value,
                        },
                        {
                            "Name": COMMIT_SHA_DIMENSION_NAME,
                            "Value": args.target_sha,
                        },
                        {
                            "Name": GITHUB_RUN_ID_DIMENSION_NAME,
                            "Value": args.github_run_id,
                        },
                    ],
                },
                "Stat": METRIC_DATA_STATISTIC,
                "Period": args.metrics_period,
            },
            "Label": "Virtual Memory",
            "ReturnData": False,
        },
        {
            "Id": "physical_memory_raw",
            "MetricStat": {
                "Metric": {
                    "Namespace": args.logs_namespace,
                    "MetricName": "process.memory.physical_usage",
                    "Dimensions": [
                        {
                            "Name": PROCESS_COMMAND_LINE_DIMENSION_NAME,
                            "Value": args.app_process_command_line_dimension_value,
                        },
                        {
                            "Name": COMMIT_SHA_DIMENSION_NAME,
                            "Value": args.target_sha,
                        },
                        {
                            "Name": GITHUB_RUN_ID_DIMENSION_NAME,
                            "Value": args.github_run_id,
                        },
                    ],
                },
                "Stat": METRIC_DATA_STATISTIC,
                "Period": args.metrics_period,
            },
            "Label": "Physical Memory",
            "ReturnData": False,
        },
        {
            "Id": "total_memory_expr",
            "Expression": "SUM([virtual_memory_raw])",
            "Label": "Total Memory",
            "ReturnData": True,
            "Period": args.metrics_period,
        },
    ]

    return cpu_load_metric_data_queries, total_memory_metric_data_queries
