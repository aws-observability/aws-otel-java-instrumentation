import argparse
import json
import logging
import os
import shutil
from heapq import nsmallest
from datetime import datetime, timedelta
from pathlib import Path

import boto3

logging.basicConfig(
    format="%(asctime)-8s %(levelname)-8s %(message)s",
    level=logging.INFO,
    datefmt="%FT%TZ",
)

logger = logging.getLogger(__file__)

SOAK_TESTS_SNAPSHOTS_COMMITS_DIR = "soak-tests/snapshots/commits"

# AWS Client API Constants

COMMIT_SHA_DIMENSION_NAME = "commit_sha"
GITHUB_RUN_ID_DIMENSION_NAME = "github_run_id"
PROCESS_COMMAND_LINE_DIMENSION_NAME = "process.command_line"
METRIC_DATA_STATISTIC = "Sum"


def parse_args():
    parser = argparse.ArgumentParser(
        description="""
        produce_metric_widget_images.py produces snapshot graphs of the
        metrics used in the performance tests that were just run.
        """
    )

    parser.add_argument(
        "--cpu-load-threshold",
        required=True,
        type=int,
        help="""
        The threshold the CPU Load (as a percentage) must stay under to not
        trigger the alarm.

        Examples:

            --cpu-load-threshold=75
        """,
    )

    parser.add_argument(
        "--total-memory-threshold",
        required=True,
        type=int,
        help="""
        The threshold the Total Memory (in bytes) must stay under to not trigger
        the alarm.

        Examples:

            --total-memory-threshold=$(echo 1.5 \* 2^30 | bc)
        """,
    )

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
        period used by the metrics that the alarms monitor and is the interval
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
        "--test-duration-minutes",
        required=True,
        type=int,
        help="""
        The duration of the performance test, which is used to determine the
        start of metrics to include in the snapshots.

        Examples:

            --test-duration-minutes=$(echo 1.5 \* 2^30 | bc)
        """,
    )

    parser.add_argument(
        "--target-sha",
        required=True,
        help="""
        The SHA of the commit for the current GitHub workflow run. Used to
        query Cloudwatch by metric dimension value so metrics returned
        correspond to the app that was performance tested. Also used to create a
        folder for the snapshot PNG files.

        Examples:

            --target-sha=${{ github.sha }}
        """,
    )

    parser.add_argument(
        "--github-run-id",
        required=True,
        help="""
        The Id for the current GitHub workflow run. Used to create the name of
        the snapshot PNG file.

        Examples:

            --github-run-id=${GITHUB_RUN_ID}
        """,
    )

    parser.add_argument(
        "--app-platform",
        required=True,
        help="""
        The framework platform for the Sample App which produced the performance
        metrics. Used to create the name of the snapshot PNG file.

        Examples:

            --app-platform=flask
        """,
    )

    parser.add_argument(
        "--instrumentation-type",
        required=True,
        help="""
        The framework platform for the Sample App which produced the performance
        metrics. Used to create the name of the snapshot PNG file.

        Examples:

            --instrumentation-type=auto
        """,
    )

    parser.add_argument(
        "--max-benchmarks-to-keep",
        required=True,
        type=int,
        help="""
        The max number of benchmarks to keep. Used to limit the size of the
        snapshots/commits folder by deleting older results.

        Examples:

            --max-benchmarks-to-keep=100
        """,
    )

    parser.add_argument(
        "--github-repository",
        required=True,
        help="""
        The repository of the current workflow. Used to generate a log message
        with the location of the generated snapshot.

        Examples:

            --github-repository=${GITHUB_REPOSITORY}
        """,
    )

    return parser.parse_args()


if __name__ == "__main__":
    logger.debug("Starting script to get performance test results.")

    args = parse_args()

    start_time = (
        datetime.utcnow() - timedelta(minutes=args.test_duration_minutes)
    ).strftime("%FT%TZ")

    metric_widget_images = [
        (
            "cpu-load",
            {
                "metrics": [
                    [
                        args.logs_namespace,
                        "process.cpu.time",
                        PROCESS_COMMAND_LINE_DIMENSION_NAME,
                        args.app_process_command_line_dimension_value,
                        COMMIT_SHA_DIMENSION_NAME,
                        args.target_sha,
                        GITHUB_RUN_ID_DIMENSION_NAME,
                        args.github_run_id,
                        {
                            "id": "cpu_time_raw",
                            "label": "CPU Time Raw",
                            "visible": False,
                        },
                    ],
                    [
                        {
                            "expression": f"cpu_time_raw/PERIOD(cpu_time_raw)/{args.num_of_cpus}*100",
                            "id": "cpu_load_expr",
                            "label": f"CPU Load Percentage for {args.num_of_cpus} CPUs",
                            "color": "#1f77b4",
                        }
                    ],
                    [
                        {
                            "expression": f"TIME_SERIES({args.cpu_load_threshold})",
                            "label": "CPU Threshold",
                            "id": "cpu_load_threshold",
                            "color": "#d62728",
                        }
                    ],
                ],
                "view": "timeSeries",
                "stacked": False,
                "stat": METRIC_DATA_STATISTIC,
                "period": args.metrics_period,
                "title": f"Process CPU Load Percentage with {args.num_of_cpus} CPUs for ({args.app_platform}, {args.instrumentation_type}) Sample App",
                "yAxis": {"left": {"label": "Percentage", "showUnits": False}},
                "liveData": False,
                "width": 2043,
                "height": 250,
                "start": f"-PT{args.test_duration_minutes}M",
                "end": "P0D",
            },
        ),
        (
            "total-memory",
            {
                "metrics": [
                    [
                        args.logs_namespace,
                        "process.memory.virtual_usage",
                        PROCESS_COMMAND_LINE_DIMENSION_NAME,
                        args.app_process_command_line_dimension_value,
                        COMMIT_SHA_DIMENSION_NAME,
                        args.target_sha,
                        GITHUB_RUN_ID_DIMENSION_NAME,
                        args.github_run_id,
                        {
                            "id": "virtual_memory_raw",
                            "label": "Virtual Memory",
                            "color": "#ff7f0e",
                        },
                    ],
                    [
                        ".",
                        "process.memory.physical_usage",
                        ".",
                        ".",
                        ".",
                        ".",
                        ".",
                        ".",
                        {
                            "id": "physical_memory_raw",
                            "label": "Physical Memory",
                            "color": "#2ca02c",
                        },
                    ],
                    [
                        {
                            "expression": "SUM([virtual_memory_raw])",
                            "label": "Total Memory (Virtual Memory)",
                            "id": "total_memory_expr",
                            "color": "#1f77b4",
                        }
                    ],
                    [
                        {
                            "expression": f"TIME_SERIES({args.total_memory_threshold})",
                            "label": "Total Memory Threshold",
                            "id": "total_memory_threshold",
                            "color": "#d62728",
                        }
                    ],
                ],
                "view": "timeSeries",
                "stacked": False,
                "stat": METRIC_DATA_STATISTIC,
                "period": args.metrics_period,
                "title": f"Process Memory Usage for ({args.app_platform}, {args.instrumentation_type}) Sample App",
                "yAxis": {"left": {"showUnits": True}},
                "liveData": False,
                "width": 2043,
                "height": 250,
                "start": f"-PT{args.test_duration_minutes}M",
                "end": "P0D",
            },
        ),
    ]

    snapshots_dir = f"{SOAK_TESTS_SNAPSHOTS_COMMITS_DIR}/{ args.target_sha }/runs/{args.github_run_id}/{args.app_platform}"

    Path(snapshots_dir).mkdir(parents=True, exist_ok=True)

    aws_client = boto3.client("cloudwatch")

    for snapshot_type, metric_widget_params in metric_widget_images:
        metric_widget_image_bytes = aws_client.get_metric_widget_image(
            MetricWidget=json.dumps(metric_widget_params),
        )["MetricWidgetImage"]

        snapshot_location = (
            f"{snapshots_dir}/{args.instrumentation_type}-{snapshot_type}.png"
        )

        with open(
            snapshot_location,
            "wb",
        ) as file_context:
            file_context.write(metric_widget_image_bytes)

        logger.info(
            f"Will create a snapshot at URL: https://github.com/{args.github_repository}/blob/gh-pages/{snapshot_location}",
        )

    # Delete oldest snapshots

    snapshot_dirs_length = len(os.listdir(SOAK_TESTS_SNAPSHOTS_COMMITS_DIR))

    if snapshot_dirs_length > args.max_benchmarks_to_keep:
        oldest_snapshot_dirs = nsmallest(
            snapshot_dirs_length - args.max_benchmarks_to_keep,
            Path(SOAK_TESTS_SNAPSHOTS_COMMITS_DIR).iterdir(),
            key=os.path.getmtime,
        )
        for old_snapshots_dir in oldest_snapshot_dirs:
            shutil.rmtree(old_snapshots_dir, ignore_errors=True)

    logger.info("Done creating metric widget images.")
